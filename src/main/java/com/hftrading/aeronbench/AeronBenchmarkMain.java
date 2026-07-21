package com.hftrading.aeronbench;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone Aeron IPC benchmark. It intentionally does not use the trading
 * application's feed, order-book, configuration, or latency classes.
 *
 * Examples:
 *   combined --messages 10000000 --payload 48
 *   driver
 *   subscriber --messages 10000000 --payload 48
 *   publisher --messages 10000000 --payload 48
 */
public final class AeronBenchmarkMain {
    private static final int HEADER_LENGTH = Long.BYTES + Long.BYTES + Integer.BYTES;
    private static final long CLOSED = Publication.CLOSED;
    private static final long MAX_SPINS = 1_000L;
    private static final long MAX_YIELDS = 1_000L;
    private static final long MIN_PARK_NS = 1_000L;
    private static final long MAX_PARK_NS = 100_000L;

    private AeronBenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.role.equals("driver")) {
            runDriver(options);
            return;
        }
        if (options.role.equals("combined")) {
            runCombined(options);
            return;
        }
        if (options.role.equals("publisher")) {
            runPublisher(options);
            return;
        }
        if (options.role.equals("subscriber")) {
            runSubscriber(options);
            return;
        }
        throw new IllegalArgumentException("Unknown --role: " + options.role);
    }

    private static void runCombined(Options o) throws Exception {
        MediaDriver.Context driverContext = new MediaDriver.Context()
                .aeronDirectoryName(o.aeronDirectory)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);
        try (MediaDriver driver = o.embeddedDriver ? MediaDriver.launchEmbedded(driverContext) : null;
             Aeron publisherAeron = connect(o);
             Aeron subscriberAeron = connect(o)) {
            SubscriberState subscriberState = new SubscriberState(o);
            try (Subscription subscription = subscriberAeron.addSubscription(o.channel, o.streamId);
                 Publication publication = publisherAeron.addPublication(o.channel, o.streamId)) {
                awaitConnected(publication, subscription, o.timeoutSeconds);
                AtomicBoolean running = new AtomicBoolean(true);
                AtomicReference<Throwable> subscriberFailure = new AtomicReference<>();
                Thread subscriber = new Thread(() -> {
                    try {
                        poll(subscription, subscriberState, o, running);
                    } catch (Throwable t) {
                        subscriberFailure.set(t);
                    }
                }, "aeron-bench-subscriber");
                subscriber.start();
                publish(publication, o);
                subscriber.join(TimeUnit.SECONDS.toMillis(o.timeoutSeconds));
                if (subscriber.isAlive()) {
                    running.set(false);
                    subscriber.join(TimeUnit.SECONDS.toMillis(5));
                    if (subscriber.isAlive()) {
                        throw new IllegalStateException("Subscriber did not stop within timeout");
                    }
                    throw new IllegalStateException("Subscriber did not receive all messages within timeout");
                }
                if (subscriberFailure.get() != null) {
                    throw new IllegalStateException("Subscriber failed", subscriberFailure.get());
                }
                writeReport(o, subscriberState, "combined");
            }
        }
    }

    private static void runPublisher(Options o) throws Exception {
        try (Aeron aeron = connect(o);
             Publication publication = aeron.addPublication(o.channel, o.streamId)) {
            waitUntilConnected(publication, o.timeoutSeconds);
            publish(publication, o);
        }
    }

    private static void runSubscriber(Options o) throws Exception {
        SubscriberState state = new SubscriberState(o);
        try (Aeron aeron = connect(o);
             Subscription subscription = aeron.addSubscription(o.channel, o.streamId)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(o.timeoutSeconds);
            IdleStrategy idle = newIdleStrategy();
            while (subscription.imageCount() == 0 && System.nanoTime() < deadline) {
                idle.idle();
            }
            if (subscription.imageCount() == 0) {
                throw new IllegalStateException("No Aeron image appeared within timeout");
            }
            poll(subscription, state, o, new AtomicBoolean(true));
            writeReport(o, state, "subscriber");
        }
    }

    private static Aeron connect(Options o) {
        return Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(o.aeronDirectory)
                .idleStrategy(newIdleStrategy())
                .awaitingIdleStrategy(newIdleStrategy()));
    }

    private static void runDriver(Options o) throws Exception {
        MediaDriver.Context context = new MediaDriver.Context()
                .aeronDirectoryName(o.aeronDirectory)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);
        try (MediaDriver ignored = MediaDriver.launch(context)) {
            System.out.println("Aeron driver running: directory=" + o.aeronDirectory);
            new CountDownLatch(1).await();
        }
    }

    private static void awaitConnected(Publication publication, Subscription subscription, int timeoutSeconds)
            throws InterruptedException {
        waitUntilConnected(publication, timeoutSeconds);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        IdleStrategy idle = newIdleStrategy();
        while (subscription.imageCount() == 0 && System.nanoTime() < deadline) {
            idle.idle();
        }
        if (subscription.imageCount() == 0) {
            throw new IllegalStateException("Subscription has no image within timeout");
        }
    }

    private static void waitUntilConnected(Publication publication, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        IdleStrategy idle = newIdleStrategy();
        while (!publication.isConnected() && System.nanoTime() < deadline) {
            idle.idle();
        }
        if (!publication.isConnected()) {
            throw new IllegalStateException("Publication did not connect within timeout");
        }
    }

    private static void publish(Publication publication, Options o) {
        int messageLength = HEADER_LENGTH + o.payload;
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(messageLength));
        long offerFailures = 0;
        long start = System.nanoTime();
        IdleStrategy idle = newIdleStrategy();
        for (long sequence = 0; sequence < o.messages; sequence++) {
            buffer.putLong(0, sequence);
            buffer.putLong(Long.BYTES, System.nanoTime());
            buffer.putInt(Long.BYTES * 2, checksum(sequence, o.payload));
            fillPayload(buffer, HEADER_LENGTH, sequence, o.payload);

            long result;
            do {
                result = publication.offer(buffer, 0, messageLength);
                if (result < 0) {
                    offerFailures++;
                    if (result == CLOSED) {
                        throw new IllegalStateException("Publication closed while publishing sequence " + sequence);
                    }
                    idle.idle();
                }
            } while (result < 0);
            idle.reset();
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("published=%d duration_ms=%.3f throughput_msg_s=%.3f offer_failures=%d%n",
                o.messages, elapsed / 1_000_000.0, o.messages * 1_000_000_000.0 / elapsed, offerFailures);
    }

    private static void poll(Subscription subscription, SubscriberState state, Options o, AtomicBoolean running) {
        FragmentHandler handler = (buffer, offset, length, header) -> {
            if (state.received >= o.messages) return;
            if (length < HEADER_LENGTH) {
                state.invalid++;
                return;
            }
            long sequence = buffer.getLong(offset);
            long publishedNanos = buffer.getLong(offset + Long.BYTES);
            int expectedChecksum = buffer.getInt(offset + Long.BYTES * 2);
            long arrivalNanos = System.nanoTime();
            if (sequence != state.nextSequence) {
                if (sequence < state.nextSequence) state.duplicatesOrReordered++;
                else state.gaps += sequence - state.nextSequence;
                state.nextSequence = sequence + 1;
            } else {
                state.nextSequence++;
            }
            if (expectedChecksum != checksum(sequence, length - HEADER_LENGTH) ||
                    !payloadMatches(buffer, offset + HEADER_LENGTH, sequence, length - HEADER_LENGTH)) {
                state.invalid++;
            }
            if (state.received >= o.warmup) {
                long latency = arrivalNanos - publishedNanos;
                if (latency >= 0) state.histogram.recordValue(latency);
                else state.negativeTimestamps++;
            }
            state.received++;
        };

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(o.timeoutSeconds);
        IdleStrategy idle = newIdleStrategy();
        while (running.get() && state.received < o.messages && System.nanoTime() < deadline) {
            int fragments = subscription.poll(handler, o.fragmentLimit);
            idle.idle(fragments);
        }
        if (state.received != o.messages) {
            throw new IllegalStateException("Expected " + o.messages + " messages but received " + state.received);
        }
    }

    private static IdleStrategy newIdleStrategy() {
        return new BackoffIdleStrategy(MAX_SPINS, MAX_YIELDS, MIN_PARK_NS, MAX_PARK_NS);
    }

    private static void writeReport(Options o, SubscriberState state, String mode) throws Exception {
        String report = "timestamp,mode,messages,payload,warmup,received,gaps,duplicates_or_reordered,invalid," +
                "negative_timestamps,p50_ns,p99_ns,p999_ns,max_ns\n" +
                Instant.now() + "," + mode + "," + o.messages + "," + o.payload + "," + o.warmup + "," +
                state.received + "," + state.gaps + "," + state.duplicatesOrReordered + "," + state.invalid + "," +
                state.negativeTimestamps + "," + state.histogram.getValueAtPercentile(50) + "," +
                state.histogram.getValueAtPercentile(99) + "," + state.histogram.getValueAtPercentile(99.9) + "," +
                state.histogram.getMaxValue() + "\n";
        Path output = Path.of(o.output);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.print(report);
        if (state.gaps != 0 || state.duplicatesOrReordered != 0 || state.invalid != 0) {
            throw new IllegalStateException("Correctness validation failed; see report: " + o.output);
        }
    }

    private static int checksum(long sequence, int payloadLength) {
        int value = (int) (sequence ^ (sequence >>> 32));
        return value ^ payloadLength * 31;
    }

    private static void fillPayload(UnsafeBuffer buffer, int offset, long sequence, int length) {
        byte value = (byte) (sequence * 31);
        for (int i = 0; i < length; i++) buffer.putByte(offset + i, (byte) (value + i));
    }

    private static boolean payloadMatches(org.agrona.DirectBuffer buffer, int offset, long sequence, int length) {
        byte value = (byte) (sequence * 31);
        for (int i = 0; i < length; i++) {
            if (buffer.getByte(offset + i) != (byte) (value + i)) return false;
        }
        return true;
    }

    private static final class SubscriberState {
        final Histogram histogram = new Histogram(3);
        long received;
        long nextSequence;
        long gaps;
        long duplicatesOrReordered;
        long invalid;
        long negativeTimestamps;

        SubscriberState(Options o) {
            histogram.setAutoResize(true);
        }
    }

    private static final class Options {
        String role = "combined";
        String channel = "aeron:ipc?term-length=65536";
        String aeronDirectory = "aeron-bench-data";
        String output = "output/aeron_benchmark.csv";
        int streamId = 1001;
        int payload = 48;
        int fragmentLimit = 256;
        int timeoutSeconds = 60;
        long messages = 1_000_000;
        long warmup = 100_000;
        boolean embeddedDriver = true;

        static Options parse(String[] args) {
            Options o = new Options();
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected option: " + args[i]);
                String key = args[i].substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                values.put(key, value);
            }
            o.role = values.getOrDefault("role", o.role);
            o.channel = values.getOrDefault("channel", o.channel);
            o.aeronDirectory = values.getOrDefault("directory", o.aeronDirectory);
            o.output = values.getOrDefault("output", o.output);
            o.streamId = intValue(values, "stream", o.streamId);
            o.payload = intValue(values, "payload", o.payload);
            o.fragmentLimit = intValue(values, "fragment-limit", o.fragmentLimit);
            o.timeoutSeconds = intValue(values, "timeout-seconds", o.timeoutSeconds);
            o.messages = longValue(values, "messages", o.messages);
            o.warmup = longValue(values, "warmup", o.warmup);
            o.embeddedDriver = Boolean.parseBoolean(values.getOrDefault("embedded-driver", Boolean.toString(o.embeddedDriver)));
            if (o.payload < 0 || o.messages <= 0 || o.warmup < 0 || o.warmup >= o.messages) {
                throw new IllegalArgumentException("Require payload >= 0, messages > 0, and 0 <= warmup < messages");
            }
            if (o.role.equals("publisher") || o.role.equals("subscriber")) o.embeddedDriver = false;
            return o;
        }

        private static int intValue(Map<String, String> values, String key, int fallback) {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
        }

        private static long longValue(Map<String, String> values, String key, long fallback) {
            return values.containsKey(key) ? Long.parseLong(values.get(key)) : fallback;
        }
    }
}

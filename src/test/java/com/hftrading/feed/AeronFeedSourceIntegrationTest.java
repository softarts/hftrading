package com.hftrading.feed;

import com.hftrading.config.HftConfig;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full round-trip integration test: embedded driver, Java publisher → Aeron IPC
 * → {@link AeronFeedSourceImpl} subscriber, field-level assertions.
 */
class AeronFeedSourceIntegrationTest {

    @TempDir Path tempDir;

    private MediaDriver driver;
    private Aeron aeron;
    private HftConfig config;

    @BeforeEach
    void setUp() throws IOException {
        Path props = tempDir.resolve("test.properties");
        Files.writeString(props,
                "pipeline.mode=aeron\n" +
                "aeron.channel=aeron:ipc\n" +
                "aeron.stream.id=9902\n" +  // distinct stream from AeronPublisherTest
                "aeron.embed.driver=true\n" +
                "aeron.fragment.limit=128\n" +
                "benchmark.warmup.events=0\n"
        );
        config = HftConfig.load(props);

        driver = MediaDriver.launchEmbedded(
                new MediaDriver.Context().dirDeleteOnStart(true).dirDeleteOnShutdown(true));
        aeron = Aeron.connect(
                new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (aeron  != null) aeron.close();
        if (driver != null) driver.close();
    }

    // ── Full round-trip ───────────────────────────────────────────────────────

    @Test
    void fullRoundTrip_allFieldsCorrect() throws Exception {
        int numEvents = 50;
        CountDownLatch latch = new CountDownLatch(numEvents);
        List<MarketEvent> received = new ArrayList<>(numEvents);
        Object lock = new Object();

        // Subscriber
        AeronFeedSourceImpl subscriber = new AeronFeedSourceImpl(config, aeron, null);
        Thread subThread = new Thread(() ->
            subscriber.replay(event -> {
                MarketEvent copy = new MarketEvent();
                copy.set(event.type(), event.timestampNanos(), event.symbol(),
                         event.orderId(), event.side(), event.quantity(), event.price());
                copy.ingressNanos(event.ingressNanos());
                synchronized (lock) { received.add(copy); }
                latch.countDown();
            }), "ipc-sub");
        subThread.setDaemon(true);
        subThread.start();

        // Publisher
        Publication pub = aeron.addPublication(config.aeronChannel(), config.aeronStreamId());
        // Wait for subscriber to connect
        while (pub.isConnected() == false) {
            Thread.sleep(1);
        }

        UnsafeBuffer sendBuf = new UnsafeBuffer(
                BufferUtil.allocateDirectAligned(MarketEventDecoder.MESSAGE_LENGTH, 64));
        MarketEvent src = new MarketEvent();

        for (int i = 0; i < numEvents; i++) {
            MessageType type = (i % 4 == 0) ? MessageType.NEW_ORDER
                             : (i % 4 == 1) ? MessageType.REPLACE
                             : (i % 4 == 2) ? MessageType.MODIFY
                             :                MessageType.CANCEL;
            Side side = (type == MessageType.CANCEL) ? null
                       : (i % 2 == 0) ? Side.BUY : Side.SELL;

            src.set(type, 1_000_000_000L + i, i % 10, (long) i, side, 100L * (i + 1), 50_000L * (i + 1));
            src.ingressNanos(System.nanoTime());

            MarketEventDecoder.encode(sendBuf, 0, src);
            long result;
            do {
                result = pub.offer(sendBuf, 0, MarketEventDecoder.MESSAGE_LENGTH);
            } while (result < 0);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "Subscriber timed out — received " + received.size() + "/" + numEvents);

        subscriber.stop();
        pub.close();
        subThread.join(2000);

        assertEquals(numEvents, received.size());

        // Spot-check every event's fields
        for (int i = 0; i < numEvents; i++) {
            MarketEvent ev = received.get(i);
            MessageType expectedType = (i % 4 == 0) ? MessageType.NEW_ORDER
                                     : (i % 4 == 1) ? MessageType.REPLACE
                                     : (i % 4 == 2) ? MessageType.MODIFY
                                     :                MessageType.CANCEL;
            assertEquals(expectedType, ev.type(), "event[" + i + "] type");
            assertEquals(1_000_000_000L + i, ev.timestampNanos(), "event[" + i + "] ts");
            assertEquals(i % 10, ev.symbol(), "event[" + i + "] symbol");
            assertEquals((long) i, ev.orderId(), "event[" + i + "] orderId");
            assertEquals(100L * (i + 1), ev.quantity(), "event[" + i + "] quantity");
            assertEquals(50_000L * (i + 1), ev.price(), "event[" + i + "] price");
            // ingressNanos is overwritten by subscriber with its own arrival time — verify it's positive
            assertTrue(ev.ingressNanos() > 0, "event[" + i + "] ingressNanos must be > 0");
        }
    }

    // ── Subscriber stop() halts the spin loop ─────────────────────────────────

    @Test
    void stop_haltsBusySpin() throws Exception {
        AeronFeedSourceImpl subscriber = new AeronFeedSourceImpl(config, aeron, null);

        Thread subThread = new Thread(() ->
            subscriber.replay(ev -> {}), "ipc-sub-stop-test");
        subThread.setDaemon(true);
        subThread.start();

        Thread.sleep(100); // let it spin
        subscriber.stop();
        subThread.join(2000);

        assertFalse(subThread.isAlive(), "subscriber thread should have exited after stop()");
    }
}

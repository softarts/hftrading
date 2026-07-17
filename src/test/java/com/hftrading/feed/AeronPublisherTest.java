package com.hftrading.feed;

import com.hftrading.config.HftConfig;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.BusySpinIdleStrategy;
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
 * Verifies that {@link AeronPublisher} sends N events over the IPC channel and
 * that the subscriber receives all of them with correct field values.
 *
 * Uses an embedded MediaDriver so no external Aeron agent is required.
 */
class AeronPublisherTest {

    @TempDir Path tempDir;

    private MediaDriver driver;
    private Aeron aeron;
    private HftConfig config;

    @BeforeEach
    void setUp() throws IOException {
        // Write a minimal config for the test
        Path props = tempDir.resolve("test.properties");
        Files.writeString(props,
                "pipeline.mode=aeron\n" +
                "aeron.channel=aeron:ipc\n" +
                "aeron.stream.id=9901\n" +
                "aeron.embed.driver=true\n" +
                "aeron.fragment.limit=64\n"
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

    // ── Send N events, receive all ────────────────────────────────────────────

    @Test
    void publisher_sendsAllEvents_subscriberReceivesAll() throws Exception {
        int eventCount = 100;
        CountDownLatch latch = new CountDownLatch(eventCount);
        List<MarketEvent> received = new ArrayList<>(eventCount);
        Object lock = new Object();

        Subscription sub = aeron.addSubscription(config.aeronChannel(), config.aeronStreamId());
        MarketEvent scratchpad = new MarketEvent();

        FragmentHandler handler = (buffer, offset, length, header) -> {
            MarketEventDecoder.decode(buffer, offset, scratchpad);
            MarketEvent copy = new MarketEvent();
            copy.set(scratchpad.type(), scratchpad.timestampNanos(), scratchpad.symbol(),
                     scratchpad.orderId(), scratchpad.side(), scratchpad.quantity(), scratchpad.price());
            copy.ingressNanos(scratchpad.ingressNanos());
            synchronized (lock) { received.add(copy); }
            latch.countDown();
        };

        // Subscriber thread
        Thread subThread = new Thread(() -> {
            BusySpinIdleStrategy idle = new BusySpinIdleStrategy();
            while (latch.getCount() > 0) {
                idle.idle(sub.poll(handler, 64));
            }
        }, "sub-thread");
        subThread.setDaemon(true);
        subThread.start();

        // Publisher
        try (AeronPublisher publisher = new AeronPublisher(config, aeron, null)) {
            for (int i = 0; i < eventCount; i++) {
                MarketEvent ev = new MarketEvent();
                ev.set(MessageType.NEW_ORDER, 1_000_000_000L + i, 1, i, Side.BUY, 100L + i, 200_000L + i);
                ev.ingressNanos(System.nanoTime());
                publisher.onEvent(ev);
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Subscriber did not receive all events within 5s");
        assertEquals(eventCount, received.size());

        // Spot-check a few events
        MarketEvent first = received.get(0);
        assertEquals(MessageType.NEW_ORDER, first.type());
        assertEquals(Side.BUY, first.side());
        assertEquals(1, first.symbol());
        assertEquals(0L, first.orderId());
        assertEquals(100L, first.quantity());
        assertEquals(200_000L, first.price());

        sub.close();
    }

    // ── Fields survive encode / decode ────────────────────────────────────────

    @Test
    void publisher_preservesAllFields() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MarketEvent received = new MarketEvent();
        MarketEvent scratchpad = new MarketEvent();

        Subscription sub = aeron.addSubscription(config.aeronChannel(), config.aeronStreamId());
        FragmentHandler handler = (buf, off, len, hdr) -> {
            MarketEventDecoder.decode(buf, off, scratchpad);
            received.set(scratchpad.type(), scratchpad.timestampNanos(), scratchpad.symbol(),
                         scratchpad.orderId(), scratchpad.side(), scratchpad.quantity(), scratchpad.price());
            received.ingressNanos(scratchpad.ingressNanos());
            latch.countDown();
        };

        Thread subThread = new Thread(() -> {
            BusySpinIdleStrategy idle = new BusySpinIdleStrategy();
            while (latch.getCount() > 0) idle.idle(sub.poll(handler, 16));
        }, "sub-thread");
        subThread.setDaemon(true);
        subThread.start();

        long tsNs     = 9_876_543_210L;
        long ingressNs = 1_111_111_111L;
        try (AeronPublisher publisher = new AeronPublisher(config, aeron, null)) {
            MarketEvent ev = new MarketEvent();
            ev.set(MessageType.REPLACE, tsNs, 42, 7777L, Side.SELL, 500L, 300_000L);
            ev.ingressNanos(ingressNs);
            publisher.onEvent(ev);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(MessageType.REPLACE, received.type());
        assertEquals(Side.SELL,           received.side());
        assertEquals(tsNs,                received.timestampNanos());
        assertEquals(42,                  received.symbol());
        assertEquals(7777L,               received.orderId());
        assertEquals(500L,                received.quantity());
        assertEquals(300_000L,            received.price());
        assertEquals(ingressNs,           received.ingressNanos());

        sub.close();
    }
}

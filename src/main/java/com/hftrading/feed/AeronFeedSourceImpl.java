package com.hftrading.feed;

import com.hftrading.config.HftConfig;
import com.hftrading.util.LatencyProbe;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import net.openhft.affinity.Affinity;
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aeron IPC subscriber. Implements {@link FeedSource} by spin-polling an
 * Aeron {@link Subscription} and decoding each fragment into a reusable
 * {@link MarketEvent} before forwarding it to the {@link FeedHandler}.
 *
 * <p><b>Pinned thread model:</b> {@link #replay(FeedHandler)} blocks the calling
 * thread indefinitely using a {@link BusySpinIdleStrategy}. Callers should run
 * this on a dedicated thread (and optionally pin it via OS affinity).
 *
 * <p><b>Stopping:</b> call {@link #stop()} from another thread to break the
 * spin loop cleanly.
 *
 * <h2>Probes</h2>
 * <ul>
 *   <li>{@code aeron.subscribe} — measures
 *       {@code System.nanoTime() - event.ingressNanos()} immediately on fragment
 *       arrival, where {@code ingressNanos} was stamped by the Java publisher
 *       before {@code offer()}. This gives Aeron IPC transport time and is only
 *       valid in Java-publisher mode (same JVM clock). C++ cross-process measurement
 *       requires a TSC-based shared clock — future work.</li>
 * </ul>
 */
public final class AeronFeedSourceImpl implements FeedSource {

    private final Subscription subscription;
    private final int fragmentLimit;
    private final int subscriberCpu;
    private final LatencyProbe aeronProbe;  // measures pure IPC transport latency
    private final MarketEvent reusableEvent = new MarketEvent();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int warmupEvents;
    private int eventCount = 0;

    /**
     * @param config     effective HFT config (channel, stream id, fragment limit, cpu pin)
     * @param aeron      connected Aeron instance (caller owns lifecycle)
     * @param aeronProbe {@code aeron} probe measuring IPC transport latency, or {@code null}
     */
    public AeronFeedSourceImpl(HftConfig config, Aeron aeron, LatencyProbe aeronProbe) {
        this.subscription  = aeron.addSubscription(config.aeronChannel(), config.aeronStreamId());
        this.fragmentLimit = config.aeronFragmentLimit();
        this.subscriberCpu = config.aeronSubscriberCpu();
        this.aeronProbe    = aeronProbe;
        this.warmupEvents  = config.benchmarkWarmupEvents();
    }

    // -------------------------------------------------------------------------
    // FeedSource
    // -------------------------------------------------------------------------

    /**
     * Spin-polls the Aeron subscription and forwards decoded events to
     * {@code handler} until {@link #stop()} is called or the subscription closes.
     */
    @Override
    public void replay(FeedHandler handler) {
        if (subscriberCpu >= 0) {
            Affinity.setAffinity(subscriberCpu);
            System.out.println("[aeron-sub] pinned to CPU " + subscriberCpu);
        }

        BusySpinIdleStrategy idle = new BusySpinIdleStrategy();

        FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
            long arrivalNs = System.nanoTime(); // stamp immediately on receipt

            MarketEventDecoder.decode(buffer, offset, reusableEvent);

            // aeron probe: arrivalNs - publishNanos = pure IPC transport latency.
            // publishNanos was written by AeronPublisher into OFFSET_INGRESS_NANOS
            // just before offer(), so this excludes CSV parse time and queue wait.
            eventCount++;
            if (eventCount > warmupEvents) {
                LatencyProbe.record(aeronProbe, arrivalNs - reusableEvent.ingressNanos());
            }

            // Reset ingressNanos to subscriber arrival so the downstream e2e probe
            // measures from subscriber receipt to book.apply() completion.
            reusableEvent.ingressNanos(arrivalNs);

            handler.onEvent(reusableEvent);
        };

        while (running.get() && !subscription.isClosed()) {
            int fragments = subscription.poll(fragmentHandler, fragmentLimit);
            idle.idle(fragments);
        }
    }

    /**
     * Signal the spin loop to exit cleanly. Thread-safe; can be called from any thread.
     */
    public void stop() {
        running.set(false);
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    @Override
    public void close() throws Exception {
        stop();
        subscription.close();
    }
}

package com.hftrading.feed;

import com.hftrading.config.HftConfig;
import com.hftrading.util.LatencyProbe;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
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
    private final LatencyProbe subscribeProbe;
    private final MarketEvent reusableEvent = new MarketEvent();
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * @param config         effective HFT config (channel, stream id, fragment limit)
     * @param aeron          connected Aeron instance (caller owns lifecycle)
     * @param subscribeProbe {@code aeron.subscribe} probe, or {@code null} to disable
     */
    public AeronFeedSourceImpl(HftConfig config, Aeron aeron, LatencyProbe subscribeProbe) {
        this.subscription    = aeron.addSubscription(config.aeronChannel(), config.aeronStreamId());
        this.fragmentLimit   = config.aeronFragmentLimit();
        this.subscribeProbe  = subscribeProbe;
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
        BusySpinIdleStrategy idle = new BusySpinIdleStrategy();

        FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {
            long arrivalNs = System.nanoTime(); // stamp immediately on receipt

            MarketEventDecoder.decode(buffer, offset, reusableEvent);

            // aeron.subscribe probe: Aeron IPC transport time (Java publisher only)
            LatencyProbe.record(subscribeProbe, arrivalNs - reusableEvent.ingressNanos());

            // Overwrite ingressNanos with subscriber arrival time so the downstream
            // FeedHandler's e2e probe measures from this point forward.
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

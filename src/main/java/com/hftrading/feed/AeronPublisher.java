package com.hftrading.feed;

import com.hftrading.config.HftConfig;
import com.hftrading.util.LatencyProbe;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Java-side Aeron IPC publisher. Implements {@link FeedHandler} so it can
 * sit in-line between a {@link CsvFeedSource} and the ring.
 *
 * <p>For each event received via {@link #onEvent(MarketEvent)}:
 * <ol>
 *   <li>Encodes the event into a pre-allocated 48-byte {@link UnsafeBuffer}.</li>
 *   <li>Offers the buffer on the {@link Publication}, busy-spinning on back-pressure.</li>
 *   <li>Records the offer duration in the {@code aeron.publish} probe (if enabled).</li>
 * </ol>
 *
 * <p><b>Note on ingressNanos:</b> the event's {@code ingressNanos} (set by
 * {@code CsvFeedSource} at parse time) is encoded into the wire format so the
 * subscriber can compute Aeron IPC transport latency.
 */
public final class AeronPublisher implements FeedHandler, AutoCloseable {

    private final Publication publication;
    private final UnsafeBuffer sendBuffer;

    /**
     * @param config effective HFT config (channel, stream id)
     * @param aeron  connected Aeron instance (caller owns lifecycle)
     */
    public AeronPublisher(HftConfig config, Aeron aeron) {
        // Allocate a cache-line-aligned direct buffer for the outbound message
        this.sendBuffer = new UnsafeBuffer(
                BufferUtil.allocateDirectAligned(MarketEventDecoder.MESSAGE_LENGTH, 64));
        this.publication = aeron.addPublication(config.aeronChannel(), config.aeronStreamId());
    }

    // -------------------------------------------------------------------------
    // FeedHandler
    // -------------------------------------------------------------------------

    @Override
    public void onEvent(MarketEvent event) {
        MarketEventDecoder.encode(sendBuffer, 0, event);

        // Busy-spin on back-pressure -- acceptable for a dedicated IPC thread
        long result;
        do {
            // Overwrite OFFSET_INGRESS_NANOS with the actual successful publish timestamp.
            // Putting this inside the loop ensures that any backpressure waiting time
            // is excluded from the transit latency measurement.
            sendBuffer.putLong(MarketEventDecoder.OFFSET_INGRESS_NANOS, System.nanoTime());
            result = publication.offer(sendBuffer, 0, MarketEventDecoder.MESSAGE_LENGTH);
            if (result < 0 && result != Publication.CLOSED) {
                Thread.onSpinWait();
            }
        } while (result < 0 && result != Publication.CLOSED);
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        publication.close();
    }
}

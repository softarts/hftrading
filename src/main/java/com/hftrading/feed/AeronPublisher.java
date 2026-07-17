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
    private final LatencyProbe publishProbe;

    /**
     * @param config       effective HFT config (channel, stream id)
     * @param aeron        connected Aeron instance (caller owns lifecycle)
     * @param publishProbe {@code aeron.publish} probe, or {@code null} to disable
     */
    public AeronPublisher(HftConfig config, Aeron aeron, LatencyProbe publishProbe) {
        this.publishProbe = publishProbe;
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

        long offerStart = (publishProbe != null) ? System.nanoTime() : 0L;

        // Busy-spin on back-pressure — acceptable in a dedicated HFT thread
        long result;
        do {
            result = publication.offer(sendBuffer, 0, MarketEventDecoder.MESSAGE_LENGTH);
        } while (result < 0 && result != Publication.CLOSED);

        LatencyProbe.record(publishProbe, System.nanoTime() - offerStart);
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        publication.close();
    }
}

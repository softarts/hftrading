package com.hftrading.feed;

/**
 * Reusable, zero-allocation market event carrier.
 *
 * <p>Two timestamp fields serve distinct purposes:
 * <ul>
 *   <li>{@link #timestampNanos()} — exchange event time, decoded from the wire
 *       format. Never overwritten after decode. Used by strategy logic.</li>
 *   <li>{@link #ingressNanos()} — when <em>this process</em> first saw the event.
 *       Set by {@code CsvFeedSource} (direct mode) or by
 *       {@code AeronFeedSourceImpl.onFragment()} (aeron mode) using
 *       {@link System#nanoTime()}. Used for e2e latency measurement.</li>
 * </ul>
 */
public final class MarketEvent {

    private MessageType type;
    private long timestampNanos;
    private long ingressNanos;
    private int symbol;
    private long orderId;
    private Side side;
    private long quantity;
    private long price;

    public MarketEvent() {}

    /**
     * Populate all fields. {@code ingressNanos} is reset to zero — the caller
     * is responsible for setting it via {@link #ingressNanos(long)} immediately
     * after this call.
     */
    public void set(MessageType type, long timestampNanos, int symbol, long orderId,
                    Side side, long quantity, long price) {
        this.type = type;
        this.timestampNanos = timestampNanos;
        this.ingressNanos = 0L;
        this.symbol = symbol;
        this.orderId = orderId;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
    }

    /** Reset all fields to zero/null for slab reuse. */
    public void clear() {
        type = null;
        timestampNanos = 0L;
        ingressNanos = 0L;
        symbol = 0;
        orderId = 0L;
        side = null;
        quantity = 0L;
        price = 0L;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public MessageType type()          { return type; }
    public long timestampNanos()       { return timestampNanos; }
    public long ingressNanos()         { return ingressNanos; }
    /** Set the pipeline ingress timestamp. Called by the feed source on arrival. */
    public void ingressNanos(long ns)  { this.ingressNanos = ns; }
    public int symbol()                { return symbol; }
    public long orderId()              { return orderId; }
    public Side side()                 { return side; }
    public long quantity()             { return quantity; }
    public long price()                { return price; }
}

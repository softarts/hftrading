package com.hftrading.feed;

public final class MarketEvent {
    private MessageType type;
    private long timestampNanos;
    private int symbol;
    private long orderId;
    private Side side;
    private long quantity;
    private long price;

    public MarketEvent() {}

    public void set(MessageType type, long timestampNanos, int symbol, long orderId, Side side, long quantity, long price) {
        this.type = type;
        this.timestampNanos = timestampNanos;
        this.symbol = symbol;
        this.orderId = orderId;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
    }

    public MessageType type() { return type; }
    public long timestampNanos() { return timestampNanos; }
    public int symbol() { return symbol; }
    public long orderId() { return orderId; }
    public Side side() { return side; }
    public long quantity() { return quantity; }
    public long price() { return price; }
}


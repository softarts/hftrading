package com.hftrading.book;

import com.hftrading.feed.Side;

public final class PriceLevel {
    long price;
    int side;
    int headIndex;
    int tailIndex;
    long quantity;
    int orderCount;
    // Linked list pointers kept for backward compatibility (not used in dense-array path)
    int prevIndex;
    int nextIndex;
    int state;
    int slabIndex; // index of this object in the levels[] slab array

    void clear() {
        price = 0L;
        side = 0;
        headIndex = 0;
        tailIndex = 0;
        quantity = 0L;
        orderCount = 0;
        prevIndex = 0;
        nextIndex = 0;
        state = 0;
        // slabIndex is intentionally NOT cleared -- it is a permanent identity
    }

    void set(long price, Side side) {
        this.price = price;
        this.side = side.ordinal();
        this.headIndex = 0;
        this.tailIndex = 0;
        this.quantity = 0L;
        this.orderCount = 0;
        this.prevIndex = 0;
        this.nextIndex = 0;
        this.state = 1;
        // slabIndex is set once at construction time, never changed
    }

    Side side() {
        return side == Side.BUY.ordinal() ? Side.BUY : Side.SELL;
    }
}

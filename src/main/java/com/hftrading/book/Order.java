package com.hftrading.book;

import com.hftrading.feed.Side;

public final class Order {
    long orderId;
    int side;
    long quantity;
    long price;
    int levelIndex;
    int prevIndex;
    int nextIndex;
    int state;

    void clear() {
        orderId = 0L;
        side = 0;
        quantity = 0L;
        price = 0L;
        levelIndex = 0;
        prevIndex = 0;
        nextIndex = 0;
        state = 0;
    }

    void set(long orderId, Side side, long quantity, long price) {
        this.orderId = orderId;
        this.side = side.ordinal();
        this.quantity = quantity;
        this.price = price;
        this.levelIndex = 0;
        this.prevIndex = 0;
        this.nextIndex = 0;
        this.state = 1;
    }

    Side side() {
        return side == Side.BUY.ordinal() ? Side.BUY : Side.SELL;
    }
}

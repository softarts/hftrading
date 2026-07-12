package com.hftrading.feed;

@FunctionalInterface
public interface FeedHandler {
    void onEvent(MarketEvent event);
}

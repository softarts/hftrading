package com.hftrading.feed;

public interface FeedSource extends AutoCloseable {
    void replay(FeedHandler handler) throws Exception;

    @Override
    default void close() throws Exception {
    }
}

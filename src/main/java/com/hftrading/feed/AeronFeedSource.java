package com.hftrading.feed;

/**
 * Placeholder boundary for future Aeron/Agrona ingestion.
 * The CSV feeder and future C++/DPDK feed should both normalize to MarketEvent
 * before reaching the book engine.
 */
public interface AeronFeedSource extends FeedSource {
}

package com.hftrading.feed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CsvFeedSourceTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempFile("csv-feed-source-test", ".csv");
        try {
            Files.writeString(temp,
                    "X,1000000003,1,2,B,264,11562\n" +
                    "M,1000000004,1,3,B,597,10189\n");
            List<MarketEvent> events = new ArrayList<>();
            try (CsvFeedSource source = new CsvFeedSource(temp)) {
                source.replay(event -> {
                    MarketEvent copy = new MarketEvent();
                    copy.set(event.type(), event.timestampNanos(), event.symbol(), event.orderId(), event.side(), event.quantity(), event.price());
                    events.add(copy);
                });
            }

            if (events.size() != 2) {
                throw new AssertionError("Expected 2 events, got " + events.size());
            }
            assertCancel(events.get(0));
            assertModify(events.get(1));
            System.out.println("CsvFeedSourceTest PASSED");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void assertCancel(MarketEvent event) {
        if (event.type() != MessageType.CANCEL) {
            throw new AssertionError("Expected CANCEL, got " + event.type());
        }
        if (event.timestampNanos() != 1000000003L || event.symbol() != 1 || event.orderId() != 2L || event.quantity() != 264L || event.price() != 11562L) {
            throw new AssertionError("Unexpected CANCEL event: " + event);
        }
    }

    private static void assertModify(MarketEvent event) {
        if (event.type() != MessageType.MODIFY) {
            throw new AssertionError("Expected MODIFY, got " + event.type());
        }
        if (event.timestampNanos() != 1000000004L || event.symbol() != 1 || event.orderId() != 3L || event.side() != Side.BUY || event.quantity() != 597L || event.price() != 10189L) {
            throw new AssertionError("Unexpected MODIFY event: " + event);
        }
    }
}

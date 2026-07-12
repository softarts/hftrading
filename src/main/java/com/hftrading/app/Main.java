package com.hftrading.app;

import com.hftrading.book.OrderBook;
import com.hftrading.feed.CsvFeedSource;
import com.hftrading.feed.FeedHandler;
import com.hftrading.feed.MarketEvent;
import com.hftrading.util.LatencyMeasurement;
import com.hftrading.util.TestDataTool;

import java.nio.file.Path;
import java.util.Locale;

public final class Main {
    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config.generatePath != null) {
            TestDataTool.generate(config.generatePath, config.totalOrders, config.peakOrders, config.seed, config.products);
            return;
        }

        if (config.input == null) {
            System.err.println("Usage: java com.hftrading.app.Main --input <csv> [--measure on|off] [--orders n] [--peak n] [--products n]");
            System.exit(1);
        }

        LatencyMeasurement bookLatency = config.measureLatency ? new LatencyMeasurement(config.totalOrders) : null;
        LatencyMeasurement csvLatency = config.measureLatency ? new LatencyMeasurement(config.totalOrders) : null;
        LatencyMeasurement e2eLatency = config.measureLatency ? new LatencyMeasurement(config.totalOrders) : null;

        OrderBook[] books = new OrderBook[AppConfig.MAX_PRODUCTS];

        FeedHandler handler = event -> {
            long start = config.measureLatency ? System.nanoTime() : 0L;
            int symbol = event.symbol();
            OrderBook book = books[symbol];
            if (book == null) {
                book = new OrderBook(Math.max(AppConfig.MAX_ORDERS, config.peakOrders), Math.max(AppConfig.MAX_PRICE_LEVELS, config.peakOrders));
                books[symbol] = book;
            }
            
            book.apply(event);
            
            if (config.measureLatency) {
                bookLatency.record(System.nanoTime() - start);
            }
        };

        long t0 = System.nanoTime();
        try (CsvFeedSource source = new CsvFeedSource(config.input)) {
            if (config.measureLatency) {
                source.setCsvLatency(csvLatency);
                source.setE2eLatency(e2eLatency);
            }
            source.replay(handler);
        }
        long elapsed = System.nanoTime() - t0;

        for (int i = 0; i < books.length; i++) {
            if (books[i] != null) {
                System.out.println("Product: " + i);
                System.out.println(books[i].snapshot());
                System.out.println(books[i].topOfBook(5));
            }
        }
        System.out.println("elapsedNs=" + elapsed);
        if (config.measureLatency) {
            System.out.println(csvLatency.summary("CsvFeeder"));
            System.out.println(bookLatency.summary("OrderBook"));
            System.out.println(e2eLatency.summary("EndToEnd"));
        }
    }

    private static final class Config {
        Path input;
        Path generatePath;
        boolean measureLatency = false;
        int totalOrders = 1_000_000;
        int peakOrders = 1_000_000;
        int products = 1;
        long seed = 1L;

        static Config parse(String[] args) {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--input" -> c.input = Path.of(args[++i]);
                    case "--generate" -> c.generatePath = Path.of(args[++i]);
                    case "--orders" -> c.totalOrders = Integer.parseInt(args[++i]);
                    case "--peak" -> c.peakOrders = Integer.parseInt(args[++i]);
                    case "--products" -> c.products = Integer.parseInt(args[++i]);
                    case "--seed" -> c.seed = Long.parseLong(args[++i]);
                    case "--measure" -> c.measureLatency = parseOnOff(args[++i]);
                    default -> {
                        if (c.input == null && !a.startsWith("--")) {
                            c.input = Path.of(a);
                        } else {
                            throw new IllegalArgumentException("Unknown arg: " + a);
                        }
                    }
                }
            }
            return c;
        }

        private static boolean parseOnOff(String value) {
            String v = value.toLowerCase(Locale.ROOT);
            return v.equals("on") || v.equals("true") || v.equals("1");
        }
    }
}


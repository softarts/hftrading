package com.hftrading.util;

import com.hftrading.feed.Side;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class TestDataTool {
    private TestDataTool() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        generate(config.output, config.totalOrders, config.peakOrders, config.seed, config.products);
    }

    public static void generate(Path output, int totalOrders, int peakOrders, long seed, int products) throws Exception {
        Random random = new Random(seed);
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            long ts = 1_000_000_000L;
            long nextOrderId = 1L;
            List<Long> liveOrders = new ArrayList<>(Math.min(peakOrders, 1_000_000));
            int[] orderToProduct = new int[totalOrders + 1];
            int live = 0;
            
            for (int i = 0; i < totalOrders; i++) {
                boolean add = live < peakOrders && (live == 0 || random.nextInt(100) < 65);
                if (add) {
                    long orderId = nextOrderId++;
                    int productId = 1 + random.nextInt(products);
                    orderToProduct[(int) orderId] = productId;
                    
                    Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                    long qty = 1 + random.nextInt(1000);
                    long price = 10_000 + random.nextInt(100); // 100-tick spread fits Phase 1 dense array
                    writer.write("N," + ts++ + "," + productId + "," + orderId + "," + side.code() + "," + qty + "," + price);
                    writer.newLine();
                    liveOrders.add(orderId);
                    live++;
                } else if (!liveOrders.isEmpty()) {
                    long orderId = liveOrders.remove(random.nextInt(liveOrders.size()));
                    int productId = orderToProduct[(int) orderId];
                    long qty = 1 + random.nextInt(1000);
                    long price = 10_000 + random.nextInt(100); // 100-tick spread
                    if (random.nextBoolean()) {
                        writer.write("X," + ts++ + "," + productId + "," + orderId + ",B," + qty + "," + price);
                        live--;
                    } else {
                        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                        writer.write("M," + ts++ + "," + productId + "," + orderId + "," + side.code() + "," + qty + "," + price);
                    }
                    writer.newLine();
                }
            }
        }
    }

    private static final class Config {
        Path output;
        int totalOrders = 1_000_000;
        int peakOrders = 1_000_000;
        int products = 1;
        long seed = 1L;

        static Config parse(String[] args) {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--output" -> c.output = Path.of(args[++i]);
                    case "--orders" -> c.totalOrders = Integer.parseInt(args[++i]);
                    case "--peak" -> c.peakOrders = Integer.parseInt(args[++i]);
                    case "--products" -> c.products = Integer.parseInt(args[++i]);
                    case "--seed" -> c.seed = Long.parseLong(args[++i]);
                    default -> {
                        if (c.output == null && !a.startsWith("--")) {
                            c.output = Path.of(a);
                        } else {
                            throw new IllegalArgumentException("Unknown arg: " + a);
                        }
                    }
                }
            }
            if (c.output == null) {
                c.output = Path.of("out", "bench_" + c.totalOrders + "_peak_" + c.peakOrders + ".csv");
            }
            return c;
        }
    }
}

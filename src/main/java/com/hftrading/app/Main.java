package com.hftrading.app;

import com.hftrading.book.OrderBook;
import com.hftrading.config.HftConfig;
import com.hftrading.feed.*;
import com.hftrading.util.LatencyProbe;
import com.hftrading.util.TestDataTool;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point for the HFT trading system.
 *
 * <h2>Modes</h2>
 * <pre>
 *   --generate &lt;path&gt;   generate synthetic test data and exit
 *   (default)            load config/default.properties, run pipeline
 * </pre>
 *
 * <h2>Pipeline modes (pipeline.mode in config)</h2>
 * <pre>
 *   direct  CsvFeedSource ──────────────────────────────► OrderBook
 *   aeron   CsvFeedSource ──► AeronPublisher ──► [ipc] ──► AeronSubscriber ──► OrderBook
 * </pre>
 *
 * <h2>Warm-up</h2>
 * The first {@code benchmark.warmup.events} events run through the full pipeline
 * but probes do not record. A log line marks the transition.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // ── Generate mode (backward-compatible) ───────────────────────────────
        if (args.length >= 2 && "--generate".equals(args[0])) {
            Path out       = Path.of(args[1]);
            int  orders    = argInt(args, "--orders",   1_000_000);
            int  peak      = argInt(args, "--peak",     1_000_000);
            int  products  = argInt(args, "--products", 1);
            long seed      = argLong(args, "--seed",    1L);
            TestDataTool.generate(out, orders, peak, seed, products);
            return;
        }

        // ── Config ────────────────────────────────────────────────────────────
        String configPath = argStr(args, "--config", "config/default.properties");
        HftConfig config = HftConfig.load(Path.of(configPath));

        // --input flag overrides config file path (backward compat)
        String inputOverride = argStr(args, "--input", null);

        // ── Probes ────────────────────────────────────────────────────────────
        int sigDigits = config.latencyHistogramSignificantDigits();

        LatencyProbe csvParseProbe      = config.latencyCsvParse()        ? LatencyProbe.create("csv.parse",       sigDigits) : null;
        LatencyProbe aeronProbe         = config.latencyAeron()           ? LatencyProbe.create("aeron",           sigDigits) : null;
        LatencyProbe bookApplyProbe     = config.latencyBookApply()       ? LatencyProbe.create("book.apply",      sigDigits) : null;
        LatencyProbe e2eProbe           = config.latencyE2e()             ? LatencyProbe.create("e2e",             sigDigits) : null;

        // Ordered map for consistent summary output
        Map<String, LatencyProbe> probes = new LinkedHashMap<>();
        if (csvParseProbe      != null) probes.put("csv.parse",       csvParseProbe);
        if (aeronProbe         != null) probes.put("aeron",           aeronProbe);
        if (bookApplyProbe     != null) probes.put("book.apply",      bookApplyProbe);
        if (e2eProbe           != null) probes.put("e2e",             e2eProbe);

        // ── Order books ───────────────────────────────────────────────────────
        int maxProducts = config.bookMaxProducts();
        OrderBook[] books = new OrderBook[maxProducts];

        // ── Warm-up state ─────────────────────────────────────────────────────
        int warmupEvents = config.benchmarkWarmupEvents();
        int[] eventCount = {0};

        // ── FeedHandler: book.apply + e2e probes ──────────────────────────────
        FeedHandler bookHandler = event -> {
            eventCount[0]++;
            boolean record = (eventCount[0] > warmupEvents);

            int symbol = event.symbol();
            if (symbol < 0 || symbol >= maxProducts) return;

            OrderBook book = books[symbol];
            if (book == null) {
                book = new OrderBook(config.bookMaxOrders(), config.bookMaxPriceLevels());
                books[symbol] = book;
            }

            long bookStart = (bookApplyProbe != null && record) ? System.nanoTime() : 0L;
            book.apply(event);
            if (bookApplyProbe != null && record) {
                bookApplyProbe.record(System.nanoTime() - bookStart);
            }

            if (e2eProbe != null && record && event.ingressNanos() > 0) {
                e2eProbe.record(System.nanoTime() - event.ingressNanos());
            }
        };

        // ── Run pipeline ──────────────────────────────────────────────────────
        long t0 = System.nanoTime();

        if ("aeron".equals(config.pipelineMode())) {
            int publisherCpu = config.aeronPublisherCpu();
            if (publisherCpu >= 0) {
                net.openhft.affinity.Affinity.setAffinity(publisherCpu);
                System.out.println("[aeron-pub] pinned publisher thread to CPU " + publisherCpu);
            }
            runAeronMode(config, bookHandler, csvParseProbe, aeronProbe,
                         warmupEvents, inputOverride);
        } else {
            runDirectMode(config, bookHandler, csvParseProbe, warmupEvents, inputOverride);
        }

        long elapsedNs = System.nanoTime() - t0;

        // ── Print book snapshots ───────────────────────────────────────────────
        int depth = config.outputSnapshotDepth();
        for (int i = 0; i < books.length; i++) {
            if (books[i] != null) {
                System.out.println("Product: " + i);
                System.out.println(books[i].snapshot());
                if (config.outputPrintTopOfBook()) {
                    System.out.println(books[i].topOfBook(depth));
                }
            }
        }

        System.out.printf("elapsedNs=%d (%.3f ms)%n", elapsedNs, elapsedNs / 1_000_000.0);

        // ── Latency report ─────────────────────────────────────────────────────
        if (!probes.isEmpty()) {
            LatencyProbe.printSummary(probes, config.latencyOutputFile());
        }
    }

    // -------------------------------------------------------------------------
    // Direct mode
    // -------------------------------------------------------------------------

    private static void runDirectMode(HftConfig config, FeedHandler handler,
                                      LatencyProbe csvParseProbe, int warmupEvents,
                                      String inputOverride) throws Exception {
        Path csvPath = inputOverride != null ? Path.of(inputOverride) : config.inputCsvPath();
        if (csvPath == null) {
            System.err.println("[direct] input.csv.path not configured");
            System.exit(1);
        }

        System.out.println("[direct] replaying: " + csvPath);
        try (CsvFeedSource source = new CsvFeedSource(csvPath, config.inputCsvBufferBytes())) {
            source.setCsvParseProbe(csvParseProbe);
            source.setWarmupEvents(warmupEvents);
            source.replay(handler);
        }
    }

    // -------------------------------------------------------------------------
    // Aeron mode
    // -------------------------------------------------------------------------

    private static void runAeronMode(HftConfig config, FeedHandler bookHandler,
                                     LatencyProbe csvParseProbe,
                                     LatencyProbe aeronProbe,
                                     int warmupEvents,
                                     String inputOverride) throws Exception {
        Path csvPath = inputOverride != null ? Path.of(inputOverride) : config.inputCsvPath();
        if (csvPath == null) {
            System.err.println("[aeron] input.csv.path not configured");
            System.exit(1);
        }

        System.out.println("[aeron] channel=" + config.aeronChannel()
                + " stream=" + config.aeronStreamId());

        // Embedded MediaDriver — single JVM process hosts both driver and client
        MediaDriver.Context driverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(io.aeron.driver.ThreadingMode.SHARED);

        try (MediaDriver driver = config.aeronEmbedDriver()
                ? MediaDriver.launchEmbedded(driverCtx)
                : null) {

            Aeron.Context aeronCtx = new Aeron.Context();
            if (driver != null) {
                aeronCtx.aeronDirectoryName(driver.aeronDirectoryName());
            }

            try (Aeron aeron = Aeron.connect(aeronCtx)) {

                // Subscriber runs on a dedicated thread, spin-polling the ring
                AeronFeedSourceImpl subscriber =
                        new AeronFeedSourceImpl(config, aeron, aeronProbe);

                Thread subThread = new Thread(() -> {
                    try {
                        subscriber.replay(bookHandler);
                    } catch (Exception e) {
                        System.err.println("[aeron-sub] error: " + e.getMessage());
                    }
                }, "aeron-subscriber");
                subThread.setDaemon(true);
                subThread.start();

                // Publisher: CSV → Aeron ring (main thread)
                try (AeronPublisher publisher = new AeronPublisher(config, aeron);
                     CsvFeedSource source = new CsvFeedSource(csvPath, config.inputCsvBufferBytes())) {

                    source.setCsvParseProbe(csvParseProbe);
                    source.setWarmupEvents(warmupEvents);
                    source.replay(publisher);
                }

                System.out.println("[aeron] all events sent — draining subscriber...");
                // Give the subscriber a moment to drain remaining messages
                Thread.sleep(500);
                subscriber.stop();
                subThread.join(2000);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Arg helpers
    // -------------------------------------------------------------------------

    private static String argStr(String[] args, String flag, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return def;
    }

    private static int argInt(String[] args, String flag, int def) {
        String v = argStr(args, flag, null);
        return v != null ? Integer.parseInt(v) : def;
    }

    private static long argLong(String[] args, String flag, long def) {
        String v = argStr(args, flag, null);
        return v != null ? Long.parseLong(v) : def;
    }
}

package com.hftrading.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Typed configuration loader. Reads a {@code .properties} file, provides typed
 * getters with documented defaults, validates required keys at startup, and
 * prints the effective config on launch.
 *
 * <p>Replaces {@code AppConfig.java} (hard-coded constants).</p>
 */
public final class HftConfig {

    private final Properties props;

    private HftConfig(Properties props) {
        this.props = props;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static HftConfig load(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        HftConfig config = new HftConfig(props);
        config.validate();
        config.printEffective();
        return config;
    }

    public static HftConfig loadDefault() throws IOException {
        return load(Path.of("config/default.properties"));
    }

    // -------------------------------------------------------------------------
    // Validation & startup print
    // -------------------------------------------------------------------------

    private void validate() {
        require("pipeline.mode");
        String mode = pipelineMode();
        if (!mode.equals("direct") && !mode.equals("aeron")) {
            throw new IllegalArgumentException(
                    "pipeline.mode must be 'direct' or 'aeron', got: " + mode);
        }
    }

    private void require(String key) {
        if (props.getProperty(key) == null) {
            throw new IllegalArgumentException("Required config key missing: " + key);
        }
    }

    private void printEffective() {
        System.out.println("=== Effective Configuration ===");
        props.stringPropertyNames().stream().sorted()
             .forEach(k -> System.out.println("  " + k + " = " + props.getProperty(k)));
        System.out.println("================================");
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    /** {@code "direct"} or {@code "aeron"}. */
    public String pipelineMode() {
        return props.getProperty("pipeline.mode", "direct");
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    public Path inputCsvPath() {
        String p = props.getProperty("input.csv.path");
        return p != null ? Path.of(p) : null;
    }

    public int inputCsvBufferBytes() {
        return getInt("input.csv.buffer.bytes", 262_144);
    }

    // -------------------------------------------------------------------------
    // Aeron
    // -------------------------------------------------------------------------

    public String aeronChannel() {
        return props.getProperty("aeron.channel", "aeron:ipc");
    }

    public int aeronStreamId() {
        return getInt("aeron.stream.id", 1001);
    }

    /** {@code true} = embed MediaDriver in the JVM process. */
    public boolean aeronEmbedDriver() {
        return getBool("aeron.embed.driver", true);
    }

    /** CPU core to pin the subscriber thread, or {@code -1} for no pinning. */
    public int aeronSubscriberCpu() {
        return getInt("aeron.subscriber.cpu", -1);
    }

    /** CPU core to pin the publisher thread, or {@code -1} for no pinning. */
    public int aeronPublisherCpu() {
        return getInt("aeron.publisher.cpu", -1);
    }

    public int aeronFragmentLimit() {
        return getInt("aeron.fragment.limit", 256);
    }

    // -------------------------------------------------------------------------
    // Order book
    // -------------------------------------------------------------------------

    public int bookMaxOrders() {
        return getInt("book.max.orders", 1_000_000);
    }

    public int bookMaxPriceLevels() {
        return getInt("book.max.price.levels", 8192);
    }

    public int bookMaxProducts() {
        return getInt("book.max.products", 1024);
    }

    public int bookTickWindow() {
        return getInt("book.tick.window", 500);
    }

    // -------------------------------------------------------------------------
    // Latency probes
    // -------------------------------------------------------------------------

    public boolean latencyCsvParse() {
        return getBool("latency.csv.parse", true);
    }

    public boolean latencyAeronPublish() {
        return getBool("latency.aeron.publish", true);
    }

    public boolean latencyAeronSubscribe() {
        return getBool("latency.aeron.subscribe", true);
    }

    public boolean latencyBookApply() {
        return getBool("latency.book.apply", true);
    }

    public boolean latencyE2e() {
        return getBool("latency.e2e", true);
    }

    // -------------------------------------------------------------------------
    // Benchmark
    // -------------------------------------------------------------------------

    public int benchmarkWarmupEvents() {
        return getInt("benchmark.warmup.events", 100_000);
    }

    public int latencyHistogramSignificantDigits() {
        return getInt("latency.histogram.significant.digits", 3);
    }

    public String latencyOutputFile() {
        return props.getProperty("latency.output.file", "output/latency_report.csv");
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    public int outputSnapshotDepth() {
        return getInt("output.snapshot.depth", 5);
    }

    public boolean outputPrintTopOfBook() {
        return getBool("output.print.top.of.book", true);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private int getInt(String key, int defaultValue) {
        String v = props.getProperty(key);
        return v != null ? Integer.parseInt(v.trim()) : defaultValue;
    }

    private boolean getBool(String key, boolean defaultValue) {
        String v = props.getProperty(key);
        if (v == null) return defaultValue;
        v = v.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes");
    }
}

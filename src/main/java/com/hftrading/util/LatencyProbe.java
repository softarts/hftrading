package com.hftrading.util;

import org.HdrHistogram.Histogram;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Thin wrapper around {@link Histogram} for per-stage latency recording.
 *
 * <p><b>Hot-path contract:</b> {@link #record(long)} is allocation-free.
 * {@link #record(LatencyProbe, long)} is a null-safe static helper that
 * collapses to a single null check when the probe is disabled — zero overhead
 * on the critical path.
 *
 * <p>Replaces {@code LatencyMeasurement} (sort-based, allocating).</p>
 *
 * <p>Provides p50 / p90 / p99 / p99.9 / p99.99 / max via HdrHistogram,
 * which handles coordinated omission correctly.</p>
 */
public final class LatencyProbe {

    /** 10 seconds expressed in nanoseconds — upper bound for any single sample. */
    private static final long MAX_VALUE_NS = 10_000_000_000L;

    private final String name;
    private final Histogram histogram;

    private LatencyProbe(String name, int significantDigits) {
        this.name = name;
        this.histogram = new Histogram(MAX_VALUE_NS, significantDigits);
    }

    /**
     * Create a probe with 3 significant digits (±0.1% accuracy).
     */
    public static LatencyProbe create(String name) {
        return new LatencyProbe(name, 3);
    }

    /**
     * Create a probe with configurable significant digits.
     */
    public static LatencyProbe create(String name, int significantDigits) {
        return new LatencyProbe(name, significantDigits);
    }

    // -------------------------------------------------------------------------
    // Hot-path recording
    // -------------------------------------------------------------------------

    /**
     * Record a latency sample. Allocation-free on the hot path.
     * Silently drops negative values (clock anomalies).
     */
    public void record(long latencyNs) {
        if (latencyNs >= 0 && latencyNs <= MAX_VALUE_NS) {
            histogram.recordValue(latencyNs);
        }
    }

    /**
     * Null-safe static helper: collapses to a single null check when the probe
     * is disabled — zero overhead on the critical path when {@code probe} is null.
     */
    public static void record(LatencyProbe probe, long latencyNs) {
        if (probe != null) {
            probe.record(latencyNs);
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String name() { return name; }

    public Histogram histogram() { return histogram; }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    public String summary() {
        if (histogram.getTotalCount() == 0) {
            return String.format("%-22s  (no samples)", name);
        }
        return String.format(
                "%-22s  samples=%,d  p50=%,dns  p90=%,dns  p99=%,dns  p99.9=%,dns  p99.99=%,dns  max=%,dns",
                name,
                histogram.getTotalCount(),
                histogram.getValueAtPercentile(50.0),
                histogram.getValueAtPercentile(90.0),
                histogram.getValueAtPercentile(99.0),
                histogram.getValueAtPercentile(99.9),
                histogram.getValueAtPercentile(99.99),
                histogram.getMaxValue());
    }

    /**
     * Print all enabled probes side-by-side and optionally dump a CSV file.
     *
     * @param probes        ordered map of probe name → probe (only non-null values)
     * @param csvOutputPath path to write the CSV report, or {@code null} to skip
     */
    public static void printSummary(Map<String, LatencyProbe> probes, String csvOutputPath) {
        System.out.println("\n=== Latency Report ===");
        for (LatencyProbe p : probes.values()) {
            System.out.println(p.summary());
        }
        System.out.println("======================");
        if (csvOutputPath != null && !csvOutputPath.isBlank()) {
            dumpCsv(probes, csvOutputPath);
        }
    }

    // -------------------------------------------------------------------------
    // CSV dump
    // -------------------------------------------------------------------------

    private static void dumpCsv(Map<String, LatencyProbe> probes, String path) {
        File f = new File(path);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
            w.println("probe,samples,p50_ns,p90_ns,p99_ns,p99.9_ns,p99.99_ns,max_ns");
            for (LatencyProbe p : probes.values()) {
                Histogram h = p.histogram();
                w.printf("%s,%d,%d,%d,%d,%d,%d,%d%n",
                        p.name(),
                        h.getTotalCount(),
                        h.getValueAtPercentile(50.0),
                        h.getValueAtPercentile(90.0),
                        h.getValueAtPercentile(99.0),
                        h.getValueAtPercentile(99.9),
                        h.getValueAtPercentile(99.99),
                        h.getMaxValue());
            }
            System.out.println("Latency CSV written to: " + path);
        } catch (IOException e) {
            System.err.println("Failed to write latency CSV: " + e.getMessage());
        }
    }
}

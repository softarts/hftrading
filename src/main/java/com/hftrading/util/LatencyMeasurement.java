package com.hftrading.util;

import java.util.Arrays;

public final class LatencyMeasurement {
    private long[] samples;
    private int size;
    private long total;

    public LatencyMeasurement(int expected) {
        this.samples = new long[Math.max(1, expected)];
    }

    public void record(long nanos) {
        if (size == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[size++] = nanos;
        total += nanos;
    }

    public String summary(String name) {
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        long avg = size == 0 ? 0 : total / size;
        long p50 = percentile(sorted, 0.50);
        long p90 = percentile(sorted, 0.90);
        long p99 = percentile(sorted, 0.99);
        long p999 = percentile(sorted, 0.999);
        return name + "LatencyNs{avg=" + avg + ", p50=" + p50 + ", p90=" + p90
                + ", p99=" + p99 + ", p99.9=" + p999 + ", samples=" + size + "}";
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
        return sorted[index];
    }
}

package com.hftrading.util;

/**
 * @deprecated Replaced by {@link LatencyProbe} + HdrHistogram.
 *
 * <p>{@code LatencyMeasurement} used a sort-based approach that allocated a
 * growing array on the hot path.  {@link LatencyProbe} wraps
 * {@code org.HdrHistogram.Histogram} which is allocation-free on the hot path
 * and handles coordinated omission correctly.</p>
 *
 * <p>This file is retained only so that external tooling does not fail to
 * compile. It will be removed in a subsequent cleanup.</p>
 */
@Deprecated(forRemoval = true)
public final class LatencyMeasurement {

    private LatencyMeasurement() {}

    /** @deprecated use {@link LatencyProbe#record(long)} */
    @Deprecated
    public void record(long nanos) {
        throw new UnsupportedOperationException(
                "LatencyMeasurement is deprecated — use LatencyProbe instead");
    }

    /** @deprecated use {@link LatencyProbe#summary()} */
    @Deprecated
    public String summary(String name) {
        throw new UnsupportedOperationException(
                "LatencyMeasurement is deprecated — use LatencyProbe instead");
    }
}

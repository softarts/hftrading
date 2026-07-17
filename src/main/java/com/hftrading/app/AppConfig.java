package com.hftrading.app;

/**
 * @deprecated Replaced by {@link com.hftrading.config.HftConfig}.
 *
 * <p>Hard-coded constants have been moved to {@code config/default.properties}
 * and are read at startup via {@code HftConfig.loadDefault()}.</p>
 *
 * <p>This file is retained only so that any external tooling referencing the
 * class does not fail to compile. It will be removed in a subsequent cleanup.</p>
 */
@Deprecated(forRemoval = true)
public final class AppConfig {

    /** @deprecated use {@code HftConfig.bookMaxProducts()} */
    @Deprecated public static final int MAX_PRODUCTS    = 1024;

    /** @deprecated use {@code HftConfig.bookMaxOrders()} */
    @Deprecated public static final int MAX_ORDERS      = 1_000_000;

    /** @deprecated use {@code HftConfig.bookMaxPriceLevels()} */
    @Deprecated public static final int MAX_PRICE_LEVELS = 8192;

    private AppConfig() {}
}

package com.hftrading.book;

import com.hftrading.feed.MessageType;
import com.hftrading.feed.MarketEvent;
import com.hftrading.feed.Side;

/**
 * Order book using a dense pre-allocated price level array for O(1) access.
 *
 * <h2>Price Level Management - Phase 1: Dense Array</h2>
 *
 * Price levels are stored in a fixed-size flat array indexed by tick offset from
 * a base price.  Given MAX_TICKS = 500, the array covers [basePrice, basePrice + 500).
 * Bids occupy indices [0, 500), asks occupy the same array, differentiated by
 * the side stored in PriceLevel.  Finding a level is a single array read:
 * <pre>
 *   int idx = (int)(price - basePrice);
 * </pre>
 * No linked list, no hash map, no pointer chasing -- guaranteed O(1) and cache hot.
 *
 * <h2>Price Level Management - Phase 2 (future): Drifting Ring Buffer</h2>
 *
 * Replace the fixed base with a ring buffer:
 * <pre>
 *   int idx = (int)(price % RING_SIZE);  // ring wrap
 *   long basePrice = price - idx;         // virtual base
 * </pre>
 * When the inside spread drifts, the base reference index simply advances.
 * No data movement is required.  Prices outside the hot ring fall back to
 * a sparse open-addressed hash map identical to the current orderIdToSlot map.
 */
public final class OrderBook {
    // Dense array covering a 500-tick band around the first seen price
    static final int MAX_TICKS = 500;

    private static final int EMPTY = 0;

    private final int maxOrders;
    private final Order[] orders;
    private final PriceLevel[] levels; // slab for order queues

    // Dense tick array -- index = (price - basePrice)
    // slot 0 is reserved (EMPTY sentinel), real indices start at 1
    private final PriceLevel[] tickLevels;
    private long basePrice = Long.MIN_VALUE; // set on first add
    private int bestBidTick = -1; // highest active bid tick index in tickLevels
    private int bestAskTick = Integer.MAX_VALUE; // lowest active ask tick index

    // Free-list stacks for level slab recycling
    private final int[] freeLevelStack;
    private int freeLevelTop = -1;
    private int nextLevelIndex = 1;

    // Order hash map (orderId -> slab index)
    private final int[] orderIdToSlot;
    private final long[] orderIdKeys;

    // Free-list stacks for order slab recycling
    private final int[] freeOrderStack;
    private int freeOrderTop = -1;
    private int nextOrderIndex = 1;

    private int activeOrders;
    private int activeLevels;

    public OrderBook(int maxOrders, int maxPriceLevels) {
        this.maxOrders = maxOrders;
        this.orders = new Order[maxOrders + 1];
        for (int i = 0; i < orders.length; i++) {
            orders[i] = new Order();
        }
        this.levels = new PriceLevel[maxPriceLevels + 1];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = new PriceLevel();
            levels[i].slabIndex = i; // permanent identity
        }
        // Dense tick array: indices 0..MAX_TICKS-1, each is a PriceLevel slab slot reference
        // We store PriceLevel references directly for zero-indirection access
        this.tickLevels = new PriceLevel[MAX_TICKS];
        this.orderIdToSlot = new int[nextPow2(maxOrders * 2)];
        this.orderIdKeys = new long[orderIdToSlot.length];
        this.freeOrderStack = new int[maxOrders];
        this.freeLevelStack = new int[maxPriceLevels];
    }

    public void apply(MarketEvent event) {
        MessageType type = event.type();
        if (type == MessageType.NEW_ORDER) {
            add(event.orderId(), event.side(), event.quantity(), event.price());
        } else if (type == MessageType.REPLACE) {
            replace(event.orderId(), event.side(), event.quantity(), event.price());
        } else if (type == MessageType.CANCEL) {
            cancel(event.orderId());
        } else if (type == MessageType.MODIFY) {
            modify(event.orderId(), event.side(), event.quantity(), event.price());
        }
    }

    public void add(long orderId, Side side, long quantity, long price) {
        if (orderId <= 0 || side == null || quantity <= 0) {
            return;
        }
        if (lookupOrder(orderId) != EMPTY) {
            cancel(orderId);
        }

        // Establish base price on first order
        if (basePrice == Long.MIN_VALUE) {
            basePrice = price - (MAX_TICKS / 2); // centre the band
        }

        int tick = (int) (price - basePrice);
        if (tick < 0 || tick >= MAX_TICKS) {
            // Out-of-band price -- ignored in Phase 1
            return;
        }

        PriceLevel level = tickLevels[tick];
        if (level == null) {
            level = allocateLevel(side, price);
            tickLevels[tick] = level;
        }

        int orderIndex = allocateOrder(orderId, side, quantity, price);
        appendOrder(level, orderIndex);
        activeOrders++;

        // Update best bid/ask
        if (side == Side.BUY) {
            if (tick > bestBidTick) bestBidTick = tick;
        } else {
            if (tick < bestAskTick) bestAskTick = tick;
        }
    }

    public void replace(long orderId, Side side, long quantity, long price) {
        cancel(orderId);
        add(orderId, side, quantity, price);
    }

    public void modify(long orderId, Side side, long quantity, long price) {
        int orderIndex = lookupOrder(orderId);
        if (orderIndex == EMPTY) {
            return;
        }
        Order order = orders[orderIndex];
        if (price != order.price) {
            Side effectiveSide = side != null ? side : order.side();
            cancel(orderId);
            add(orderId, effectiveSide, quantity, price);
            return;
        }
        long oldQty = order.quantity;
        order.quantity = quantity;
        int tick = (int) (price - basePrice);
        if (tick >= 0 && tick < MAX_TICKS && tickLevels[tick] != null) {
            tickLevels[tick].quantity = tickLevels[tick].quantity - oldQty + quantity;
        }
    }

    public void cancel(long orderId) {
        int orderIndex = lookupOrder(orderId);
        if (orderIndex == EMPTY) {
            return;
        }
        Order order = orders[orderIndex];
        int tick = (int) (order.price - basePrice);
        PriceLevel level = (tick >= 0 && tick < MAX_TICKS) ? tickLevels[tick] : null;

        if (level != null) {
            // Unlink from doubly-linked queue
            int prev = order.prevIndex;
            int next = order.nextIndex;
            if (prev != EMPTY) {
                orders[prev].nextIndex = next;
            } else {
                level.headIndex = next;
            }
            if (next != EMPTY) {
                orders[next].prevIndex = prev;
            } else {
                level.tailIndex = prev;
            }
            level.quantity -= order.quantity;
            level.orderCount--;

            if (level.headIndex == EMPTY) {
                // Level is now empty -- return slab, clear tick slot
                tickLevels[tick] = null;
                recycleLevel(level);
                activeLevels--;
                // Recompute best bid/ask if we removed it
                if (order.side() == Side.BUY && tick == bestBidTick) {
                    bestBidTick = findBestBidTick();
                } else if (order.side() == Side.SELL && tick == bestAskTick) {
                    bestAskTick = findBestAskTick();
                }
            }
        }

        removeOrder(orderId);
        order.clear();
        recycleOrder(orderIndex);
        activeOrders--;
    }

    /**
     * Returns a formatted top-of-book string showing the best N bid and ask levels.
     */
    public String topOfBook(int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ORDER BOOK (Top ").append(depth).append(") ===\n");
        sb.append("       ASK\n");
        sb.append(String.format("  %-4s %-10s %-10s %-6s%n", "LVL", "PRICE", "QTY", "ORDERS"));

        // Collect ask levels (ascending from bestAskTick)
        long[] askPrices = new long[depth];
        long[] askQtys = new long[depth];
        int[] askCounts = new int[depth];
        int askFound = 0;
        if (bestAskTick < Integer.MAX_VALUE) {
            for (int t = bestAskTick; t < MAX_TICKS && askFound < depth; t++) {
                PriceLevel lv = tickLevels[t];
                if (lv != null && lv.side() == Side.SELL) {
                    askPrices[askFound] = lv.price;
                    askQtys[askFound] = lv.quantity;
                    askCounts[askFound] = lv.orderCount;
                    askFound++;
                }
            }
        }
        // Print asks in reverse (highest first at top)
        for (int i = askFound - 1; i >= 0; i--) {
            sb.append(String.format("  %-4d %-10s %-10s %-6d%n",
                    i + 1, formatPrice(askPrices[i]), formatQty(askQtys[i]), askCounts[i]));
        }

        // Spread
        if (bestBidTick >= 0 && bestAskTick < Integer.MAX_VALUE) {
            long spread = (basePrice + bestAskTick) - (basePrice + bestBidTick);
            sb.append(String.format("  ---------- SPREAD: %s ----------%n", formatPrice(spread)));
        } else {
            sb.append("  ---------- SPREAD ----------\n");
        }

        // Bid levels (descending from bestBidTick)
        int bidFound = 0;
        if (bestBidTick >= 0) {
            for (int t = bestBidTick; t >= 0 && bidFound < depth; t--) {
                PriceLevel lv = tickLevels[t];
                if (lv != null && lv.side() == Side.BUY) {
                    sb.append(String.format("  %-4d %-10s %-10s %-6d%n",
                            bidFound + 1, formatPrice(lv.price), formatQty(lv.quantity), lv.orderCount));
                    bidFound++;
                }
            }
        }
        sb.append("       BID\n");
        return sb.toString();
    }

    public String snapshot() {
        long bestBid = bestBidTick >= 0 ? basePrice + bestBidTick : -1;
        long bestAsk = bestAskTick < Integer.MAX_VALUE ? basePrice + bestAskTick : -1;
        return "OrderBook{bestBid=" + (bestBid == -1 ? "-" : bestBid)
                + ", bestAsk=" + (bestAsk == -1 ? "-" : bestAsk)
                + ", activeOrders=" + activeOrders
                + ", activeLevels=" + activeLevels + "}";
    }

    public int activeOrders() { return activeOrders; }
    public int activeLevels() { return activeLevels; }

    // ---- private helpers ----

    private int findBestBidTick() {
        for (int t = MAX_TICKS - 1; t >= 0; t--) {
            PriceLevel lv = tickLevels[t];
            if (lv != null && lv.side() == Side.BUY) return t;
        }
        return -1;
    }

    private int findBestAskTick() {
        for (int t = 0; t < MAX_TICKS; t++) {
            PriceLevel lv = tickLevels[t];
            if (lv != null && lv.side() == Side.SELL) return t;
        }
        return Integer.MAX_VALUE;
    }

    private int allocateOrder(long orderId, Side side, long quantity, long price) {
        int index;
        if (freeOrderTop >= 0) {
            index = freeOrderStack[freeOrderTop--];
        } else {
            index = nextOrderIndex++;
            if (index > maxOrders) throw new IllegalStateException("Order slab exhausted");
        }
        orders[index].set(orderId, side, quantity, price);
        putOrder(orderId, index);
        return index;
    }

    private void recycleOrder(int index) {
        if (freeOrderTop < freeOrderStack.length - 1) {
            freeOrderStack[++freeOrderTop] = index;
        }
    }

    private PriceLevel allocateLevel(Side side, long price) {
        int index;
        if (freeLevelTop >= 0) {
            index = freeLevelStack[freeLevelTop--];
        } else {
            index = nextLevelIndex++;
            if (index >= levels.length) throw new IllegalStateException("Price level slab exhausted");
        }
        levels[index].set(price, side);
        activeLevels++;
        return levels[index];
    }

    private void recycleLevel(PriceLevel level) {
        int idx = level.slabIndex; // O(1) - permanently stamped at construction
        level.clear();
        if (freeLevelTop < freeLevelStack.length - 1) {
            freeLevelStack[++freeLevelTop] = idx;
        }
    }

    private void appendOrder(PriceLevel level, int orderIndex) {
        Order order = orders[orderIndex];
        int tail = level.tailIndex;
        if (tail == EMPTY) {
            level.headIndex = orderIndex;
            level.tailIndex = orderIndex;
        } else {
            orders[tail].nextIndex = orderIndex;
            order.prevIndex = tail;
            level.tailIndex = orderIndex;
        }
        order.levelIndex = 0; // not used in dense design, kept for compatibility
        level.quantity += order.quantity;
        level.orderCount++;
    }

    private int lookupOrder(long orderId) {
        int mask = orderIdToSlot.length - 1;
        int slot = mix(orderId) & mask;
        while (orderIdToSlot[slot] != EMPTY) {
            if (orderIdKeys[slot] == orderId) return orderIdToSlot[slot];
            slot = (slot + 1) & mask;
        }
        return EMPTY;
    }

    private void putOrder(long orderId, int orderIndex) {
        int mask = orderIdToSlot.length - 1;
        int slot = mix(orderId) & mask;
        while (orderIdToSlot[slot] != EMPTY) {
            slot = (slot + 1) & mask;
        }
        orderIdToSlot[slot] = orderIndex;
        orderIdKeys[slot] = orderId;
    }

    private void removeOrder(long orderId) {
        int mask = orderIdToSlot.length - 1;
        int slot = mix(orderId) & mask;
        while (orderIdToSlot[slot] != EMPTY) {
            if (orderIdKeys[slot] == orderId) {
                deleteFromOpenAddressing(slot);
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void deleteFromOpenAddressing(int removeSlot) {
        int mask = orderIdToSlot.length - 1;
        orderIdToSlot[removeSlot] = EMPTY;
        orderIdKeys[removeSlot] = 0L;
        int slot = (removeSlot + 1) & mask;
        while (orderIdToSlot[slot] != EMPTY) {
            int rehashIndex = orderIdToSlot[slot];
            long rehashKey = orderIdKeys[slot];
            orderIdToSlot[slot] = EMPTY;
            orderIdKeys[slot] = 0L;
            int target = mix(rehashKey) & mask;
            while (orderIdToSlot[target] != EMPTY) target = (target + 1) & mask;
            orderIdToSlot[target] = rehashIndex;
            orderIdKeys[target] = rehashKey;
            slot = (slot + 1) & mask;
        }
    }

    private static int nextPow2(int value) {
        int v = 1;
        while (v < value) v <<= 1;
        return v;
    }

    private static int mix(long value) {
        long z = value ^ (value >>> 33);
        z *= 0xff51afd7ed558ccdL;
        z ^= (z >>> 33);
        z *= 0xc4ceb9fe1a85ec53L;
        z ^= (z >>> 33);
        return (int) z;
    }

    private static String formatPrice(long price) {
        return String.format("%,d", price);
    }

    private static String formatQty(long qty) {
        return String.format("%,d", qty);
    }
}

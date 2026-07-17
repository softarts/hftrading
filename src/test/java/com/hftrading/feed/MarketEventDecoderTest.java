package com.hftrading.feed;

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.BufferUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip encode → decode tests for all MessageType variants.
 */
class MarketEventDecoderTest {

    private UnsafeBuffer buffer;
    private MarketEvent decoded;

    @BeforeEach
    void setUp() {
        buffer  = new UnsafeBuffer(BufferUtil.allocateDirectAligned(MarketEventDecoder.MESSAGE_LENGTH, 64));
        decoded = new MarketEvent();
    }

    // ── Message length ────────────────────────────────────────────────────────

    @Test
    void messageLength_is48Bytes() {
        assertEquals(48, MarketEventDecoder.MESSAGE_LENGTH);
    }

    // ── NEW_ORDER round-trip ──────────────────────────────────────────────────

    @Test
    void roundTrip_newOrder() {
        MarketEvent src = makeEvent(MessageType.NEW_ORDER, Side.BUY,
                1_000_000_001L, 42, 999L, 100L, 100000L, 123456789L);

        MarketEventDecoder.encode(buffer, 0, src);
        MarketEventDecoder.decode(buffer, 0, decoded);

        assertEvent(decoded, MessageType.NEW_ORDER, Side.BUY,
                1_000_000_001L, 42, 999L, 100L, 100000L, 123456789L);
    }

    // ── REPLACE round-trip ────────────────────────────────────────────────────

    @Test
    void roundTrip_replace() {
        MarketEvent src = makeEvent(MessageType.REPLACE, Side.SELL,
                2_000_000_002L, 7, 777L, 500L, 200000L, 987654321L);

        MarketEventDecoder.encode(buffer, 0, src);
        MarketEventDecoder.decode(buffer, 0, decoded);

        assertEvent(decoded, MessageType.REPLACE, Side.SELL,
                2_000_000_002L, 7, 777L, 500L, 200000L, 987654321L);
    }

    // ── CANCEL round-trip (side is null) ──────────────────────────────────────

    @Test
    void roundTrip_cancel() {
        MarketEvent src = new MarketEvent();
        src.set(MessageType.CANCEL, 3_000_000_003L, 5, 111L, null, 0L, 0L);
        src.ingressNanos(55555L);

        MarketEventDecoder.encode(buffer, 0, src);
        MarketEventDecoder.decode(buffer, 0, decoded);

        assertEquals(MessageType.CANCEL, decoded.type());
        assertNull(decoded.side(),       "CANCEL side must be null");
        assertEquals(3_000_000_003L,    decoded.timestampNanos());
        assertEquals(5,                 decoded.symbol());
        assertEquals(111L,              decoded.orderId());
        assertEquals(55555L,            decoded.ingressNanos());
    }

    // ── MODIFY round-trip ─────────────────────────────────────────────────────

    @Test
    void roundTrip_modify() {
        MarketEvent src = makeEvent(MessageType.MODIFY, Side.BUY,
                4_000_000_004L, 3, 333L, 250L, 150000L, 0L);

        MarketEventDecoder.encode(buffer, 0, src);
        MarketEventDecoder.decode(buffer, 0, decoded);

        assertEvent(decoded, MessageType.MODIFY, Side.BUY,
                4_000_000_004L, 3, 333L, 250L, 150000L, 0L);
    }

    // ── Field boundary: max symbol (uint16) ───────────────────────────────────

    @Test
    void roundTrip_maxSymbol() {
        MarketEvent src = makeEvent(MessageType.NEW_ORDER, Side.BUY,
                1L, 65535, 1L, 1L, 1L, 0L);

        MarketEventDecoder.encode(buffer, 0, src);
        MarketEventDecoder.decode(buffer, 0, decoded);

        assertEquals(65535, decoded.symbol());
    }

    // ── ingressNanos is preserved faithfully ──────────────────────────────────

    @Test
    void ingressNanos_preserved() {
        long ingressNs = System.nanoTime();
        MarketEvent src = makeEvent(MessageType.NEW_ORDER, Side.SELL,
                1L, 1, 1L, 1L, 1L, ingressNs);

        MarketEventDecoder.encode(buffer, 0, src);
        MarketEventDecoder.decode(buffer, 0, decoded);

        assertEquals(ingressNs, decoded.ingressNanos());
    }

    // ── Padding bytes don't bleed into adjacent fields ────────────────────────

    @Test
    void paddingDoesNotCorruptFields() {
        MarketEvent src = makeEvent(MessageType.NEW_ORDER, Side.BUY,
                Long.MAX_VALUE, 1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

        MarketEventDecoder.encode(buffer, 0, src);
        MarketEventDecoder.decode(buffer, 0, decoded);

        assertEquals(Long.MAX_VALUE, decoded.timestampNanos());
        assertEquals(Long.MAX_VALUE, decoded.orderId());
        assertEquals(Long.MAX_VALUE, decoded.quantity());
        assertEquals(Long.MAX_VALUE, decoded.price());
        assertEquals(Long.MAX_VALUE, decoded.ingressNanos());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static MarketEvent makeEvent(MessageType type, Side side,
                                         long tsNanos, int symbol,
                                         long orderId, long qty, long price,
                                         long ingressNs) {
        MarketEvent ev = new MarketEvent();
        ev.set(type, tsNanos, symbol, orderId, side, qty, price);
        ev.ingressNanos(ingressNs);
        return ev;
    }

    private static void assertEvent(MarketEvent ev, MessageType type, Side side,
                                    long tsNanos, int symbol,
                                    long orderId, long qty, long price, long ingressNs) {
        assertEquals(type,      ev.type(),           "type");
        assertEquals(side,      ev.side(),           "side");
        assertEquals(tsNanos,   ev.timestampNanos(), "timestampNanos");
        assertEquals(symbol,    ev.symbol(),         "symbol");
        assertEquals(orderId,   ev.orderId(),        "orderId");
        assertEquals(qty,       ev.quantity(),       "quantity");
        assertEquals(price,     ev.price(),          "price");
        assertEquals(ingressNs, ev.ingressNanos(),   "ingressNanos");
    }
}

package com.hftrading.feed;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Wire-format constants and zero-allocation encode/decode for the 48-byte
 * fixed-layout market event message.
 *
 * <p>Offsets are defined here (Java side) and in {@code cpp/include/wire_format.h}
 * (C++ side). These two files are the single source of truth — keep them in sync.
 *
 * <h2>Layout (48 bytes, fits in one 64-byte cache line)</h2>
 * <pre>
 *  Offset  Width  Field
 *  ------  -----  -----
 *       0      1  msgType       ASCII: N=NEW, R=REPLACE, X=CANCEL, M=MODIFY
 *       1      1  side          ASCII: B=BUY, S=SELL, 0=n/a (CANCEL)
 *       2      2  symbol        uint16, up to 65535 instruments
 *       4      4  padding       align to 8-byte boundary
 *       8      8  timestampNanos  exchange event time (int64, nanoseconds)
 *      16      8  orderId       int64
 *      24      8  quantity      int64
 *      32      8  price         int64, fixed-point (e.g. price × 10000)
 *      40      8  ingressNanos  pipeline entry time (set by publisher before send)
 * </pre>
 */
public final class MarketEventDecoder {

    // -------------------------------------------------------------------------
    // Wire format offsets
    // -------------------------------------------------------------------------

    public static final int OFFSET_MSG_TYPE        =  0;
    public static final int OFFSET_SIDE            =  1;
    public static final int OFFSET_SYMBOL          =  2;
    // 4 bytes explicit padding at offset 4 (alignment)
    public static final int OFFSET_TIMESTAMP_NANOS =  8;
    public static final int OFFSET_ORDER_ID        = 16;
    public static final int OFFSET_QUANTITY        = 24;
    public static final int OFFSET_PRICE           = 32;
    public static final int OFFSET_INGRESS_NANOS   = 40;

    /** Total message length in bytes. */
    public static final int MESSAGE_LENGTH = 48;

    private MarketEventDecoder() {}

    // -------------------------------------------------------------------------
    // Decode
    // -------------------------------------------------------------------------

    /**
     * Decode a message from an Agrona {@link DirectBuffer} into a reusable
     * {@link MarketEvent}. Zero-allocation on the hot path.
     *
     * @param buffer source buffer (e.g. the Aeron fragment buffer)
     * @param offset start offset within {@code buffer}
     * @param event  reusable event object to populate
     */
    public static void decode(DirectBuffer buffer, int offset, MarketEvent event) {
        char msgTypeChar = (char) (buffer.getByte(offset + OFFSET_MSG_TYPE) & 0xFF);
        char sideChar    = (char) (buffer.getByte(offset + OFFSET_SIDE)     & 0xFF);
        int symbol       = buffer.getShort(offset + OFFSET_SYMBOL)  & 0xFFFF;
        long tsNanos     = buffer.getLong(offset + OFFSET_TIMESTAMP_NANOS);
        long orderId     = buffer.getLong(offset + OFFSET_ORDER_ID);
        long quantity    = buffer.getLong(offset + OFFSET_QUANTITY);
        long price       = buffer.getLong(offset + OFFSET_PRICE);
        long ingressNs   = buffer.getLong(offset + OFFSET_INGRESS_NANOS);

        MessageType type = MessageType.fromCode(msgTypeChar);
        Side side        = (type == MessageType.CANCEL) ? null : Side.fromCode(sideChar);

        event.set(type, tsNanos, symbol, orderId, side, quantity, price);
        event.ingressNanos(ingressNs);
    }

    // -------------------------------------------------------------------------
    // Encode
    // -------------------------------------------------------------------------

    /**
     * Encode a {@link MarketEvent} into an Agrona {@link MutableDirectBuffer}.
     * Zero-allocation on the hot path.
     *
     * @param buffer destination buffer (pre-allocated, at least
     *               {@code offset + MESSAGE_LENGTH} bytes)
     * @param offset start offset within {@code buffer}
     * @param event  source event
     */
    public static void encode(MutableDirectBuffer buffer, int offset, MarketEvent event) {
        buffer.putByte(offset + OFFSET_MSG_TYPE,        (byte) event.type().code());
        buffer.putByte(offset + OFFSET_SIDE,            (byte) (event.side() != null ? event.side().code() : '0'));
        buffer.putShort(offset + OFFSET_SYMBOL,         (short) event.symbol());
        buffer.putInt(offset + 4,                       0); // explicit padding
        buffer.putLong(offset + OFFSET_TIMESTAMP_NANOS, event.timestampNanos());
        buffer.putLong(offset + OFFSET_ORDER_ID,        event.orderId());
        buffer.putLong(offset + OFFSET_QUANTITY,        event.quantity());
        buffer.putLong(offset + OFFSET_PRICE,           event.price());
        buffer.putLong(offset + OFFSET_INGRESS_NANOS,   event.ingressNanos());
    }
}

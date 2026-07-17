#pragma once
/**
 * wire_format.h — Shared wire format constants for the 48-byte market event message.
 *
 * IMPORTANT: Keep this file in sync with MarketEventDecoder.java (Java side).
 * These two files are the single source of truth for the wire layout.
 *
 * Layout (48 bytes, fits in one 64-byte cache line with alignment):
 *
 *  Offset  Width  Field
 *  ------  -----  -----
 *       0      1  msg_type       ASCII: N=NEW, R=REPLACE, X=CANCEL, M=MODIFY
 *       1      1  side           ASCII: B=BUY, S=SELL, 0=n/a (CANCEL)
 *       2      2  symbol         uint16, up to 65535 instruments
 *       4      4  padding        explicit alignment to 8-byte boundary
 *       8      8  timestamp_nanos  exchange event time (int64, nanoseconds)
 *      16      8  order_id       int64
 *      24      8  quantity       int64
 *      32      8  price          int64, fixed-point (e.g. price × 10000)
 *      40      8  ingress_nanos  pipeline entry time (set by publisher before offer)
 */

#include <cstdint>
#include <cstring>

namespace hft {

// ── Offsets ────────────────────────────────────────────────────────────────
static constexpr int OFFSET_MSG_TYPE         =  0;
static constexpr int OFFSET_SIDE             =  1;
static constexpr int OFFSET_SYMBOL           =  2;
// 4 bytes padding at offset 4
static constexpr int OFFSET_TIMESTAMP_NANOS  =  8;
static constexpr int OFFSET_ORDER_ID         = 16;
static constexpr int OFFSET_QUANTITY         = 24;
static constexpr int OFFSET_PRICE            = 32;
static constexpr int OFFSET_INGRESS_NANOS    = 40;

static constexpr int MESSAGE_LENGTH          = 48;

// ── Message type codes ─────────────────────────────────────────────────────
static constexpr char MSG_NEW_ORDER = 'N';
static constexpr char MSG_REPLACE   = 'R';
static constexpr char MSG_CANCEL    = 'X';
static constexpr char MSG_MODIFY    = 'M';

// ── Side codes ─────────────────────────────────────────────────────────────
static constexpr char SIDE_BUY      = 'B';
static constexpr char SIDE_SELL     = 'S';
static constexpr char SIDE_NONE     = '0'; // used for CANCEL

// ── Helpers ────────────────────────────────────────────────────────────────

struct MarketEvent {
    char     msg_type;
    char     side;
    uint16_t symbol;
    int64_t  timestamp_nanos;
    int64_t  order_id;
    int64_t  quantity;
    int64_t  price;
    int64_t  ingress_nanos;
};

/**
 * Encode a MarketEvent into a 48-byte buffer using the fixed wire layout.
 * The buffer must be at least MESSAGE_LENGTH bytes.
 */
inline void encode(uint8_t* buf, const MarketEvent& ev) {
    buf[OFFSET_MSG_TYPE] = static_cast<uint8_t>(ev.msg_type);
    buf[OFFSET_SIDE]     = static_cast<uint8_t>(ev.side);

    // symbol (uint16, little-endian matches JVM DirectBuffer default on x86)
    uint16_t sym = ev.symbol;
    std::memcpy(buf + OFFSET_SYMBOL, &sym, 2);

    // explicit 4-byte padding (zeroed)
    std::memset(buf + 4, 0, 4);

    // 8-byte fields — little-endian on x86
    int64_t ts  = ev.timestamp_nanos;  std::memcpy(buf + OFFSET_TIMESTAMP_NANOS, &ts,  8);
    int64_t oid = ev.order_id;         std::memcpy(buf + OFFSET_ORDER_ID,        &oid, 8);
    int64_t qty = ev.quantity;         std::memcpy(buf + OFFSET_QUANTITY,         &qty, 8);
    int64_t px  = ev.price;            std::memcpy(buf + OFFSET_PRICE,            &px,  8);
    int64_t ing = ev.ingress_nanos;    std::memcpy(buf + OFFSET_INGRESS_NANOS,    &ing, 8);
}

/**
 * Decode a 48-byte buffer into a MarketEvent.
 */
inline void decode(const uint8_t* buf, MarketEvent& ev) {
    ev.msg_type = static_cast<char>(buf[OFFSET_MSG_TYPE]);
    ev.side     = static_cast<char>(buf[OFFSET_SIDE]);
    std::memcpy(&ev.symbol,           buf + OFFSET_SYMBOL,           2);
    std::memcpy(&ev.timestamp_nanos,  buf + OFFSET_TIMESTAMP_NANOS,  8);
    std::memcpy(&ev.order_id,         buf + OFFSET_ORDER_ID,         8);
    std::memcpy(&ev.quantity,         buf + OFFSET_QUANTITY,         8);
    std::memcpy(&ev.price,            buf + OFFSET_PRICE,            8);
    std::memcpy(&ev.ingress_nanos,    buf + OFFSET_INGRESS_NANOS,    8);
}

} // namespace hft

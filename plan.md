# HFT Trading System — Implementation Plan

## Architecture Overview

The full pipeline topology:

```
[Exchange / Market Data]
        │
        ▼
[C++ DPDK Process]  ← kernel-bypass packet capture
        │
        │  aeron:ipc (shared memory ring)
        ▼
[JVM Process]
  AeronFeedSourceImpl (spin-poll, pinned core)
        │
        ▼
  OrderBook (zero-alloc, slab-allocated)
        │
        ▼
  Strategy / Risk / OMS  (future)
```

For development and benchmarking, `CsvFeedSource` can substitute for the C++ DPDK process:

```
Mode: direct
  CsvFeedSource ──────────────────────────────► OrderBook

Mode: aeron
  CsvFeedSource ──► AeronPublisher (Java) ──► [aeron:ipc] ──► AeronSubscriber ──► OrderBook
```

---

## C++/Java Communication — Approach Decision

### Options Evaluated

| Approach | Latency | Complexity | Notes |
|---|---|---|---|
| JNI (in-process) | ~50–200 ns | High | C++ segfault kills JVM; safepoint risk |
| Aeron IPC (shared memory) | ~150–400 ns | Medium | Industry standard; process isolation |
| Raw mmap ring buffer | ~100–300 ns | High | Aeron is a hardened version of this |
| LMAX Disruptor | in-JVM only | Low | No C++ ingestion; pure Java pipeline |
| FPGA/RDMA → DirectByteBuffer | ~50 ns | Very High | Co-lo only; overkill for prototype |

### Decision: Aeron IPC

- Process isolation: C++ crash does not kill the JVM
- Sub-microsecond IPC latency with busy-spin on pinned cores
- Battle-tested in production HFT environments
- Clean Java API (Agrona `DirectBuffer`, `FragmentHandler`)
- `aeron.subscribe` latency probe is only valid in Java-publisher mode (same process, consistent `nanoTime()`). C++ cross-process measurement requires TSC-based shared clock — documented as future work.

---

## Wire Format — 48-byte Fixed Layout

Raw binary via Agrona `UnsafeBuffer`. No SBE for now — fixed schema does not justify the code-generation overhead.

| Offset | Width | Field | Notes |
|---|---|---|---|
| 0 | 1 | `msgType` | ASCII: N=NEW, R=REPLACE, X=CANCEL, M=MODIFY |
| 1 | 1 | `side` | ASCII: B=BUY, S=SELL, 0 for CANCEL |
| 2 | 2 | `symbol` | uint16, supports 65535 instruments |
| 4 | 4 | `padding` | align to 8-byte boundary |
| 8 | 8 | `timestampNanos` | exchange event time (int64, nanoseconds) |
| 16 | 8 | `orderId` | int64 |
| 24 | 8 | `quantity` | int64 |
| 32 | 8 | `price` | int64, fixed-point (e.g. price × 10000) |
| 40 | 8 | `ingressNanos` | pipeline entry time — set by CsvFeedSource or AeronSubscriber; separate from exchange timestamp |

Total: 48 bytes. Fits in one 64-byte cache line with alignment padding.

Offsets defined in exactly two places: `MarketEventDecoder.java` and `wire_format.h` (C++).

---

## MarketEvent Changes

Add `ingressNanos` field alongside `timestampNanos`:

- `timestampNanos` — exchange event time. Set from wire format. Never overwritten.
- `ingressNanos` — when this process first saw the event. Set by `CsvFeedSource` (direct mode) or `AeronFeedSourceImpl.onFragment()` (aeron mode) using `System.nanoTime()`.

This separation keeps exchange time clean for strategy use while enabling accurate e2e latency measurement.

---

## Per-Component Latency Probes

Each probe is an `HdrHistogram` instance (replaces the current sort-based `LatencyMeasurement`). All probes are optional — disabled probes have zero hot-path overhead (null check only).

| Probe | Config Key | Measures | Instrumented In |
|---|---|---|---|
| `csv.parse` | `latency.csv.parse` | Parse one CSV line → `MarketEvent` | `CsvFeedSource.replay()` |
| `aeron.publish` | `latency.aeron.publish` | `pub.offer()` call duration (encode + ring write) | `AeronPublisher.onEvent()` |
| `aeron.subscribe` | `latency.aeron.subscribe` | `ingressNanos` − `timestampNanos` (Java publisher mode only) | `AeronFeedSourceImpl.onFragment()` |
| `book.apply` | `latency.book.apply` | `OrderBook.apply(event)` duration | `FeedHandler` wrapping `book.apply()` |
| `e2e` | `latency.e2e` | `System.nanoTime()` after `book.apply()` − `ingressNanos` | `FeedHandler` after `book.apply()` |

**Important:** `aeron.subscribe` latency is only meaningful in `pipeline.mode=aeron` with the Java publisher. In C++ publisher mode the clocks are not synchronized — TSC-based shared clock is required (future work).

**HdrHistogram** (`org.hdrhistogram:HdrHistogram:2.1.12`) replaces `LatencyMeasurement`:
- `Histogram.recordValue(latencyNs)` is allocation-free on the hot path
- Handles coordinated omission correctly
- Provides p50/p90/p99/p99.9/p99.99/max

At shutdown, all enabled probes print side-by-side and optionally dump to CSV.

---

## Warm-Up Phase

JVM interprets the first ~50k–100k events before JIT compiles the hot path. Early samples are 5–10× higher than steady state and must be excluded from latency histograms.

Config key: `benchmark.warmup.events=100000`

During warm-up, the pipeline runs normally but probes do not record. A log line marks the transition: `[WARMUP COMPLETE] recording latency from event 100001`.

---

## Configuration — `config/default.properties`

All configuration is in a single `.properties` file. `HftConfig.java` loads it, exposes typed getters with defaults, validates required keys at startup, and prints the effective config on launch.

```properties
# --- Pipeline mode ---
# direct: CsvFeedSource → OrderBook
# aeron:  CsvFeedSource → AeronPublisher → [aeron:ipc] → AeronSubscriber → OrderBook
pipeline.mode=direct

# --- Input ---
input.csv.path=data/sample.csv
input.csv.buffer.bytes=262144

# --- Aeron ---
aeron.channel=aeron:ipc
aeron.stream.id=1001
aeron.embed.driver=true
aeron.subscriber.cpu=-1
aeron.publisher.cpu=-1
aeron.fragment.limit=256

# --- Order Book ---
book.max.orders=1000000
book.max.price.levels=8192
book.max.products=1024
book.tick.window=500

# --- Latency probes ---
latency.csv.parse=true
latency.aeron.publish=true
latency.aeron.subscribe=true
latency.book.apply=true
latency.e2e=true

# --- Benchmark ---
benchmark.warmup.events=100000
latency.histogram.size=1000000
latency.output.file=output/latency_report.csv

# --- Output ---
output.snapshot.depth=5
output.print.top.of.book=true
```

The C++ publisher reads the same file (trivial 30-line parser) for `aeron.channel`, `aeron.stream.id`, and `aeron.publisher.cpu`. This prevents config drift between the two processes.

---

## JVM Startup Flags

Document in `scripts/run.sh` and `scripts/jvm.flags`:

```
-Xms4g -Xmx4g
-XX:+UseZGC
-XX:+AlwaysPreTouch
-XX:+UnlockExperimentalVMOptions
-XX:+DisableExplicitGC
-Djava.lang.invoke.stringConcat=BC_SB
```

Zero-GC code discipline is meaningless without GC tuning. `-XX:+AlwaysPreTouch` faults heap pages at startup to prevent OS page faults mid-benchmark.

---

## New / Modified Files

### New Java Files

| File | Purpose |
|---|---|
| `src/main/java/com/hftrading/config/HftConfig.java` | Replaces `AppConfig.java`; loads `.properties`, typed getters, startup validation |
| `src/main/java/com/hftrading/feed/MarketEventDecoder.java` | Wire format constants + zero-alloc `decode(DirectBuffer, offset, MarketEvent)` |
| `src/main/java/com/hftrading/feed/AeronPublisher.java` | Java-side IPC publisher; implements `FeedHandler`; takes events from `CsvFeedSource` |
| `src/main/java/com/hftrading/feed/AeronFeedSourceImpl.java` | IPC subscriber; spin-poll; `BusySpinIdleStrategy`; `ingressNanos` probe |
| `src/main/java/com/hftrading/util/LatencyProbe.java` | Thin wrapper around `HdrHistogram`; null-safe static helper for hot-path recording |

### Modified Java Files

| File | Change |
|---|---|
| `src/main/java/com/hftrading/feed/MarketEvent.java` | Add `ingressNanos` field; update `set()` and `clear()` |
| `src/main/java/com/hftrading/feed/CsvFeedSource.java` | Stamp `event.ingressNanos = System.nanoTime()` at parse entry; wrap parse in `csv.parse` probe |
| `src/main/java/com/hftrading/app/Main.java` | Load `HftConfig`; branch on `pipeline.mode`; wire all probes; print summary + CSV dump |

### Deleted Files

| File | Reason |
|---|---|
| `src/main/java/com/hftrading/app/AppConfig.java` | Replaced by `HftConfig.java` |
| `src/main/java/com/hftrading/util/LatencyMeasurement.java` | Replaced by `LatencyProbe.java` + HdrHistogram |

### New C++ Files

| File | Purpose |
|---|---|
| `cpp/CMakeLists.txt` | Builds `market_data_publisher` via Aeron C++ client (vcpkg) |
| `cpp/publisher/MarketDataPublisher.cpp` | IPC publisher; reads `config.properties`; encodes 48-byte messages; pinned thread |
| `cpp/include/wire_format.h` | Shared offset constants — single source of truth for C++ side |

### New Config / Build Files

| File | Purpose |
|---|---|
| `build.gradle` | Aeron, Agrona, HdrHistogram, JUnit 5 dependencies; CMake exec task |
| `settings.gradle` | `rootProject.name = 'hftrading'` |
| `config/default.properties` | All configuration knobs with documentation |
| `scripts/run.sh` / `scripts/run.cmd` | JVM flag file + launcher |
| `scripts/jvm.flags` | Documented GC and tuning flags |

---

## New Test Files

| File | Tests |
|---|---|
| `AeronPublisherTest.java` | Java publisher sends N events; subscriber receives all with correct fields |
| `AeronFeedSourceIntegrationTest.java` | Embedded driver; full round-trip; field-level assertions |
| `HftConfigTest.java` | Property loading, defaults, missing required key throws at startup |
| `MarketEventDecoderTest.java` | Encode → decode round-trip for all `MessageType` variants |

---

## Implementation Sequence

```
Phase 0 — Foundation (no Aeron yet)
  1. build.gradle + settings.gradle          ← dependency resolution
  2. config/default.properties               ← all knobs documented
  3. HftConfig.java                          ← replaces AppConfig; validates at startup
  4. README.md (architecture section)        ← communication approaches, decision rationale
  5. scripts/jvm.flags + run scripts

Phase 1 — Latency Infrastructure
  6. LatencyProbe.java (HdrHistogram wrapper)
  7. MarketEvent.java (add ingressNanos)
  8. CsvFeedSource.java (stamp ingressNanos, csv.parse probe)

Phase 2 — Aeron Transport
  9.  MarketEventDecoder.java               ← wire format, decode()
  10. AeronPublisher.java                   ← Java IPC publisher
  11. AeronFeedSourceImpl.java              ← subscriber, probes
  12. cpp/CMakeLists.txt
  13. cpp/include/wire_format.h
  14. cpp/publisher/MarketDataPublisher.cpp

Phase 3 — Wiring
  15. Main.java (pipeline.mode branch, probe wiring, summary output)

Phase 4 — Tests
  16. HftConfigTest, MarketEventDecoderTest
  17. AeronPublisherTest, AeronFeedSourceIntegrationTest
```

---

## Known Limitations / Future Work

- `aeron.subscribe` cross-process latency requires TSC-based clock (not `System.nanoTime()`)
- Out-of-band price handling (prices outside 500-tick window) — Phase 2 ring buffer + fallback hash map
- Matching engine (fill events, execution simulation) — not yet planned
- Thread pinning on Windows requires `SetThreadAffinityMask` JNI shim; Linux uses OpenHFT affinity library
- Strategy / alpha engine, pre-trade risk, OMS adapter — out of scope for this phase

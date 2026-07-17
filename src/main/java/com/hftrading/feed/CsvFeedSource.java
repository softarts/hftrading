package com.hftrading.feed;

import com.hftrading.util.LatencyProbe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zero-alloc CSV feed source. Reads a pipe-delimited market-event file and
 * delivers events to a {@link FeedHandler} via a reusable {@link MarketEvent}.
 *
 * <p><b>Instrumentation:</b>
 * <ul>
 *   <li>{@code csv.parse} probe — wraps the parse call for each line.</li>
 *   <li>{@code ingressNanos} — stamped on the event immediately before parsing,
 *       so downstream probes (book.apply, e2e) measure from this point.</li>
 * </ul>
 *
 * <p><b>Warm-up:</b> probes do not record during the first
 * {@code warmupEvents} events. A log line marks the transition.
 */
public final class CsvFeedSource implements FeedSource {

    private final InputStream in;
    private final MarketEvent reusableEvent = new MarketEvent();

    private final byte[] buf;
    private int bufLen = 0;
    private int bufPos = 0;

    // Latency probe (null = disabled)
    private LatencyProbe csvParseProbe;

    // Warm-up
    private int warmupEvents = 0;
    private int eventCount   = 0;
    private boolean warmupDone = false;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public CsvFeedSource(Path path) throws IOException {
        this(path, 256 * 1024);
    }

    public CsvFeedSource(Path path, int bufferBytes) throws IOException {
        this.in  = Files.newInputStream(path);
        this.buf = new byte[Math.max(bufferBytes, 4096)];
    }

    // -------------------------------------------------------------------------
    // Configuration setters (call before replay())
    // -------------------------------------------------------------------------

    /** Attach the csv.parse latency probe. Pass {@code null} to disable. */
    public void setCsvParseProbe(LatencyProbe probe) {
        this.csvParseProbe = probe;
    }

    /**
     * Number of events to skip before latency probes start recording.
     * Set to 0 (default) to record from the very first event.
     */
    public void setWarmupEvents(int warmupEvents) {
        this.warmupEvents = warmupEvents;
        this.warmupDone   = (warmupEvents <= 0);
    }

    // -------------------------------------------------------------------------
    // FeedSource
    // -------------------------------------------------------------------------

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public void replay(FeedHandler handler) throws IOException {
        while (true) {
            // Compact buffer
            if (bufPos > 0 && bufLen > 0) {
                System.arraycopy(buf, bufPos, buf, 0, bufLen - bufPos);
            }
            bufLen -= bufPos;
            bufPos = 0;

            int read = in.read(buf, bufLen, buf.length - bufLen);
            if (read <= 0) {
                // Flush any remaining bytes (file with no trailing newline)
                if (bufLen > 0) {
                    processLine(buf, 0, bufLen, handler);
                }
                break;
            }
            bufLen += read;

            while (bufPos < bufLen) {
                int end = indexOf(buf, (byte) '\n', bufPos, bufLen);
                if (end == bufLen) {
                    break; // incomplete line — wait for more data
                }
                int len = end - bufPos;
                if (len > 0 && buf[end - 1] == '\r') {
                    len--; // strip CR from CRLF
                }
                processLine(buf, bufPos, bufPos + len, handler);
                bufPos = end + 1;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void processLine(byte[] buf, int start, int end, FeedHandler handler) {
        if (start >= end || buf[start] == '#') {
            return; // empty or comment line
        }

        // Stamp ingress time immediately — before parse overhead.
        long ingressNs = System.nanoTime();

        // Track warm-up transition
        eventCount++;
        if (!warmupDone && eventCount > warmupEvents) {
            warmupDone = true;
            System.out.println("[WARMUP COMPLETE] recording latency from event " + eventCount);
        }
        boolean doRecord = warmupDone;

        long parseStart = (csvParseProbe != null && doRecord) ? System.nanoTime() : 0L;
        parse(buf, start, end, reusableEvent);
        if (csvParseProbe != null && doRecord) {
            csvParseProbe.record(System.nanoTime() - parseStart);
        }

        // Stamp ingressNanos on the event so downstream stages can compute e2e
        reusableEvent.ingressNanos(ingressNs);

        handler.onEvent(reusableEvent);
    }

    // -------------------------------------------------------------------------
    // Zero-alloc CSV parser
    // -------------------------------------------------------------------------

    private static void parse(byte[] line, int start, int end, MarketEvent event) {
        int pos = start;

        MessageType type = MessageType.fromCode((char) line[pos]);
        pos += 2; // skip msgType + comma

        int comma = indexOf(line, (byte) ',', pos, end);
        long ts = parseLong(line, pos, comma);
        pos = comma + 1;

        comma = indexOf(line, (byte) ',', pos, end);
        int symbol = (int) parseLong(line, pos, comma);
        pos = comma + 1;

        comma = indexOf(line, (byte) ',', pos, end);
        long orderId = parseLong(line, pos, comma);
        pos = comma + 1;

        char sideChar = (char) line[pos];
        Side side = (type == MessageType.CANCEL) ? null : Side.fromCode(sideChar);
        pos += 2; // skip side + comma

        comma = indexOf(line, (byte) ',', pos, end);
        long quantity = parseLong(line, pos, comma);
        pos = comma + 1;

        long price = parseLong(line, pos, end);

        event.set(type, ts, symbol, orderId, side, quantity, price);
    }

    private static int indexOf(byte[] s, byte c, int from, int max) {
        for (int i = from; i < max; i++) {
            if (s[i] == c) return i;
        }
        return max;
    }

    private static long parseLong(byte[] s, int start, int end) {
        long v = 0;
        for (int i = start; i < end; i++) {
            v = v * 10 + (s[i] - '0');
        }
        return v;
    }
}

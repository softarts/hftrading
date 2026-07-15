package com.hftrading.feed;

import com.hftrading.util.LatencyMeasurement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CsvFeedSource implements FeedSource {
    private final InputStream in;
    private LatencyMeasurement csvLatency;
    private LatencyMeasurement e2eLatency;
    private final MarketEvent reusableEvent = new MarketEvent();

    private final byte[] buf = new byte[256 * 1024];
    private int bufLen = 0;
    private int bufPos = 0;

    public CsvFeedSource(Path path) throws IOException {
        this.in = Files.newInputStream(path);
    }

    public void setCsvLatency(LatencyMeasurement csvLatency) {
        this.csvLatency = csvLatency;
    }

    public void setE2eLatency(LatencyMeasurement e2eLatency) {
        this.e2eLatency = e2eLatency;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public void replay(FeedHandler handler) throws IOException {
        while (true) {
            if (bufPos > 0 && bufLen > 0) {
                System.arraycopy(buf, bufPos, buf, 0, bufLen - bufPos);
            }
            bufLen -= bufPos;
            bufPos = 0;

            int read = in.read(buf, bufLen, buf.length - bufLen);
            if (read <= 0) {
                if (bufLen > 0) {
                    processLine(buf, 0, bufLen, handler, (csvLatency != null || e2eLatency != null) ? System.nanoTime() : 0L);
                }
                break;
            }
            bufLen += read;

            while (bufPos < bufLen) {
                long e2eStart = (csvLatency != null || e2eLatency != null) ? System.nanoTime() : 0L;
                int end = indexOf(buf, (byte)'\n', bufPos, bufLen);
                if (end == bufLen) {
                    break;
                }
                int len = end - bufPos;
                if (len > 0 && buf[end - 1] == '\r') {
                    len--;
                }
                processLine(buf, bufPos, bufPos + len, handler, e2eStart);
                bufPos = end + 1;
            }
        }
    }

    private void processLine(byte[] buf, int start, int end, FeedHandler handler, long e2eStart) {
        if (start >= end || buf[start] == '#') {
            return;
        }
        parse(buf, start, end, reusableEvent);
        if (csvLatency != null) {
            csvLatency.record(System.nanoTime() - e2eStart);
        }
        handler.onEvent(reusableEvent);
        if (e2eLatency != null) {
            e2eLatency.record(System.nanoTime() - e2eStart);
        }
    }

    private static void parse(byte[] line, int start, int end, MarketEvent event) {
        int pos = start;

        MessageType type = MessageType.fromCode((char) line[pos]);
        pos += 2; 

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
        pos += 2;

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


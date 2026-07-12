package com.hftrading.feed;

import com.hftrading.util.LatencyMeasurement;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CsvFeedSource implements FeedSource {
    private final BufferedReader reader;
    private LatencyMeasurement csvLatency;
    private LatencyMeasurement e2eLatency;
    private final MarketEvent reusableEvent = new MarketEvent();

    public CsvFeedSource(Path path) throws IOException {
        this.reader = Files.newBufferedReader(path);
    }

    public void setCsvLatency(LatencyMeasurement csvLatency) {
        this.csvLatency = csvLatency;
    }

    public void setE2eLatency(LatencyMeasurement e2eLatency) {
        this.e2eLatency = e2eLatency;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    @Override
    public void replay(FeedHandler handler) throws IOException {
        String line;
        while (true) {
            long e2eStart = (csvLatency != null || e2eLatency != null) ? System.nanoTime() : 0L;
            line = reader.readLine();
            if (line == null) break;
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            parse(line, reusableEvent);
            if (csvLatency != null) {
                csvLatency.record(System.nanoTime() - e2eStart);
            }
            handler.onEvent(reusableEvent);
            if (e2eLatency != null) {
                e2eLatency.record(System.nanoTime() - e2eStart);
            }
        }
    }

    // Zero-allocation parser. Format: TYPE,TS,SYMBOL,ORDERID,SIDE,QTY,PRICE
    private static void parse(String line, MarketEvent event) {
        int len = line.length();
        int pos = 0;

        // field 0: type char
        MessageType type = MessageType.fromCode(line.charAt(pos));
        pos += 2; // skip char + comma

        // field 1: timestampNanos
        int end = indexOf(line, ',', pos, len);
        long ts = parseLong(line, pos, end);
        pos = end + 1;

        // field 2: symbol
        end = indexOf(line, ',', pos, len);
        int symbol = (int) parseLong(line, pos, end);
        pos = end + 1;

        // field 3: orderId
        end = indexOf(line, ',', pos, len);
        long orderId = parseLong(line, pos, end);
        pos = end + 1;

        // field 4: side char
        char sideChar = line.charAt(pos);
        Side side = (type == MessageType.CANCEL) ? null : Side.fromCode(sideChar);
        pos += 2; // skip char + comma

        // field 5: quantity
        end = indexOf(line, ',', pos, len);
        long quantity = parseLong(line, pos, end);
        pos = end + 1;

        // field 6: price (rest of line)
        long price = parseLong(line, pos, len);

        event.set(type, ts, symbol, orderId, side, quantity, price);
    }

    private static int indexOf(String s, char c, int from, int max) {
        for (int i = from; i < max; i++) {
            if (s.charAt(i) == c) return i;
        }
        return max;
    }

    /** Parse a non-negative decimal integer from s[start..end) without allocation. */
    private static long parseLong(String s, int start, int end) {
        long v = 0;
        for (int i = start; i < end; i++) {
            v = v * 10 + (s.charAt(i) - '0');
        }
        return v;
    }
}


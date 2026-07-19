package com.hftrading.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HftConfigTest {

    @TempDir
    Path tempDir;

    private Path write(String content) throws IOException {
        Path p = tempDir.resolve("test.properties");
        Files.writeString(p, content);
        return p;
    }

    // ── Happy-path loading ────────────────────────────────────────────────────

    @Test
    void load_directMode_defaults() throws IOException {
        Path props = write("pipeline.mode=direct\n");
        HftConfig cfg = HftConfig.load(props);

        assertEquals("direct", cfg.pipelineMode());
        assertEquals("aeron:ipc", cfg.aeronChannel());
        assertEquals(1001, cfg.aeronStreamId());
        assertTrue(cfg.aeronEmbedDriver());
        assertEquals(-1, cfg.aeronSubscriberCpu());
        assertEquals(-1, cfg.aeronPublisherCpu());
        assertEquals(256, cfg.aeronFragmentLimit());
        assertEquals(1_000_000, cfg.bookMaxOrders());
        assertEquals(8192, cfg.bookMaxPriceLevels());
        assertEquals(1024, cfg.bookMaxProducts());
        assertEquals(500, cfg.bookTickWindow());
        assertTrue(cfg.latencyCsvParse());
        assertTrue(cfg.latencyAeron());
        assertTrue(cfg.latencyBookApply());
        assertTrue(cfg.latencyE2e());
        assertEquals(100_000, cfg.benchmarkWarmupEvents());
        assertEquals(5, cfg.outputSnapshotDepth());
        assertTrue(cfg.outputPrintTopOfBook());
    }

    @Test
    void load_aeronMode() throws IOException {
        Path props = write(
                "pipeline.mode=aeron\n" +
                "aeron.channel=aeron:ipc\n" +
                "aeron.stream.id=2002\n" +
                "aeron.embed.driver=false\n" +
                "aeron.subscriber.cpu=3\n" +
                "aeron.publisher.cpu=4\n"
        );
        HftConfig cfg = HftConfig.load(props);

        assertEquals("aeron", cfg.pipelineMode());
        assertEquals(2002, cfg.aeronStreamId());
        assertFalse(cfg.aeronEmbedDriver());
        assertEquals(3, cfg.aeronSubscriberCpu());
        assertEquals(4, cfg.aeronPublisherCpu());
    }

    @Test
    void load_probesCanBeDisabled() throws IOException {
        Path props = write(
                "pipeline.mode=direct\n" +
                "latency.csv.parse=false\n" +
                "latency.aeron=false\n" +
                "latency.book.apply=false\n" +
                "latency.e2e=false\n"
        );
        HftConfig cfg = HftConfig.load(props);

        assertFalse(cfg.latencyCsvParse());
        assertFalse(cfg.latencyAeron());
        assertFalse(cfg.latencyBookApply());
        assertFalse(cfg.latencyE2e());
    }

    @Test
    void load_overriddenNumerics() throws IOException {
        Path props = write(
                "pipeline.mode=direct\n" +
                "book.max.orders=500000\n" +
                "book.max.price.levels=4096\n" +
                "book.max.products=512\n" +
                "benchmark.warmup.events=50000\n" +
                "output.snapshot.depth=10\n"
        );
        HftConfig cfg = HftConfig.load(props);

        assertEquals(500_000, cfg.bookMaxOrders());
        assertEquals(4096,    cfg.bookMaxPriceLevels());
        assertEquals(512,     cfg.bookMaxProducts());
        assertEquals(50_000,  cfg.benchmarkWarmupEvents());
        assertEquals(10,      cfg.outputSnapshotDepth());
    }

    @Test
    void load_inputCsvPath() throws IOException {
        Path props = write(
                "pipeline.mode=direct\n" +
                "input.csv.path=data/sample.csv\n"
        );
        HftConfig cfg = HftConfig.load(props);

        assertNotNull(cfg.inputCsvPath());
        assertEquals(Path.of("data/sample.csv"), cfg.inputCsvPath());
    }

    @Test
    void load_inputCsvPathMissing_returnsNull() throws IOException {
        Path props = write("pipeline.mode=direct\n");
        HftConfig cfg = HftConfig.load(props);

        assertNull(cfg.inputCsvPath());
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void load_missingPipelineMode_throws() {
        assertThrows(IllegalArgumentException.class, () -> {
            Path props = write("book.max.orders=1000000\n");
            HftConfig.load(props);
        });
    }

    @Test
    void load_invalidPipelineMode_throws() {
        assertThrows(IllegalArgumentException.class, () -> {
            Path props = write("pipeline.mode=kafka\n");
            HftConfig.load(props);
        });
    }
}

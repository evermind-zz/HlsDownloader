package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HlsMediaProcessorTest {
    private Path tempDir;
    private String outputDir;
    private String outputFile;
    private String stateFile;
    private HlsMediaProcessor downloader;
    private HlsParser parser;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test_hls_downloader");
        outputDir = tempDir.toString();
        outputFile = tempDir.resolve("output.ts").toString();
        stateFile = tempDir.resolve("download_state.txt").toString();

        String playlistContent = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key.key\",IV=0xabcdef\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:9.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key.key\",IV=0x123456\n" +
                "#EXTINF:9.0,\n" +
                "segment3.ts\n" +
                "#EXT-X-ENDLIST";

        parser = new HlsParser(
                variants -> {
                    fail("Should not be called for media playlist");
                    return null;
                },
                new MockDownloader(playlistContent),
                true
        );

        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new TestSegmentDownloader(),
                (progress, total) -> {},
                () -> {}
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    void testSuccessfulDownload() throws IOException {
        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)), "Output file should exist");
        assertFalse(Files.exists(Path.of(stateFile)), "State file should be cleaned up");
        assertEquals(0, countSegmentFiles(), "No segment files should remain after combining");
        assertTrue(Files.size(Path.of(outputFile)) > 0, "Output file should have content");
    }

    @Test
    void testPauseAndResume() throws IOException, InterruptedException {
        CountDownLatch firstSegmentLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicInteger segmentCounter = new AtomicInteger(0);

        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new TestSegmentDownloader(),
                (progress, total) -> {
                    if (segmentCounter.incrementAndGet() == 1) {
                        downloader.pause(); // Pause immediately after first segment
                        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
                        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist yet");
                        firstSegmentLatch.countDown(); // Signal test thread
                    }
                },
                () -> completionLatch.countDown()
        );

        Thread downloadThread = new Thread(() -> {
            try {
                downloader.download(URI.create("http://test/media.m3u8"));
            } catch (IOException e) {
                fail("Download should complete successfully: " + e.getMessage());
            }
        });

        downloadThread.start();

        assertTrue(firstSegmentLatch.await(5, TimeUnit.SECONDS), "First segment should be downloaded within 5 seconds");
        downloader.resume();

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Download should complete within 5 seconds");

        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(0, countSegmentFiles());
        assertTrue(Files.size(Path.of(outputFile)) > 0);
    }

    @Test
    void testCancelDuringDownload() throws IOException, InterruptedException {
        CountDownLatch firstSegmentLatch = new CountDownLatch(1);
        AtomicInteger segmentCounter = new AtomicInteger(0);

        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new TestSegmentDownloader(),
                (progress, total) -> {
                    if (segmentCounter.incrementAndGet() == 1) {
                        downloader.cancel(); // Cancel immediately after first segment
                        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
                        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist yet");
                        firstSegmentLatch.countDown(); // Signal test thread
                    }
                },
                () -> {}
        );

        Thread downloadThread = new Thread(() -> {
            try {
                downloader.download(URI.create("http://test/media.m3u8"));
            } catch (IOException e) {
                // Expected due to cancel
            }
        });
        downloadThread.start();

        assertTrue(firstSegmentLatch.await(5, TimeUnit.SECONDS), "First segment should be downloaded within 5 seconds");
        downloadThread.join();

        assertFalse(Files.exists(Path.of(outputFile)));
        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")));
        assertTrue(Files.exists(Path.of(stateFile)));
        assertEquals(0, readStateFile(), "State should reflect last completed index");
    }

    @Test
    void testResumeFromSavedState() throws IOException {
        Files.writeString(Path.of(stateFile), "1");
        downloader.lastDownloadedSegmentIndex = 1;

        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(0, countSegmentFiles());
        assertTrue(Files.size(Path.of(outputFile)) > 0);
    }

    @Test
    void testIOExceptionDuringDownload() throws IOException {
        MockDownloader mockDownloader = new MockDownloader("#EXTM3U\n#EXTINF:9.0,\nsegment1.ts\n#EXTINF:9.0,\n") {
            @Override
            public String download(URI uri) {
                throw new RuntimeException("Simulated network error"); // Changed to IOException for consistency
            }
        };
        parser = new HlsParser(
                variants -> null,
                mockDownloader,
                true
        );
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new TestSegmentDownloader(),
                (progress, total) -> {},
                () -> {}
        );

        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Simulated network error"));
        }

        assertFalse(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(outputDir + "/segment_1.ts")));
        assertTrue(Files.exists(Path.of(stateFile)));
        assertEquals(-1, readStateFile());
    }

    private int countSegmentFiles() throws IOException {
        return (int) Files.list(Path.of(outputDir))
                .filter(p -> p.getFileName().toString().startsWith("segment_"))
                .count();
    }

    private int readStateFile() throws IOException {
        return Files.exists(Path.of(stateFile)) ? Integer.parseInt(Files.readString(Path.of(stateFile)).trim()) : -1;
    }

    private static class TestSegmentDownloader implements HlsMediaProcessor.SegmentDownloader {
        protected final AtomicInteger segmentCounter = new AtomicInteger(0);

        @Override
        public InputStream download(URI uri) throws IOException {
            int segmentNum = segmentCounter.incrementAndGet();
            if (segmentNum > 3) throw new IOException("Too many segments");
            byte[] data = new byte[1024];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (segmentNum + i);
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public void disconnect() {
            // No-op for test
        }
    }

    private static class MockDownloader implements HlsParser.Downloader {
        final String content;

        MockDownloader(String content) {
            this.content = content;
        }

        @Override
        public String download(URI uri) {
            return content;
        }
    }
}

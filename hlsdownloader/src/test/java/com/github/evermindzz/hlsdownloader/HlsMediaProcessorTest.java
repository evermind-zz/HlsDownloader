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

        // Custom downloader with mocked segment downloads
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new TestSegmentDownloader(),
                (progress, total) -> {},
                () -> {}
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a)) // Delete in reverse order to handle directories
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    void testSuccessfulDownload() throws IOException {
        // Act
        downloader.download(URI.create("http://test/media.m3u8"));

        // Assert
        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile))); // State file should be cleaned up
        assertEquals(0, countSegmentFiles()); // Three segments downloaded
    }

    @Test
    void testPauseAndResume() throws IOException, InterruptedException {
        // Act
        Thread downloadThread = new Thread(() -> {
            try {
                downloader.download(URI.create("http://test/media.m3u8"));
            } catch (IOException e) {
                // Expected if paused
            }
        });
        downloadThread.start();

        // Pause after first segment
        Thread.sleep(100); // Allow some time for first segment to start
        downloader.pause();
        Thread.sleep(100); // Wait to ensure pause takes effect
        downloader.resume();

        // Wait for download to complete
        downloadThread.join();

        // Assert
        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(3, countSegmentFiles());
    }

    @Test
    void testCancelDuringDownload() throws IOException {
        // Act & Assert
        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException on cancel");
        } catch (IOException e) {
            downloader.cancel(); // Cancel after first segment attempt
        }

        // Assert
        assertFalse(Files.exists(Path.of(outputFile)));
        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")));
        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")));
        assertTrue(Files.exists(Path.of(stateFile))); // State should be saved
        assertEquals(0, readStateFile()); // Should reflect the last completed index
    }

    @Test
    void testResumeFromSavedState() throws IOException {
        // Arrange
        Files.writeString(Path.of(stateFile), "1");
        downloader.lastDownloadedSegmentIndex = 1; // Simulate one segment downloaded

        // Act
        downloader.download(URI.create("http://test/media.m3u8"));

        // Assert
        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(3, countSegmentFiles()); // Two new segments downloaded
    }

    @Test
    void testIOExceptionDuringDownload() throws IOException {
        // Arrange
        MockDownloader mockDownloader = new MockDownloader("#EXTM3U\n#EXTINF:9.0,\nsegment1.ts\n#EXTINF:9.0,\n") {
            @Override
            public String download(URI uri) {
                throw new RuntimeException("Simulated network error");
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

        // Act & Assert
        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Simulated network error"));
        }

        // Assert
        assertFalse(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(outputDir + "/segment_1.ts"))); // No segments due to parser failure
        assertTrue(Files.exists(Path.of(stateFile))); // State should be saved
        assertEquals(-1, readStateFile()); // No segments downloaded
    }

    // Helper methods
    private int countSegmentFiles() throws IOException {
        return (int) Files.list(Path.of(outputDir))
                .filter(p -> p.getFileName().toString().startsWith("segment_"))
                .count();
    }

    private int readStateFile() throws IOException {
        return Files.exists(Path.of(stateFile)) ? Integer.parseInt(Files.readString(Path.of(stateFile)).trim()) : -1;
    }

    // Custom SegmentDownloader for tests
    private static class TestSegmentDownloader implements HlsMediaProcessor.SegmentDownloader {
        private final AtomicInteger segmentCounter = new AtomicInteger(0);

        @Override
        public InputStream download(URI uri) throws IOException {
            int segmentNum = segmentCounter.incrementAndGet();
            if (segmentNum > 3) throw new IOException("Too many segments");
            // Simulate segment data
            byte[] data = ("Segment " + segmentNum).getBytes();
            return new ByteArrayInputStream(data);
        }

        @Override
        public void disconnect() {
            // No-op for test
        }
    }

    // MockDownloader for playlist
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

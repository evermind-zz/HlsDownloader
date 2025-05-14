package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
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

        downloader = new HlsMediaProcessor(
                parser,
                outputDir,
                outputFile,
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
        assertEquals(27.0, getFileSizeInSeconds(), 0.1); // 3 segments * 9 seconds each
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
        assertEquals(27.0, getFileSizeInSeconds(), 0.1);
    }

    @Test
    void testCancelDuringDownload() throws IOException {
        // Act & Assert
        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException on cancel");
        } catch (IOException e) {
            // Cancel manually after first segment
            downloader.cancel();
        }

        // Assert
        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")));
        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")));
        assertFalse(Files.exists(Path.of(outputFile)));
        assertTrue(Files.exists(Path.of(stateFile))); // State should be saved
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
        assertEquals(18.0, getFileSizeInSeconds(), 0.1); // 2 segments * 9 seconds each
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
        downloader = new HlsMediaProcessor(
                parser,
                outputDir,
                outputFile,
                (progress, total) -> {},
                () -> {}
        );

        // Act & Assert
        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Simulated network error"));
        }

        // Assert
        assertFalse(Files.exists(Path.of(outputFile)));
        assertTrue(Files.exists(Path.of(stateFile))); // State should be saved
    }

    // Helper method to estimate file size based on segment duration (simplified)
    private double getFileSizeInSeconds() throws IOException {
        return Files.size(Path.of(outputFile)) / (1024 * 1024); // Rough estimate in MB, assuming 1MB ~ 9s
    }

    // MockDownloader implementation
    static class MockDownloader implements HlsParser.Downloader {
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

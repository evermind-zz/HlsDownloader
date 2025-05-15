package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static HlsParser parser;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test_hls_downloader");
        outputDir = tempDir.toString();
        outputFile = tempDir.resolve("output.ts").toString();
        stateFile = tempDir.resolve("download_state.txt").toString();

        // Playlist with encrypted segments and a key change
        String playlistContent = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key1.key\",IV=0xabcdef\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:9.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key2.key\",IV=0x123456\n" +
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
                new DecryptingSegmentDownloader(outputDir),
                null, // Use default StateManager
                null, // Use default SegmentCombiner
                (progress, total) -> {},
                () -> {},
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
                new DecryptingSegmentDownloader(outputDir),
                null, // Use default StateManager
                null, // Use default SegmentCombiner
                (progress, total) -> {
                    if (segmentCounter.incrementAndGet() == 1) {
                        downloader.pause(); // Pause immediately after first segment
                        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
                        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist yet");
                        firstSegmentLatch.countDown(); // Signal test thread
                    }
                },
                () -> completionLatch.countDown(),
                () -> {}
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
                new DecryptingSegmentDownloader(outputDir),
                null, // Use default StateManager
                null, // Use default SegmentCombiner
                (progress, total) -> {
                    if (segmentCounter.incrementAndGet() == 1) {
                        downloader.cancel(); // Cancel immediately after first segment
                        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
                        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist yet");
                        firstSegmentLatch.countDown(); // Signal test thread
                    }
                },
                () -> {},
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
        // Setup: Simulate a partially completed download with lastDownloadedSegmentIndex = 1
        Files.writeString(Path.of(stateFile), "1");
        downloader.lastDownloadedSegmentIndex = 1;

        // Create segment_1.ts and segment_2.ts (for indices 0 and 1) to simulate existing segments
        for (int i = 1; i <= 2; i++) {
            String segmentFile = outputDir + "/segment_" + i + ".ts";
            byte[] data = new byte[1024];
            for (int j = 0; j < data.length; j++) {
                data[j] = (byte) (i + j); // Same logic as TestSegmentDownloader
            }
            try (FileOutputStream fos = new FileOutputStream(segmentFile)) {
                fos.write(data);
            }
        }

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
            public String download(URI uri) throws IOException {
                throw new IOException("Simulated network error");
            }
        };
        parser = new HlsParser(
                variants -> null,
                mockDownloader,
                true
        );
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new DecryptingSegmentDownloader(outputDir),
                null, // Use default StateManager
                null, // Use default SegmentCombiner
                (progress, total) -> {},
                () -> {},
                () -> {}
        );

        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Simulated network error"));
        }

        assertFalse(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(outputDir + "/segment_1.ts")));
        assertTrue(Files.exists(Path.of(stateFile))); // Exists due to saveState() after loadState()
        assertEquals(-1, readStateFile());
    }

    @Test
    void testDecryptSingleEncryptedSegment() throws IOException {
        // Prepare mock encrypted segment and key
        String segmentFile = outputDir + "/segment_1.ts";
        byte[] originalData = new byte[1024];
        for (int i = 0; i < originalData.length; i++) {
            originalData[i] = (byte) i; // Original unencrypted data
        }
        byte[] encryptedData = encryptMock(originalData); // Mock encryption (reverse bytes)
        try (FileOutputStream fos = new FileOutputStream(segmentFile)) {
            fos.write(encryptedData);
        }

        // Mock key downloader
        MockKeyDownloader keyDownloader = new MockKeyDownloader("key1".getBytes());
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new DecryptingSegmentDownloader(outputDir, keyDownloader),
                null,
                null,
                (progress, total) -> {},
                () -> {},
                () -> {}
        );

        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(0, countSegmentFiles());
        try (InputStream is = new FileInputStream(outputFile)) {
            byte[] decryptedData = is.readAllBytes();
            assertArrayEquals(originalData, decryptedData, "Decrypted data should match original data");
        }
    }

    @Test
    void testDecryptWithKeyChange() throws IOException {
        // Prepare mock encrypted segments and keys
        for (int i = 1; i <= 3; i++) {
            String segmentFile = outputDir + "/segment_" + i + ".ts";
            byte[] originalData = new byte[1024];
            for (int j = 0; j < originalData.length; j++) {
                originalData[j] = (byte) (i + j); // Unique data per segment
            }
            byte[] encryptedData = encryptMock(originalData); // Mock encryption
            try (FileOutputStream fos = new FileOutputStream(segmentFile)) {
                fos.write(encryptedData);
            }
        }

        // Mock key downloader with different keys
        MockKeyDownloader keyDownloader = new MockKeyDownloader(
                new byte[][] {"key1".getBytes(), "key1".getBytes(), "key2".getBytes()} // Key change at segment 3
        );
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new DecryptingSegmentDownloader(outputDir, keyDownloader),
                null,
                null,
                (progress, total) -> {},
                () -> {},
                () -> {}
        );

        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(0, countSegmentFiles());
        try (InputStream is = new FileInputStream(outputFile)) {
            byte[] combinedData = is.readAllBytes();
            byte[] expectedData = new byte[1024 * 3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 1024; j++) {
                    expectedData[i * 1024 + j] = (byte) (i + 1 + j);
                }
            }
            assertArrayEquals(expectedData, combinedData, "Decrypted data should match original data across key change");
        }
    }

    private int countSegmentFiles() throws IOException {
        return (int) Files.list(Path.of(outputDir))
                .filter(p -> p.getFileName().toString().startsWith("segment_"))
                .count();
    }

    private int readStateFile() throws IOException {
        return Files.exists(Path.of(stateFile)) ? Integer.parseInt(Files.readString(Path.of(stateFile)).trim()) : -1;
    }

    private byte[] encryptMock(byte[] data) {
        // Mock encryption: reverse bytes (placeholder for real AES-128)
        byte[] encrypted = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            encrypted[i] = (byte) (255 - data[i]); // Simple reversible transformation
        }
        return encrypted;
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

    private static class DecryptingSegmentDownloader implements HlsMediaProcessor.SegmentDownloader {
        private final MockKeyDownloader keyDownloader;
        private String outputDir;
        private int segmentIndex = 0;

        DecryptingSegmentDownloader(String outputDir) {
            this(outputDir, new MockKeyDownloader((byte[]) null));
        }

        DecryptingSegmentDownloader(String outputDir, MockKeyDownloader keyDownloader) {
            this.outputDir = outputDir;
            this.keyDownloader = keyDownloader != null ? keyDownloader : new MockKeyDownloader((byte[]) null);
        }

        @Override
        public InputStream download(URI uri) throws IOException {
            segmentIndex++;
            // Simulate fetching encryption info from HlsParser (mocked here)
            HlsParser.MediaPlaylist playlist = parser.parse(URI.create("http://test/media.m3u8"));
            HlsParser.EncryptionInfo encryptionInfo = playlist.getSegments().get(segmentIndex - 1).getEncryptionInfo();
            byte[] key = keyDownloader.download(encryptionInfo.getUri());

            // Read the encrypted segment file
            String segmentFile = outputDir + "/segment_" + segmentIndex + ".ts";
            byte[] encryptedData = Files.readAllBytes(Paths.get(segmentFile));

            // Mock decryption (reverse the mock encryption)
            byte[] decryptedData = new byte[encryptedData.length];
            for (int i = 0; i < encryptedData.length; i++) {
                decryptedData[i] = (byte) (255 - encryptedData[i]); // Reverse the mock encryption
            }

            return new ByteArrayInputStream(decryptedData);
        }

        @Override
        public void disconnect() {
            // No-op for test
        }
    }

    private static class MockKeyDownloader {
        private final byte[][] keys;

        MockKeyDownloader(byte[] key) {
            this.keys = key != null ? new byte[][] {key} : null;
        }

        MockKeyDownloader(byte[][] keys) {
            this.keys = keys;
        }

        public byte[] download(URI uri) throws IOException {
            if (keys == null) {
                return "defaultkey".getBytes(); // Default key if none provided
            }
            int index = Integer.parseInt(uri.getPath().replaceAll(".key", ""));
            return keys[Math.min(index, keys.length - 1)];
        }
    }

    private static class MockDownloader implements HlsParser.Downloader {
        final String content;

        MockDownloader(String content) {
            this.content = content;
        }

        @Override
        public String download(URI uri) throws IOException {
            return content;
        }
    }
}

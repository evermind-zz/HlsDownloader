package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class HlsMediaProcessorTest {
    private Path tempDir;
    private String outputDir;
    private String outputFile;
    private String stateFile;
    private HlsMediaProcessor downloader;
    private HlsParser parser;

    // Playlist content definitions
    private static final String DEFAULT_PLAYLIST = "#EXTM3U\n" +
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
    private static final int DEFAULT_PLAYLIST_SEGMENTS = 3;
    private static final String TWO_SEGMENT_PLAYLIST = "#EXTM3U\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXTINF:9.0,\n" +
            "segment1.ts\n" +
            "#EXTINF:9.0,\n" +
            "segment2.ts\n" +
            "#EXT-X-ENDLIST";
    private static final String SINGLE_SEGMENT_PLAYLIST = "#EXTM3U\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key1.key\",IV=0xabcdef\n" +
            "#EXTINF:9.0,\n" +
            "segment1.ts\n" +
            "#EXT-X-ENDLIST";

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test_hls_downloader");
        outputDir = tempDir.toString();
        outputFile = tempDir.resolve("output.ts").toString();
        stateFile = tempDir.resolve("download_state.txt").toString();

        // Default setup with a three-segment playlist
        initHls(DEFAULT_PLAYLIST);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .map(Path::toFile)
                .forEach(File::delete);
    }

    private void initHls(String playlistContent) {
        initHls(playlistContent, new MockFetcher(playlistContent), new MockDecryptor(), 2);
    }

    private void initHls(String playlistContent, HlsParser.Fetcher fetcher, HlsMediaProcessor.Decryptor decryptor, int numThreads) {
        parser = new HlsParser(
                variants -> {
                    fail("Should not be called for media playlist");
                    return null;
                },
                fetcher,
                true
        );
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                fetcher, decryptor,
                numThreads,
                new FileSegmentStateManager(stateFile),
                null, // Use default SegmentCombiner
                (progress, total) -> {},
                (state, message) -> {},
                () -> {}
        );
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
    void testCancelDuringDownload() throws InterruptedException {
        CountDownLatch firstSegmentLatch = new CountDownLatch(1);
        AtomicInteger segmentCounter = new AtomicInteger(0);
        AtomicReference<HlsMediaProcessor.DownloadState> lastState = new AtomicReference<>();
        AtomicReference<String> lastMessage = new AtomicReference<>();

        initHls(TWO_SEGMENT_PLAYLIST);

        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new MockFetcher(TWO_SEGMENT_PLAYLIST),
                new MockDecryptor(),
                2,
                new FileSegmentStateManager(stateFile),
                null, // Use default SegmentCombiner
                (progress, total) -> {
                    int count = segmentCounter.incrementAndGet();
                    if (count == 1) {
                        downloader.cancel(); // Cancel after first segment
                        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
                        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist yet");
                        firstSegmentLatch.countDown(); // Signal test thread
                    } else if (count > 1) {
                        fail("No additional segments should be downloaded after cancellation");
                    }
                },
                (state, message) -> {
                    lastState.set(state);
                    lastMessage.set(message);
                },
                () -> {}
        );

        Thread downloadThread = new Thread(() -> downloader.download(URI.create("http://test/media.m3u8")));
        downloadThread.start();

        assertTrue(firstSegmentLatch.await(5, TimeUnit.SECONDS), "First segment should be downloaded within 5 seconds");
        downloadThread.join(2000); // Allow thread cleanup

        assertEquals(HlsMediaProcessor.DownloadState.CANCELLED, lastState.get(), "Should notify cancellation");
        assertEquals("Cancelled by user", lastMessage.get(), "Should provide cancellation reason");
        assertFalse(Files.exists(Path.of(outputFile)));
        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist");
        Set<Integer> state = new FileSegmentStateManager(stateFile).loadState();
        assertEquals(Collections.singleton(0), state, "State should reflect only the completed segment (index 0)");
        Files.deleteIfExists(Path.of(outputDir + "/segment_1.ts"));
    }

    @Test
    void testDecryptWithKeyChange() throws IOException {
        AtomicInteger keyFetchCount = new AtomicInteger(0);
        MockFetcher fetcher = new MockFetcher(DEFAULT_PLAYLIST) {
            @Override
            public InputStream fetchContent(URI uri) throws IOException {
                if (uri != null && uri.toString().contains("key")) {
                    keyFetchCount.incrementAndGet();
                }
                return super.fetchContent(uri);
            }
        };
        MockDecryptor decryptor = new MockDecryptor(new byte[][] {"key1".getBytes(), "key1".getBytes(), "key2".getBytes()});

        initHls(DEFAULT_PLAYLIST, fetcher, decryptor, 2);

        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(0, countSegmentFiles());
        try (InputStream is = new FileInputStream(outputFile)) {
            byte[] combinedData = is.readAllBytes();
            byte[] expectedData = new byte[1024 * DEFAULT_PLAYLIST_SEGMENTS];
            for (int i = 0; i < DEFAULT_PLAYLIST_SEGMENTS; i++) {
                for (int j = 0; j < 1024; j++) {
                    expectedData[i * 1024 + j] = (byte) (i + j);
                }
            }
            assertArrayEquals(expectedData, combinedData, "Decrypted data should match original data across key change");
        }
        assertEquals(2, keyFetchCount.get(), "Each unique key should be fetched exactly once during pre-fetching");
    }

    @Test
    void testSegmentFileOverwrites() throws InterruptedException {
        // Pre-create a segment file with different content
        String segmentFile = outputDir + "/segment_1.ts";
        try {
            Files.writeString(Path.of(segmentFile), "old content");
        } catch (IOException e) {
            fail("Failed to create initial segment file: " + e.getMessage());
        }

        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new MockFetcher(TWO_SEGMENT_PLAYLIST),
                new MockDecryptor(),
                2,
                new FileSegmentStateManager(stateFile),
                null,
                (progress, total) -> {},
                (state, message) -> {},
                () -> {}
        );

        Thread downloadThread = new Thread(() -> downloader.download(URI.create("http://test/media.m3u8")));
        downloadThread.start();
        downloadThread.join(5000); // Allow download to complete

        // Verify the segment file was overwritten with new content
        try (InputStream is = new FileInputStream(segmentFile)) {
            byte[] content = is.readAllBytes();
            byte[] expectedData = new byte[1024];
            for (int j = 0; j < 1024; j++) {
                expectedData[j] = (byte) (0 + j); // Segment 1 content
            }
            assertArrayEquals(expectedData, content, "Segment file should be overwritten with new content");
        } catch (IOException e) {
            fail("Failed to read segment file: " + e.getMessage());
        }
    }

    private int countSegmentFiles() throws IOException {
        return (int) Files.list(Path.of(outputDir))
                .filter(p -> p.getFileName().toString().startsWith("segment_"))
                .count();
    }

    private static class FileSegmentStateManager implements HlsMediaProcessor.SegmentStateManager {
        private final String stateFile;

        public FileSegmentStateManager(String stateFile) {
            this.stateFile = stateFile;
        }

        @Override
        public Set<Integer> loadState() throws IOException {
            if (Files.exists(Path.of(stateFile))) {
                String content = Files.readString(Path.of(stateFile)).trim();
                return content.isEmpty() ? new HashSet<>() : Arrays.stream(content.split(","))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .collect(Collectors.toSet());
            }
            return new HashSet<>();
        }

        @Override
        public void saveState(Set<Integer> completedIndices) throws IOException {
            Files.writeString(Path.of(stateFile), completedIndices.stream()
                    .sorted()
                    .map(Object::toString)
                    .collect(Collectors.joining(",")));
        }

        @Override
        public void cleanupState() throws IOException {
            Files.deleteIfExists(Path.of(stateFile));
        }
    }

    private static class MockFetcher implements HlsParser.Fetcher {
        final String content;
        protected final AtomicInteger segmentCounter = new AtomicInteger(0);

        MockFetcher(String content) {
            this.content = content;
        }

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            if (uri.getPath().contains("segment")) {
                int segmentNum = segmentCounter.getAndIncrement();
                byte[] data = new byte[1024];
                for (int i = 0; i < data.length; i++) {
                    data[i] = reverseByte((byte) (segmentNum + i)); // mock encryption
                }
                return new ByteArrayInputStream(data);
            } else {
                return new ByteArrayInputStream(content.getBytes());
            }
        }

        @Override
        public void disconnect() {
            // No-op for test
        }
    }

    private static class MockDecryptor implements HlsMediaProcessor.Decryptor {
        private final byte[][] keys;

        MockDecryptor() {
            this.keys = null;
        }

        MockDecryptor(byte[][] keys) {
            this.keys = keys;
        }

        @Override
        public InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo) throws IOException {
            byte[] encryptedData = encryptedStream.readAllBytes();
            byte[] decryptedData = new byte[encryptedData.length];
            if (keys != null) {
                int index = Integer.parseInt(encryptionInfo.getUri().getPath().replaceAll(".*/key(\\d+)\\.key", "$1")) - 1;
                key = keys[Math.min(index, keys.length - 1)];
            }
            for (int i = 0; i < encryptedData.length; i++) {
                decryptedData[i] = reverseByte(encryptedData[i]); // Reverse mock encryption
            }
            return new ByteArrayInputStream(decryptedData);
        }
    }

    static byte reverseByte(byte b) {
        return (byte) (Integer.reverse(b) >>> (Integer.SIZE - Byte.SIZE));
    }
}

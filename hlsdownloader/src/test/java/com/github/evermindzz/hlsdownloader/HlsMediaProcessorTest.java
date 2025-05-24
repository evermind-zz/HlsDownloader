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
import java.net.SocketException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
            "#EXT-X-VERSION:2\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key1.key\",IV=0xabcdef1234567890abcdef1234567890\n" +
            "#EXTINF:9.0,\n" +
            "segment1.ts\n" +
            "#EXTINF:9.0,\n" +
            "segment2.ts\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key2.key\",IV=0x1234567890abcdef1234567890abcdef\n" +
            "#EXTINF:9.0,\n" +
            "segment3.ts\n" +
            "#EXT-X-ENDLIST";
    private static final int DEFAULT_PLAYLIST_SEGMENTS = 3;
    private static final String TWO_SEGMENT_PLAYLIST = "#EXTM3U\n" +
            "#EXT-X-VERSION:2\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXTINF:9.0,\n" +
            "segment1.ts\n" +
            "#EXTINF:9.0,\n" +
            "segment2.ts\n" +
            "#EXT-X-ENDLIST";
    private static final String SINGLE_SEGMENT_PLAYLIST = "#EXTM3U\n" +
            "#EXT-X-VERSION:2\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key1.key\",IV=0xabcdef1234567890abcdef1234567890\n" +
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
        //initHls(DEFAULT_PLAYLIST);
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
                new HlsMediaProcessor.DefaultSegmentStateManager(stateFile),
                null, // Use default SegmentCombiner
                (progress, total) -> {},
                (state, message) -> {});
    }

    @Test
    void testSuccessfulDownload() throws IOException {
        initHls(DEFAULT_PLAYLIST);
        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)), "Output file should exist");
        assertFalse(Files.exists(Path.of(stateFile)), "State file should be cleaned up");
        assertEquals(0, countSegmentFiles(), "No segment files should remain after combining");
        assertTrue(Files.size(Path.of(outputFile)) > 0, "Output file should have content");
    }

    @Test
    void testCancelDuringDownload() throws InterruptedException, IOException {
        // Create a CyclicBarrier with 3 parties: 2 download threads + 1 test thread
        CyclicBarrier barrier = new CyclicBarrier(3);
        AtomicInteger segmentCounter = new AtomicInteger(0);
        AtomicReference<HlsMediaProcessor.DownloadState> prevState = new AtomicReference<>();
        AtomicReference<HlsMediaProcessor.DownloadState> lastState = new AtomicReference<>();
        AtomicReference<String> prevMessage = new AtomicReference<>();
        AtomicReference<String> lastMessage = new AtomicReference<>();

        parser = new HlsParser( null,
                new MockFetcher(TWO_SEGMENT_PLAYLIST, null),
                true
        );
        HlsMediaProcessor.DownloadStateCallback callback = new DownloadStateLogger();
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new MockFetcher(TWO_SEGMENT_PLAYLIST, barrier), // Pass the barrier to MockFetcher
                new MockDecryptor(),
                2, // Use 2 threads to simulate concurrency
                new HlsMediaProcessor.DefaultSegmentStateManager(stateFile),
                null, // Use default SegmentCombiner
                (progress, total) -> {
                    int count = segmentCounter.incrementAndGet();
                    if (count == 1) {
                        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
                        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist yet");
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            fail("Barrier await failed: " + e.getMessage());
                        }
                        // At this point, both threads are at the barrier (one has written segment_1.ts, the other is waiting)
                        downloader.cancel(); // Cancel the download
                    }
                },
                (state, message) -> {
                    prevState.set(lastState.get());
                    prevMessage.set(lastMessage.get());
                    lastState.set(state);
                    lastMessage.set(message);
                    callback.onDownloadState(state, message);
                });

        Thread downloadThread = new Thread(() -> {
            try {
                downloader.download(URI.create("http://test/media.m3u8"));
            } catch (IOException e) {
                // Ignore expected InterruptedIOException due to cancellation
                if (!e.getMessage().contains("Download cancelled")) {
                    fail("Unexpected IOException: " + e.getMessage());
                }
            }
        });
        downloadThread.setName("MainThread");
        downloadThread.start();

        // Test thread waits at the barrier for both download threads to reach the fetch point
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Barrier await failed: " + e.getMessage());
        }

        // At this point, both threads are at the barrier (one has written segment_1.ts, the other is waiting)
        //downloader.cancel(); // Cancel the download

        downloadThread.join(2000); // Allow cancellation to complete

        assertEquals(HlsMediaProcessor.DownloadState.CANCELLED, prevState.get(), "Should notify cancellation");
        assertEquals(HlsMediaProcessor.DownloadState.STOPPED, lastState.get(), "Should notify stopped");
        assertEquals("Cancelled by user", prevMessage.get(), "Should provide cancellation reason");
        assertEquals("All operations stopped. EOW", lastMessage.get(), "Should provide stopped info");
        assertFalse(Files.exists(Path.of(outputFile)), "Output file should not exist after cancellation");
        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist");
        assertTrue(Files.exists(Path.of(stateFile)), "State file should exist");
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
    void testSegmentFileOverwrites() throws InterruptedException, IOException {
        initHls(DEFAULT_PLAYLIST);
        // Pre-create a segment file with different content
        String segmentFile = outputDir + "/segment_1.ts";
        try {
            Files.writeString(Path.of(segmentFile), "old content");
        } catch (IOException e) {
            fail("Failed to create initial segment file: " + e.getMessage());
        }

        CyclicBarrier barrier = new CyclicBarrier(3);
        AtomicInteger segmentCounter = new AtomicInteger(0);

        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new MockFetcher(TWO_SEGMENT_PLAYLIST, barrier),
                new MockDecryptor(),
                2, // Use 2 threads to simulate concurrency
                new HlsMediaProcessor.DefaultSegmentStateManager(stateFile),
                null, // Use default SegmentCombiner
                (progress, total) -> {
                    int count = segmentCounter.incrementAndGet();
                    if (count == 1) {
                        assertTrue(Files.exists(Path.of(outputDir + "/segment_1.ts")), "First segment should exist");
                        assertFalse(Files.exists(Path.of(outputDir + "/segment_2.ts")), "Second segment should not exist yet");
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            fail("Barrier await failed: " + e.getMessage());
                        }

                        downloader.cancel(); // Cancel the download
                    }
                },
                (state, message) -> {});

        Thread downloadThread = new Thread(() -> {
            try {
                downloader.download(URI.create("http://test/media.m3u8"));
            } catch (IOException e) {
                // Ignore expected InterruptedIOException due to cancellation
                if (!e.getMessage().contains("Download cancelled")) {
                    fail("Unexpected IOException: " + e.getMessage());
                }
            }
        });
        downloadThread.start();

        // Test thread waits at the barrier for both download threads to reach the fetch point
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Barrier await failed: " + e.getMessage());
        }

        downloadThread.join(2000); // Allow cancellation to complete

        // Verify the segment file was overwritten with new content
        try (InputStream is = new FileInputStream(segmentFile)) {
            byte[] content = is.readAllBytes();
            byte[] expectedData = new byte[1024];
            for (int j = 0; j < 1024; j++) {
                expectedData[j] = (byte) j; // Segment 1 content
            }
            assertArrayEquals(expectedData, content, "Segment file should be overwritten with new content");
        } catch (IOException e) {
            fail("Failed to read segment file: " + e.getMessage());
        }
    }

    @Test
    void testErrorOnEmptyPlaylist() throws IOException {
        String emptyPlaylist = "#EXTM3U\n#EXT-X-ENDLIST";
        initHls(emptyPlaylist);
        AtomicReference<HlsMediaProcessor.DownloadState> lastState = new AtomicReference<>();
        AtomicReference<String> lastMessage = new AtomicReference<>();

        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                new MockFetcher(emptyPlaylist),
                new MockDecryptor(),
                2,
                new HlsMediaProcessor.DefaultSegmentStateManager(stateFile),
                null,
                (progress, total) -> {},
                (state, message) -> {
                    lastState.set(state);
                    lastMessage.set(message);
                });

        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException for empty playlist");
        } catch (IOException e) {
            assertEquals(HlsMediaProcessor.DownloadState.ERROR, lastState.get(), "Should notify ERROR state");
            String msg = "No segments found in media playlist";
            assertTrue(lastMessage.get().contains(msg), "Should provide correct error message but was: " + lastMessage.get());
            assertTrue(e.getMessage().contains(msg), "Should provide correct error message but was: " + e.getMessage());
        }
    }

    private int countSegmentFiles() throws IOException {
        return (int) Files.list(Path.of(outputDir))
                .filter(p -> p.getFileName().toString().startsWith("segment_"))
                .count();
    }

    private static class MockFetcher implements HlsParser.Fetcher {
        final String content;
        protected final AtomicInteger segmentCounter = new AtomicInteger(0);
        private final CyclicBarrier barrier;

        MockFetcher(String content) {
            this(content, null);
        }

        MockFetcher(String content, CyclicBarrier barrier) {
            this.content = content;
            this.barrier = barrier;
        }

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            if (uri.getPath().contains("segment")) {
                int segmentNum = segmentCounter.getAndIncrement();
                byte[] data = new byte[1024];
                for (int i = 0; i < data.length; i++) {
                    data[i] = reverseByte((byte) (segmentNum + i)); // mock encryption
                }
                // Synchronize threads at the barrier before proceeding
                if (barrier != null && segmentNum == 1) {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IOException("Barrier await failed: " + e.getMessage());
                    }
                }
                return new ByteArrayInputStream(data);
            } else if (uri.toString().contains("key")) {
                return new ByteArrayInputStream("1234567890abcdef".getBytes());
            }
            return new ByteArrayInputStream(content.getBytes());
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
        public InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo, int segmentIndex) throws IOException, GeneralSecurityException {
            byte[] encryptedData = encryptedStream.readAllBytes(); // For testing, simulate decryption
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

    // disabled @Test
    void testWithActualData() throws IOException {
        String localTestUri = "http://localhost:2002/input_hls/playlist.m3u8";
        //String localTestUri = "https://1a-1791.com/video/fww1/60/s8/2/V/J/m/J/VJmJy.haa.rec.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=434384896-434393686";
        outputFile += ".mp4";
        HlsParser.Fetcher defaultFetcher = new HlsMediaProcessor.DefaultFetcher();
        parser = new HlsParser(null, defaultFetcher, true);
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                defaultFetcher, null,
                2,
                new HlsMediaProcessor.DefaultSegmentStateManager(stateFile),
                new FFmpegSegmentCombiner(),
                (progress, total) -> {},
                (state, message) -> {});

        downloader.download(URI.create(localTestUri));

        assertTrue(Files.exists(Path.of(outputFile)));
        assertFalse(Files.exists(Path.of(stateFile)));
        assertEquals(0, countSegmentFiles());
    }

    @Test
    void testDisconnectOnFailure() throws IOException {
        MockFetcher failingFetcher = new MockFetcher(DEFAULT_PLAYLIST) {
            @Override
            public InputStream fetchContent(URI uri) throws IOException {
                if (uri.toString().contains("key")) {
                    throw new IOException("Simulated connection failure");
                }
                return super.fetchContent(uri);
            }
        };
        initHls(DEFAULT_PLAYLIST, failingFetcher, new MockDecryptor(), 2);

        try {
            downloader.download(URI.create("http://test/media.m3u8"));
            fail("Should throw IOException due to simulated failure");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Simulated connection failure"), "Should catch the simulated failure but was: " + e.getMessage());
        }
    }

    @Test
    void testRetryOnSocketException() throws IOException {
        MockFetcher socketFailingFetcher = new MockFetcher(SINGLE_SEGMENT_PLAYLIST) {
            private int attemptCount = 0;

            @Override
            public InputStream fetchContent(URI uri) throws IOException {
                if (uri.getPath().contains("segment") && attemptCount < 2) {
                    attemptCount++;
                    throw new SocketException("Simulated socket closure");
                }
                return super.fetchContent(uri);
            }
        };
        initHls(SINGLE_SEGMENT_PLAYLIST, socketFailingFetcher, new MockDecryptor(), 1);

        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)), "Output file should exist after retries");
        assertFalse(Files.exists(Path.of(stateFile)), "State file should be cleaned up");
        assertEquals(0, countSegmentFiles(), "No segment files should remain after combining");
    }

    @Test
    void testStreamingDecryption() throws IOException {
        // test data
        HashMap<Integer, HlsMediaProcessorDecryptorTest.TestData> testDataMap = new HashMap<>();
        int segmentIndex = 1;
        testDataMap.put(segmentIndex, new HlsMediaProcessorDecryptorTest.TestData("AES/CBC/PKCS5Padding", "1234567890abcdef","0xabcdef1234567890abcdef1234567890", "https://example.com/key1.key", "http://test/segment1.ts"));

        // setup parser and downloader
        HlsMediaProcessorDecryptorTest.MockEncFetcher defaultFetcher = new HlsMediaProcessorDecryptorTest.MockEncFetcher(testDataMap, SINGLE_SEGMENT_PLAYLIST);

        parser = new HlsParser(null, defaultFetcher, true);
        downloader = new HlsMediaProcessor(parser, outputDir, outputFile,
                defaultFetcher, new HlsMediaProcessor.DefaultDecryptor(),
                1,
                new HlsMediaProcessor.DefaultSegmentStateManager(stateFile),
                null,
                (progress, total) -> {},
                (state, message) -> {});

        downloader.download(URI.create("http://test/media.m3u8"));

        assertTrue(Files.exists(Path.of(outputFile)), "Output file should exist");

        // as we have one single segment outputFile has toe be same like segment1.ts
        try (InputStream is = new FileInputStream(outputFile)) {
            byte[] combinedData = is.readAllBytes();
            byte[] expectedData = defaultFetcher.generateSegmentData(segmentIndex);

            byte[] encryptedData = defaultFetcher.getEncryptedSegmentData(segmentIndex, expectedData).readAllBytes();
            assertNotEquals(encryptedData[0], combinedData[0], "the data should differ as first is encrypted and 2nd should be plain");
            assertArrayEquals(expectedData, combinedData, "Decrypted data should match original data across key change");
        }
        assertFalse(Files.exists(Path.of(stateFile)), "State file should be cleaned up");
        assertEquals(0, countSegmentFiles(), "No segment files should remain");
    }

    private class DownloadStateLogger implements HlsMediaProcessor.DownloadStateCallback {

        @Override
        public void onDownloadState(HlsMediaProcessor.DownloadState state, String message) {
            SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            String timestamp = TIMESTAMP_FORMAT.format(new Date());
            // DBG only System.out.println(state.name() + " " + timestamp +  " " + message + " |--> " + Thread.currentThread().getName() + " " + Arrays.toString(Thread.currentThread().getStackTrace()).replace( ',', '\n' ));
        }
    }
}

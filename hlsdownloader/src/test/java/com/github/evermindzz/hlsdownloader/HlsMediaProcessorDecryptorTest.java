package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

// especially test the decryption via HlsMediaProcessor.processSegment()
class HlsMediaProcessorDecryptorTest {
    private Path tempDir;
    private String outputDir;
    private HlsMediaProcessor downloader;
    private HlsParser parser;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test_hls_decryptor");
        outputDir = tempDir.toString();
        setupHLS(null, null);
    }

    private void setupHLS(HlsParser.Fetcher parserFetcher, HlsParser.Fetcher downloadFetcher) {
        parser = new HlsParser(
                variants -> null,
                (parserFetcher != null ? parserFetcher : new MockEncFetcher()),
                true
        );

        downloader = new HlsMediaProcessor(parser, outputDir, tempDir.resolve("output.ts").toString(),
                (downloadFetcher != null ? downloadFetcher : new MockEncFetcher()),
                new HlsMediaProcessor.DefaultDecryptor(),
                1, new HlsMediaProcessor.DefaultSegmentStateManager(outputDir + "/download_state.txt"),
                null, (progress, total) -> {}, (state, message) -> {}, false);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    void testDecryptWithExplicitIv() throws IOException {
        // test data
        HashMap<Integer, TestData>  testDataMap = new HashMap<>();
        testDataMap.put(1, new TestData("AES/CBC/PKCS5Padding", "1234567890abcdef","0xabcdefabcdefabcdefabcdefabcdefab", "https://example.com/key1.key", "http://test/segment1.ts"));

        // setup parser and downloader
        MockEncFetcher downloadFetcher = new MockEncFetcher(testDataMap);
        setupHLS(null, downloadFetcher);

        // Test with explicit IV from playlist
        int segmentIndex = 1;
        HlsParser.Segment segment = new HlsParser.Segment(URI.create(testDataMap.get(segmentIndex).segmentUrl),
                10,
                "title",
                new HlsParser.EncryptionInfo("AES-128", URI.create(testDataMap.get(segmentIndex).keyUrl), testDataMap.get(segmentIndex).iv));
        segment.getEncryptionInfo().setKey(testDataMap.get(segmentIndex).key.getBytes());

        try (InputStream decryptedStream = downloader.processSegment(segment, segmentIndex)) {
            byte[] decryptedData = decryptedStream.readAllBytes();
            byte[] expectedData = new byte[1024];
            for (int i = 0; i < expectedData.length; i++) {
                expectedData[i] = (byte) (i + segmentIndex);
            }
            assertArrayEquals(expectedData, decryptedData, "Decrypted data should match expected plain data with explicit IV");
        }
    }

    @Test
    void testDecryptWithDefaultIv() throws IOException {
        // test data
        HashMap<Integer, TestData>  testDataMap = new HashMap<>();
        testDataMap.put(2, new TestData("AES/CBC/PKCS5Padding", "fedcba0987654321",null, "https://example.com/key2.key", "http://test/segment2.ts"));

        // setup parser and downloader
        MockEncFetcher downloadFetcher = new MockEncFetcher(testDataMap);
        setupHLS(null, downloadFetcher);

        // Test with default IV (segment index)
        int segmentIndex = 2;
        HlsParser.Segment segment = new HlsParser.Segment(URI.create(testDataMap.get(segmentIndex).segmentUrl),
                10,
                "title",
                new HlsParser.EncryptionInfo("AES-128", URI.create(testDataMap.get(segmentIndex).keyUrl), null));
        segment.getEncryptionInfo().setKey(testDataMap.get(segmentIndex).key.getBytes());

        try (InputStream decryptedStream = downloader.processSegment(segment, segmentIndex)) {
            byte[] decryptedData = decryptedStream.readAllBytes();
            byte[] expectedData = new byte[1024];
            for (int i = 0; i < expectedData.length; i++) {
                expectedData[i] = (byte) (i + segmentIndex); // Segment index 2
            }
            assertArrayEquals(expectedData, decryptedData, "Decrypted data should match expected plain data with default IV");
        }
    }

    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    void testDecryptWithKeyChange() throws IOException {
        // test data
        HashMap<Integer, TestData>  testDataMap = new HashMap<>();
        testDataMap.put(1, new TestData("AES/CBC/PKCS5Padding", "1234567890abcdef","0xabcdefabcdefabcdefabcdefabcdefab", "https://example.com/key1.key", "http://test/segment1.ts"));
        testDataMap.put(2, new TestData("AES/CBC/PKCS5Padding", "fedcba0987654321","0x12345678123456781234567812345678", "https://example.com/key2.key", "http://test/segment2.ts"));

        // setup parser and downloader
        MockEncFetcher downloadFetcher = new MockEncFetcher(testDataMap);
        setupHLS(null, downloadFetcher);

        // Test decryption with a key change
        int segmentIndex1 = 1;
        HlsParser.Segment segment0 = new HlsParser.Segment(URI.create(testDataMap.get(segmentIndex1).segmentUrl),
                10,
                "title_segment1",
                new HlsParser.EncryptionInfo("AES-128", URI.create(testDataMap.get(segmentIndex1).keyUrl), testDataMap.get(segmentIndex1).iv));
        segment0.getEncryptionInfo().setKey(testDataMap.get(segmentIndex1).key.getBytes());

        int segmentIndex2 = 2;
        HlsParser.Segment segment2 = new HlsParser.Segment(URI.create(testDataMap.get(segmentIndex2).segmentUrl),
                10,
                "title_segment1",
                new HlsParser.EncryptionInfo("AES-128", URI.create(testDataMap.get(segmentIndex2).keyUrl), testDataMap.get(segmentIndex2).iv));
        segment2.getEncryptionInfo().setKey(testDataMap.get(segmentIndex2).key.getBytes());

        try (InputStream decryptedStream1 = downloader.processSegment(segment0, segmentIndex1);
             InputStream decryptedStream2 = downloader.processSegment(segment2, segmentIndex2)) {
            byte[] decryptedData1 = decryptedStream1.readAllBytes();
            byte[] decryptedData2 = decryptedStream2.readAllBytes();
            byte[] expectedData1 = new byte[1024];
            byte[] expectedData2 = new byte[1024];
            for (int i = 0; i < 1024; i++) {
                expectedData1[i] = (byte) (i + segmentIndex1);
                expectedData2[i] = (byte) (i + segmentIndex2);
            }
            assertArrayEquals(expectedData1, decryptedData1, "Decrypted data for segment 1 should match");
            assertArrayEquals(expectedData2, decryptedData2, "Decrypted data for segment 2 should match with key change");
        }
    }

    @Test
    void testDecryptWithInvalidKey() {
        // test data
        HashMap<Integer, TestData>  testDataMap = new HashMap<>();
        testDataMap.put(1, new TestData("AES/CBC/PKCS5Padding", "1234567890abcdef","0xabcdefabcdefabcdefabcdefabcdefab", "https://example.com/key1.key", "http://test/segment1.ts"));

        // setup parser and downloader
        MockEncFetcher downloadFetcher = new MockEncFetcher(testDataMap);
        setupHLS(null, downloadFetcher);

        // Test with invalid key length
        int segmentIndex = 1;
        HlsParser.Segment segment = new HlsParser.Segment(URI.create(testDataMap.get(segmentIndex).segmentUrl),
                10,
                "title",
                new HlsParser.EncryptionInfo("AES-128", URI.create(testDataMap.get(segmentIndex).keyUrl), testDataMap.get(segmentIndex).iv));
        segment.getEncryptionInfo().setKey("invalid".getBytes()); // 6 bytes, not 16

        assertThrows(IOException.class, () -> downloader.processSegment(segment, segmentIndex),
                "Should throw exception for invalid key length");
    }

    public static class MockEncFetcher implements HlsParser.Fetcher {

        private HashMap<Integer, TestData> testDataMap = null;
        private String playlist = null;

        public MockEncFetcher(HashMap<Integer, TestData> testDataMap) {
            this.testDataMap = testDataMap;
        }
        public MockEncFetcher(HashMap<Integer, TestData> testDataMap, String playlist) {
            this(testDataMap);
            this.playlist = playlist;
        }

        public MockEncFetcher() {
        }

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            if (uri.getPath().contains("segment")) {
                // extract segmentNum to get testData entry
                int segmentNum = Integer.parseInt(uri.getPath().replaceAll(".*/segment(\\d+)\\.ts", "$1"));
                byte[] plainData = generateSegmentData(segmentNum);

                if (null != testDataMap) {
                    try {
                        return getEncryptedSegmentData(segmentNum, plainData);
                    } catch (RuntimeException e) {
                        throw new IOException("Encryption failed: " + e.getMessage(), e);
                    }
                } else {
                    throw new RuntimeException("testDataMap is null");
                }
            } else if (uri.toString().contains("key")) {
                int keyNum = Integer.parseInt(uri.getPath().replaceAll(".*/key(\\d+)\\.key", "$1"));
                if (null != testDataMap) {
                    return new ByteArrayInputStream(testDataMap.get(keyNum).key.getBytes());
                } else {
                    throw new RuntimeException("testDataMap is null");
                }
            } else if (playlist != null) {
                return new ByteArrayInputStream(playlist.getBytes());
            }

            return new ByteArrayInputStream("".getBytes());
        }

        public ByteArrayInputStream getEncryptedSegmentData(int segmentNum, byte[] plainData) {
            try {
                TestData segmentCipherData = testDataMap.get(segmentNum);
                Cipher cipher = Cipher.getInstance(segmentCipherData.padding);
                byte[] iv = parseIv(segmentCipherData.iv, segmentNum);
                SecretKeySpec keySpec = new SecretKeySpec(segmentCipherData.key.getBytes(), "AES");
                IvParameterSpec ivSpec = new IvParameterSpec(iv);
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
                return new ByteArrayInputStream(cipher.doFinal(plainData));
            } catch (InvalidAlgorithmParameterException | NoSuchPaddingException |
                     IllegalBlockSizeException | NoSuchAlgorithmException | BadPaddingException |
                     InvalidKeyException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * generate the plain unencrypted data for a test segment
         * @param segmentNum the number of the segment
         * @return the unencrypted test data
         */
        public byte[] generateSegmentData(int segmentNum) {
            byte[] plainData = new byte[1024];
            for (int i = 0; i < plainData.length; i++) {
                plainData[i] = (byte) (segmentNum + i);
            }
            return plainData;
        }

        private byte[] parseIv(String ivStr, int segmentIndex) {
            if (ivStr == null || ivStr.isEmpty()) {
                byte[] iv = new byte[16];
                Arrays.fill(iv, (byte) 0);
                iv[15] = (byte) (segmentIndex & 0xFF);
                return iv;
            }
            if (ivStr.startsWith("0x")) {
                ivStr = ivStr.substring(2);
            }
            if (ivStr.length() != 32) {
                throw new IllegalArgumentException("IV hex string must represent 16 bytes, got " + ivStr.length() / 2 + " bytes");
            }
            byte[] iv = new byte[16];
            for (int i = 0; i < 16; i++) {
                String byteStr = ivStr.substring(i * 2, i * 2 + 2);
                iv[i] = (byte) Integer.parseInt(byteStr, 16);
            }
            return iv;
        }
    }

    public static class TestData {
        String segmentUrl;
        String keyUrl;
        String padding;
        String key;
        String iv;

        public TestData(String padding, String key, String iv, String keyUrl, String segmentUrl) {
            this.padding = padding;
            this.key = key;
            this.iv = iv;
            this.keyUrl = keyUrl;
            this.segmentUrl = segmentUrl;
        }
    }
}

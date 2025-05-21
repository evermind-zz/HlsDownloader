package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HlsMediaProcessorDecryptorTest {
    private Path tempDir;
    private String outputDir;
    private HlsMediaProcessor downloader;
    private HlsParser parser;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test_hls_decryptor");
        outputDir = tempDir.toString();
        parser = new HlsParser(
                variants -> null,
                new MockFetcher(),
                true
        );
        downloader = new HlsMediaProcessor(parser, outputDir, tempDir.resolve("output.ts").toString(),
                new MockFetcher(), new HlsMediaProcessor.DefaultDecryptor(),
                1, new HlsMediaProcessor.DefaultSegmentStateManager(outputDir + "/download_state.txt"),
                null, (progress, total) -> {}, (state, message) -> {});
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
        // Test with explicit IV from playlist
        HlsParser.Segment segment = new HlsParser.Segment(URI.create("http://test/segment1.ts"),
                10,
                "title",
                new HlsParser.EncryptionInfo("AES-128", URI.create("https://example.com/key1.key"), "0xabcdefabcdefabcdefabcdefabcdefab"));
        segment.getEncryptionInfo().setKey("1234567890abcdef".getBytes());

        try (InputStream decryptedStream = downloader.processSegment(segment, 0)) {
            byte[] decryptedData = decryptedStream.readAllBytes();
            byte[] expectedData = new byte[1024];
            for (int i = 0; i < expectedData.length; i++) {
                expectedData[i] = (byte) i;
            }
            assertArrayEquals(expectedData, decryptedData, "Decrypted data should match expected plain data with explicit IV");
        }
    }

    @Test
    void testDecryptWithDefaultIv() throws IOException {
        // Test with default IV (segment index)
        HlsParser.Segment segment = new HlsParser.Segment(URI.create("http://test/segment2.ts"),
                10,
                "title",
                new HlsParser.EncryptionInfo("AES-128", URI.create("https://example.com/key2.key"), null));
        segment.getEncryptionInfo().setKey("fedcba0987654321".getBytes());

        try (InputStream decryptedStream = downloader.processSegment(segment, 1)) {
            byte[] decryptedData = decryptedStream.readAllBytes();
            byte[] expectedData = new byte[1024];
            for (int i = 0; i < expectedData.length; i++) {
                expectedData[i] = (byte) (i + 1); // Segment index 1
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
        // Test decryption with a key change
        HlsParser.Segment segment1 = new HlsParser.Segment(URI.create("http://test/segment1.ts"),
                10,
                "title_segment1",
                new HlsParser.EncryptionInfo("AES-128", URI.create("https://example.com/key1.key"), "0xabcdefabcdefabcdefabcdefabcdefab"));
        segment1.getEncryptionInfo().setKey("1234567890abcdef".getBytes());

        HlsParser.Segment segment2 = new HlsParser.Segment(URI.create("http://test/segment2.ts"),
                10,
                "title_segment2",
                new HlsParser.EncryptionInfo("AES-128", URI.create("https://example.com/key2.key"), "0x12345678123456781234567812345678"));
        segment2.getEncryptionInfo().setKey("fedcba0987654321".getBytes());

        try (InputStream decryptedStream1 = downloader.processSegment(segment1, 0);
             InputStream decryptedStream2 = downloader.processSegment(segment2, 1)) {
            byte[] decryptedData1 = decryptedStream1.readAllBytes();
            byte[] decryptedData2 = decryptedStream2.readAllBytes();
            byte[] expectedData1 = new byte[1024];
            byte[] expectedData2 = new byte[1024];
            for (int i = 0; i < 1024; i++) {
                expectedData1[i] = (byte) i;
                expectedData2[i] = (byte) (i + 1);
            }
            assertArrayEquals(expectedData1, decryptedData1, "Decrypted data for segment 1 should match");
            assertArrayEquals(expectedData2, decryptedData2, "Decrypted data for segment 2 should match with key change");
        }
    }

    @Test
    void testDecryptWithInvalidKey() {
        // Test with invalid key length
        HlsParser.Segment segment = new HlsParser.Segment(URI.create("http://test/segment1.ts"),
                10,
                "title",
                new HlsParser.EncryptionInfo("AES-128", URI.create("https://example.com/key1.key"), "0xabcdefabcdefabcdefabcdefabcdefab"));
        segment.getEncryptionInfo().setKey("invalid".getBytes()); // 6 bytes, not 16

        assertThrows(IllegalArgumentException.class, () -> downloader.processSegment(segment, 0),
                "Should throw exception for invalid key length");
    }

    private static class MockFetcher implements HlsParser.Fetcher {
        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            if (uri.getPath().contains("segment")) {
                int segmentNum = Integer.parseInt(uri.getPath().replaceAll(".*/segment(\\d+)\\.ts", "$1")) - 1;
                byte[] plainData = new byte[1024];
                for (int i = 0; i < plainData.length; i++) {
                    plainData[i] = (byte) (segmentNum + i);
                }
                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                    byte[] iv = segmentNum == 0 ? parseIv("0xabcdefabcdefabcdefabcdefabcdefab", segmentNum) :
                            parseIv("0x12345678123456781234567812345678", segmentNum);
                    SecretKeySpec keySpec = new SecretKeySpec(segmentNum == 0 ? "1234567890abcdef".getBytes() : "fedcba0987654321".getBytes(), "AES");
                    IvParameterSpec ivSpec = new IvParameterSpec(iv);
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
                    return new ByteArrayInputStream(cipher.doFinal(plainData));
                } catch (GeneralSecurityException e) {
                    throw new IOException("Encryption failed: " + e.getMessage(), e);
                }
            }
            return new ByteArrayInputStream("".getBytes());
        }

        @Override
        public void disconnect() {
            // No-op for test
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
}

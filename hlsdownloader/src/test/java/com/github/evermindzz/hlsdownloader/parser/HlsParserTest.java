package com.github.evermindzz.hlsdownloader.parser;

import com.github.evermindzz.hlsdownloader.parser.HlsParser.VariantStream;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.EncryptionInfo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HlsParserTest {
    private static URI toDataUri(String content) {
        return URI.create("data:application/vnd.apple.mpegurl;base64," +
                java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testMasterPlaylistParsingWithCallback() throws Exception {
        String masterContent = "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360,CODECS=\"avc1.42e01e,mp4a.40.2\"\n" +
                "media360.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\n" +
                "media720.m3u8";

        String mediaContent = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:10.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-ENDLIST";

        URI dummyMasterUri = URI.create("http://example.com/media.m3u8");
        URI mediaUri = toDataUri(mediaContent);

        HlsParser parser = new HlsParser(variants -> {
            assertEquals(2, variants.size());
            HlsParser.VariantStream selected = variants.get(1); // Simulate user picking the 720p
            assertEquals("1280x720", selected.getResolution());
            // Replace variant URI with test media URI
            return new VariantStream(mediaUri, selected.getBandwidth(), selected.getResolution(), selected.getCodecs());
        }, new MockFetcher(mediaContent),
                true);

        parser.parse(dummyMasterUri); // Will parse master, callback, then media
    }

    @Test
    void testMediaPlaylistParsingValidation() throws Exception {
        String mediaContent = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:8\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:7.5,\n" +
                "segment2.ts\n" +
                "#EXT-X-ENDLIST";

        URI dummyUri = URI.create("http://example.com/media.m3u8");

        HlsParser parser = new HlsParser(variants -> {
            fail("Should not call variant selector for media playlist");
            return null;
        }, new MockFetcher(mediaContent),
                true);

        parser.parse(dummyUri); // Should log warning due to duration > target
    }

    @Test
    void testEncryptedMediaPlaylistParsing() throws Exception {
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

        URI testUri = URI.create("http://test/media.m3u8");

        HlsParser parser = new HlsParser(
                variants -> {
                    fail("Should not be called for media playlist");
                    return null;
                },
                new MockFetcher(playlistContent),
                true
        );

        HlsParser.MediaPlaylist result = parser.parse(testUri);
        assertNotNull(result);
        assertNotNull(result.getSegments());
        assertEquals(3, result.getSegments().size());
        HlsParser.Segment segment = result.getSegments().get(0);
        EncryptionInfo encryptionInfo = segment.getEncryptionInfo();
        assertNotNull(encryptionInfo);
        assertEquals("AES-128", encryptionInfo.getMethod());
        assertEquals(URI.create("https://example.com/key.key"), encryptionInfo.getUri());
        assertEquals("0xabcdef", encryptionInfo.getIv());
        assertNull(encryptionInfo.getKey(), "Key should be null before pre-fetching");

        // Simulate pre-fetching key for testing
        byte[] mockKey = "mockkey".getBytes();
        encryptionInfo.setKey(mockKey);
        assertArrayEquals(mockKey, encryptionInfo.getKey(), "Key should be set after pre-fetching");

        // Should contain same encryption info that first segment
        segment = result.getSegments().get(1);
        EncryptionInfo encryptionInfo2 = segment.getEncryptionInfo();
        assertEquals(encryptionInfo2, encryptionInfo);
        assertArrayEquals(mockKey, encryptionInfo2.getKey(), "Key should be shared between segments with same info");

        segment = result.getSegments().get(2);
        encryptionInfo = segment.getEncryptionInfo();
        assertNotNull(encryptionInfo);
        assertEquals("AES-128", encryptionInfo.getMethod());
        assertEquals(URI.create("https://example.com/key.key"), encryptionInfo.getUri());
        assertEquals("0x123456", encryptionInfo.getIv());
        assertNull(encryptionInfo.getKey(), "Key should be null for new encryption info");
    }

    @Test
    void testEncryptedPlaylistParsing() throws IOException {
        String playlist = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.key\",IV=0xabcdef\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:9.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-ENDLIST";

        URI baseUri = URI.create("http://test/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockFetcher(playlist), false);
        HlsParser.MediaPlaylist result = parser.parse(baseUri);

        assertNotNull(result);
        assertEquals(2, result.getSegments().size());
        HlsParser.Segment segment = result.getSegments().get(0);
        EncryptionInfo encryptionInfo = segment.getEncryptionInfo();
        assertNotNull(encryptionInfo);
        assertEquals("AES-128", encryptionInfo.getMethod());
        assertEquals("http://test/key.key", encryptionInfo.getUri().toString());
        assertEquals("0xabcdef", encryptionInfo.getIv());
        assertNull(encryptionInfo.getKey(), "Key should be null before pre-fetching");
    }

    @Test
    void testStrictModeThrowsOnUnknownTags() {
        String invalidPlaylist = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-UNKNOWN-TAG:foobar\n" +
                "#EXTINF:8.0,\n" +
                "video.ts\n";

        URI baseUri = URI.create("http://test/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockFetcher(invalidPlaylist), true);

        IOException ex = assertThrows(IOException.class, () -> parser.parse(baseUri));
        assertTrue(ex.getMessage().contains("Unsupported or unknown tag"));
    }

    @Test
    void testNonStrictModeIgnoresUnknownTags() throws IOException {
        String playlist = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:5\n" +
                "#EXTINF:5.0,\n" +
                "seg.ts\n" +
                "#FOO-BAR:baz\n";

        URI baseUri = URI.create("http://example.com/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockFetcher(playlist), false);
        HlsParser.MediaPlaylist result = parser.parse(baseUri);

        assertNotNull(result);
        assertEquals(1, result.getSegments().size());
    }

    @Test
    void testStrictModeHandlesExtM3UGracefully() throws IOException {
        String playlist = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:9.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-ENDLIST";

        URI baseUri = URI.create("http://test/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockFetcher(playlist), true);
        HlsParser.MediaPlaylist result = parser.parse(baseUri);

        assertNotNull(result);
        assertEquals(2, result.getSegments().size());
    }

    @Test
    void testMissingExtM3UGShouldThrow() throws IOException {
        String playlist =
                "#EXT-X-TARGETDURATION:10\n" +
                        "#EXTINF:9.0,\n" +
                        "segment1.ts\n" +
                        "#EXTINF:9.0,\n" +
                        "segment2.ts\n" +
                        "#EXT-X-ENDLIST";

        URI baseUri = URI.create("http://test/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockFetcher(playlist), true);

        IOException ex = assertThrows(IOException.class, () -> parser.parse(baseUri));
        assertTrue(ex.getMessage().contains("Invalid playlist: Missing #EXTM3U"));
    }

    @Test
    void testInvalidExtM3UGShouldThrow() throws IOException {
        String playlist = "#EXTMU3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:9.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-ENDLIST";

        URI baseUri = URI.create("http://test/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockFetcher(playlist), true);

        IOException ex = assertThrows(IOException.class, () -> parser.parse(baseUri));
        assertTrue(ex.getMessage().contains("Invalid playlist: Missing #EXTM3U"));
    }

    static class MockFetcher implements HlsParser.Fetcher {
        final String content;

        MockFetcher(String content) {
            this.content = content;
        }

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            return new ByteArrayInputStream(content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        }

        @Override
        public void disconnect() {
            // No-op for test
        }
    }

    static class DummyCallback implements HlsParser.MasterPlaylistSelectionCallback {
        @Override
        public HlsParser.VariantStream onSelectVariant(List<VariantStream> variants) {
            return variants.get(0);
        }
    }
}

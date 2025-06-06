package com.github.evermindzz.hlsdownloader.parser;

import com.github.evermindzz.hlsdownloader.common.Fetcher;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.VariantStream;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.EncryptionInfo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:10.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-ENDLIST";

        URI dummyUri = URI.create("http://example.com/media.m3u8");

        HlsParser parser = new HlsParser(variants -> {
            fail("Should not call variant selector for media playlist");
            return null;
        }, new MockFetcher(mediaContent),
                true);

        HlsParser.MediaPlaylist result = parser.parse(dummyUri); // Should not throw in strict mode
        assertNotNull(result);
        assertEquals(2, result.getSegments().size());
        assertEquals(10.0, result.getTargetDuration());
        assertEquals(9.0, result.getSegments().get(0).getDuration());
        assertEquals(10.0, result.getSegments().get(1).getDuration());
    }

    @Test
    void testEncryptedMediaPlaylistParsing() throws Exception {
        String playlistContent = "#EXTM3U\n" +
                "#EXT-X-VERSION:2\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key.key\",IV=0xabcdef1234567890abcdef1234567890\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXTINF:9.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key.key\",IV=0x1234567890abcdef1234567890abcdef\n" +
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
        assertEquals("0xabcdef1234567890abcdef1234567890", encryptionInfo.getIv());
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
        assertEquals("0x1234567890abcdef1234567890abcdef", encryptionInfo.getIv());
        assertNull(encryptionInfo.getKey(), "Key should be null for new encryption info");
    }

    @Test
    void testEncryptedPlaylistParsing() throws IOException {
        String playlist = "#EXTM3U\n" +
                "#EXT-X-VERSION:2\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.key\",IV=0xabcdef1234567890abcdef1234567890\n" +
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
        assertEquals("0xabcdef1234567890abcdef1234567890", encryptionInfo.getIv());
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
        assertTrue(ex.getMessage().contains("Unsupported media playlist tag: #EXT-UNKNOWN-TAG:foobar"));
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

    @Test
    void testSerializeAndDeserialize() throws IOException, ClassNotFoundException {
        Result result = parseAdvancedPlaylistHelper();
        serializeAndDeserializeSegments(result.res);
    }

    @Test
    void testAdvancedMediaPlaylistParsing() throws IOException {
        Result result = parseAdvancedPlaylistHelper();

        assertNotNull(result.res);
        assertEquals(3, result.res.getSegments().size());
        assertEquals(7, result.parser.extractVersion(result.playlist)); // Verify version
        assertEquals(10.0, result.res.getTargetDuration());
        assertEquals(0, result.res.getMediaSequence());
        assertEquals("VOD", result.res.getPlaylistType());
        assertTrue(result.res.isEndList());
        assertTrue(result.res.isIndependentSegments());
        assertNotNull(result.res.getMap());
        assertEquals(URI.create("http://test/init.mp4"), result.res.getMap().getUri());

        HlsParser.Segment segment1 = result.res.getSegments().get(0);
        assertEquals(9.0, segment1.getDuration());
        assertNotNull(segment1.getEncryptionInfo());
        assertEquals("AES-128", segment1.getEncryptionInfo().getMethod());
        assertEquals("http://test/key.key", segment1.getEncryptionInfo().getUri().toString());
        assertEquals("0xabcdef1234567890abcdef1234567890", segment1.getEncryptionInfo().getIv());

        HlsParser.Segment segment2 = result.res.getSegments().get(1);
        assertEquals(10.0, segment2.getDuration());
        assertNull(segment2.getEncryptionInfo()); // Discontinuity resets encryption

        HlsParser.Segment segment3 = result.res.getSegments().get(2);
        assertEquals(9.0, segment3.getDuration());
        assertEquals("2025-05-20T22:00:00Z", segment3.getProgramDateTime());
    }

    private static Result parseAdvancedPlaylistHelper() throws IOException {
        String playlist = "#EXTM3U\n" +
                "#EXT-X-VERSION:7\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n" +
                "#EXT-X-INDEPENDENT-SEGMENTS\n" +
                "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.key\",IV=0xabcdef1234567890abcdef1234567890\n" +
                "#EXTINF:9.0,\n" +
                "segment1.ts\n" +
                "#EXT-X-DISCONTINUITY\n" +
                "#EXTINF:10.0,\n" +
                "segment2.ts\n" +
                "#EXT-X-PROGRAM-DATE-TIME:2025-05-20T22:00:00Z\n" +
                "#EXTINF:9.0,\n" +
                "segment3.ts\n" +
                "#EXT-X-ENDLIST";

        URI baseUri = URI.create("http://test/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockFetcher(playlist), true);
        HlsParser.MediaPlaylist res = parser.parse(baseUri);
        Result result = new Result(playlist, parser, res);
        return result;
    }

    private static class Result {
        public final String playlist;
        public final HlsParser parser;
        public final HlsParser.MediaPlaylist res;

        public Result(String playlist, HlsParser parser, HlsParser.MediaPlaylist res) {
            this.playlist = playlist;
            this.parser = parser;
            this.res = res;
        }
    }

    private void serializeAndDeserializeSegments(HlsParser.MediaPlaylist result)
            throws IOException, ClassNotFoundException {

        Path tempDir = Files.createTempDirectory("test_hls_downloader");
        File tempSerFile = Path.of(tempDir.toString(), "HlsMediaPlaylist.ser").toFile();
        FileOutputStream fileOutputStream = new FileOutputStream(tempSerFile);
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
        objectOutputStream.writeObject(result);
        objectOutputStream.close();

        FileInputStream fis = new FileInputStream(tempSerFile);
        ObjectInputStream inputStream = new ObjectInputStream(fis);
        HlsParser.MediaPlaylist deSerializedObject = (HlsParser.MediaPlaylist) inputStream.readObject();
        inputStream.close();

        assertEquals(result.getSegments().size(), deSerializedObject.getSegments().size());
        for (int i = 0; i < result.getSegments().size(); i++) {
            HlsParser.Segment expected = result.getSegments().get(i);
            HlsParser.Segment actual = deSerializedObject.getSegments().get(i);
            assertEquals(expected.getUri(), actual.getUri());
            assertEquals(expected.getDuration(), actual.getDuration());

            if (expected.getEncryptionInfo() != null) {
                assertEquals(expected.getEncryptionInfo().getIv(), actual.getEncryptionInfo().getIv());
                assertEquals(expected.getEncryptionInfo().getUri(), actual.getEncryptionInfo().getUri());
            } else {
                assertNull(actual.getEncryptionInfo());
            }
        }

        Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .map(Path::toFile)
                .forEach(File::delete);

    }

    static class MockFetcher implements Fetcher {
        final String content;

        MockFetcher(String content) {
            this.content = content;
        }

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            return new ByteArrayInputStream(content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        }
    }

    static class DummyCallback implements HlsParser.MasterPlaylistSelectionCallback {
        @Override
        public HlsParser.VariantStream onSelectVariant(List<VariantStream> variants) {
            return variants.get(0);
        }
    }
}
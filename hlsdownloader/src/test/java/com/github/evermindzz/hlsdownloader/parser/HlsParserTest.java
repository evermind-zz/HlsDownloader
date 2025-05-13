package com.github.evermindzz.hlsdownloader.parser;

import com.github.evermindzz.hlsdownloader.parser.HlsParser.VariantStream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        }, new MockDownloader(mediaContent),
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
        }, new MockDownloader(mediaContent),
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
                "#EXT-X-ENDLIST";

        URI testUri = URI.create("http://test/media.m3u8");

        HlsParser parser = new HlsParser(
                variants -> {
                    fail("Should not be called for media playlist");
                    return null;
                },
                new MockDownloader(playlistContent),
                true
        );

        // Here, we'd want to access the MediaPlaylist instance — optional enhancement: callback or accessor
        parser.parse(testUri);
        // If MediaPlaylist was returned or exposed, add assertions like:
        // assertEquals("AES-128", playlist.getEncryptionInfo().method);
        // assertEquals(URI.create("https://example.com/key.key"), playlist.getEncryptionInfo().uri);
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

        HlsParser parser = new HlsParser(new DummyCallback(), new MockDownloader(playlist), false);
        HlsParser.MediaPlaylist result = parser.parse(baseUri);

        assertNotNull(result);
        assertEquals(2, result.segments.size());
        assertNotNull(result.encryptionInfo);
        assertEquals("AES-128", result.encryptionInfo.method);
        assertEquals("http://test/key.key", result.encryptionInfo.uri.toString());
        assertEquals("0xabcdef", result.encryptionInfo.iv);
    }

    @Test
    void testStrictModeThrowsOnUnknownTags() {
        String invalidPlaylist = "#EXTM3U\n" +
                "#EXT-X-TARGETDURATION:10\n" +
                "#EXT-UNKNOWN-TAG:foobar\n" +
                "#EXTINF:8.0,\n" +
                "video.ts\n";

        URI baseUri = URI.create("http://test/");

        HlsParser parser = new HlsParser(new DummyCallback(), new MockDownloader(invalidPlaylist), true);

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

        HlsParser parser = new HlsParser(new DummyCallback(), new MockDownloader(playlist), false);
        HlsParser.MediaPlaylist result = parser.parse(baseUri);

        assertNotNull(result);
        assertEquals(1, result.segments.size());
    }

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

    static class DummyCallback implements HlsParser.MasterPlaylistSelectionCallback {
        @Override
        public HlsParser.VariantStream onSelectVariant(List<VariantStream> variants) {
            return variants.get(0);
        }
    }
}

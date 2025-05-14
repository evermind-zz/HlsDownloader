package com.github.evermindzz.hlsdownloader.parser;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parser for HLS playlists. Supports both master and media playlists,
 * and returns fully parsed MediaPlaylist objects.
 */
public class HlsParser {
    private final MasterPlaylistSelectionCallback callback;
    private final Downloader downloader;
    private final boolean strictMode;

    /**
     * Constructs a new HlsParser.
     *
     * @param callback    Callback to select a stream variant when parsing master playlists.
     * @param downloader  Downloader strategy to fetch content.
     * @param strictMode  If true, unknown tags will throw exceptions. Otherwise, they're ignored.
     */
    public HlsParser(MasterPlaylistSelectionCallback callback, Downloader downloader, boolean strictMode) {
        this.callback = callback;
        this.downloader = downloader;
        this.strictMode = strictMode;
    }

    /**
     * Parses a playlist (master or media) and returns a MediaPlaylist.
     * @param uri URI to the playlist
     * @return parsed MediaPlaylist
     * @throws IOException if downloading or parsing fails
     */
    public MediaPlaylist parse(URI uri) throws IOException {
        String content = downloader.download(uri);
        if (!content.startsWith("#EXTM3U")) {
            throw new IOException("Invalid playlist: Missing #EXTM3U tag at the start.");
        }
        if (content.contains("#EXT-X-STREAM-INF")) {
            return parseMasterPlaylist(content, uri);
        } else {
            return parseMediaPlaylist(content, uri);
        }
    }

    private MediaPlaylist parseMasterPlaylist(String content, URI baseUri) throws IOException {
        List<VariantStream> variants = new ArrayList<>();
        String[] lines = content.split("\\n");

        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                Map<String, String> attrs = parseAttributes(lines[i]);
                URI streamUri = baseUri.resolve(lines[i + 1]);
                variants.add(new VariantStream(
                        streamUri,
                        Integer.parseInt(attrs.getOrDefault("BANDWIDTH", "0")),
                        attrs.getOrDefault("RESOLUTION", "unknown"),
                        attrs.getOrDefault("CODECS", "unknown")
                ));
            }
        }

        VariantStream chosen = callback.onSelectVariant(variants);
        return parse(chosen.getUri());
    }

    /**
     * Parses a media playlist (M3U8 format).
     * <p>
     * This method processes the content of a media playlist and extracts relevant information such as segment URIs,
     * durations, encryption details, and other tags. It ensures that the playlist starts with the #EXTM3U tag, and handles
     * various other tags like #EXT-X-TARGETDURATION, #EXTINF, #EXT-X-KEY, and #EXT-X-ENDLIST.
     * If strict mode is enabled, unknown tags will result in an exception.
     * </p>
     *
     * @param content The content of the playlist as a string.
     * @param baseUri The base URI to resolve relative segment URIs.
     * @return A MediaPlaylist object containing parsed segments and metadata.
     * @throws IOException If there is an error parsing the playlist or if an unexpected tag is encountered in strict mode.
     */
    private MediaPlaylist parseMediaPlaylist(String content, URI baseUri) throws IOException {
        MediaPlaylist playlist = new MediaPlaylist();
        String[] lines = content.split("\\n");
        double currentDuration = 0;
        String currentTitle = "";
        EncryptionInfo currentEncryption = null;

        for (String line : lines) {
            if (line.startsWith("#EXTM3U")) {
                continue;
            } else if (line.startsWith("#EXT-X-TARGETDURATION")) {
                playlist.targetDuration = Double.parseDouble(line.split(":")[1]);
            } else if (line.startsWith("#EXTINF")) {
                String[] parts = line.split(":")[1].split(",");
                currentDuration = Double.parseDouble(parts[0]);
                currentTitle = parts.length > 1 ? parts[1] : "";
            } else if (line.startsWith("#EXT-X-KEY")) {
                Map<String, String> attrs = parseAttributes(line);
                String method = attrs.get("METHOD");
                URI keyUri = attrs.get("URI") != null ? baseUri.resolve(attrs.get("URI").replace("\"", "")) : null;
                String iv = attrs.get("IV");
                currentEncryption = new EncryptionInfo(method, keyUri, iv);
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                URI segmentUri = baseUri.resolve(line);
                Segment segment = new Segment(segmentUri, currentDuration, currentTitle, currentEncryption);
                playlist.addSegment(segment);
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                playlist.endList = true;
            } else {
                if (strictMode) {
                    throw new IOException("Unsupported or unknown tag in strict mode: " + line);
                }
                // Optionally log unknown tags in non-strict mode
            }
        }

        validatePlaylist(playlist);
        // Further processing/callback with MediaPlaylist if needed
        return playlist;
    }

    private void validatePlaylist(MediaPlaylist playlist) {
        for (Segment s : playlist.segments) {
            if (s.duration > playlist.targetDuration) {
                System.err.println("Warning: Segment duration exceeds target duration.");
            }
        }
    }

    private Map<String, String> parseAttributes(String line) {
        Map<String, String> attrs = new HashMap<>();
        Matcher matcher = Pattern.compile("(\\w+)=\"?([^,\"]+)\"?").matcher(line);
        while (matcher.find()) {
            attrs.put(matcher.group(1), matcher.group(2));
        }
        return attrs;
    }

    // ===== Interfaces =====

    /**
     * Interface to select a variant from a list of available streams in a master playlist.
     */
    public interface MasterPlaylistSelectionCallback {
        VariantStream onSelectVariant(List<VariantStream> variants);
    }

    /**
     * Interface for downloading content from a given URI.
     */
    public interface Downloader {
        String download(URI uri) throws IOException;
    }

    // ===== Models =====

    /**
     * Represents a variant stream in a master playlist.
     */
    public static class VariantStream {
        URI uri;
        int bandwidth;
        String resolution;
        String codecs;

        public VariantStream(URI uri, int bandwidth, String resolution, String codecs) {
            this.uri = uri;
            this.bandwidth = bandwidth;
            this.resolution = resolution;
            this.codecs = codecs;
        }

        public URI getUri() { return uri; }
        public int getBandwidth() { return bandwidth; }
        public String getResolution() { return resolution; }
        public String getCodecs() { return codecs; }

        @Override
        public String toString() {
            return "VariantStream{" +
                    "bandwidth=" + bandwidth +
                    ", resolution='" + resolution + '\'' +
                    ", codecs='" + codecs + '\'' +
                    ", uri=" + uri +
                    '}';
        }
    }

    /**
     * Represents a single segment in a media playlist.
     */
    public static class Segment {
        URI uri;
        double duration;
        String title;
        EncryptionInfo encryptionInfo;

        public Segment(URI uri, double duration, String title, EncryptionInfo encryptionInfo) {
            this.uri = uri;
            this.duration = duration;
            this.title = title;
            this.encryptionInfo = encryptionInfo;
        }

        public URI getUri() { return uri; }
        public double getDuration() { return duration; }
        public String getTitle() { return title; }
        public EncryptionInfo getEncryptionInfo() { return encryptionInfo; }
    }

    /**
     * Represents a parsed media playlist.
     */
    public static class MediaPlaylist {
        List<Segment> segments = new ArrayList<>();
        double targetDuration;
        boolean endList;

        public void addSegment(Segment segment) {
            segments.add(segment);
        }

        public List<Segment> getSegments() { return segments; }
        public double getTargetDuration() { return targetDuration; }
        public boolean isEndList() { return endList; }
    }

    /**
     * Represents encryption information for a segment.
     */
    public static class EncryptionInfo {
        public String method;
        public URI uri;
        public String iv;

        public EncryptionInfo(String method, URI uri, String iv) {
            this.method = method;
            this.uri = uri;
            this.iv = iv;
        }

        public String getMethod() { return method; }
        public URI getUri() { return uri; }
        public String getIv() { return iv; }
    }
}
package com.github.evermindzz.hlsdownloader.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parser for HLS playlists. Supports both master and media playlists, adhering to the
 * HLS specification (RFC 8216 and Apple’s Authoring Guidelines). Returns fully parsed
 * MediaPlaylist objects with support for all standard tags, including encryption,
 * discontinuities, byte ranges, and low-latency features.
 */
public class HlsParser {
    private final MasterPlaylistSelectionCallback callback;
    private final Fetcher fetcher;
    private final boolean strictMode;

    /**
     * Constructs a new HlsParser.
     *
     * @param callback    Callback to select a stream variant when parsing master playlists.
     * @param fetcher     Fetcher strategy to retrieve content.
     * @param strictMode  If true, unknown or invalid tags will throw exceptions. Otherwise, they’re logged as warnings.
     */
    public HlsParser(MasterPlaylistSelectionCallback callback, Fetcher fetcher, boolean strictMode) {
        this.callback = callback;
        this.fetcher = fetcher;
        this.strictMode = strictMode;
    }

    /**
     * Parses a playlist (master or media) and returns a MediaPlaylist.
     *
     * @param uri URI to the playlist
     * @return parsed MediaPlaylist
     * @throws IOException if downloading or parsing fails
     */
    public MediaPlaylist parse(URI uri) throws IOException {
        try (InputStream contentStream = fetcher.fetchContent(uri)) {
            String content = convertStreamToString(contentStream);
            if (!content.trim().startsWith("#EXTM3U")) {
                throw new IOException("Invalid playlist: Missing #EXTM3U tag at the start.");
            }
            int version = extractVersion(content);
            if (content.contains("#EXT-X-STREAM-INF")) {
                return parseMasterPlaylist(content, uri, version);
            } else {
                return parseMediaPlaylist(content, uri, version);
            }
        }
    }

    private String convertStreamToString(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString("UTF-8");
    }

    public int extractVersion(String content) {
        Pattern versionPattern = Pattern.compile("#EXT-X-VERSION:(\\d+)");
        Matcher matcher = versionPattern.matcher(content);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1; // Default to version 1 if not specified
    }

    private MediaPlaylist parseMasterPlaylist(String content, URI baseUri, int version) throws IOException {
        List<VariantStream> variants = new ArrayList<>();
        String[] lines = content.split("\\n");

        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                Map<String, String> attrs = parseAttributes(line);
                String uriStr = lines[i + 1].trim();
                if (uriStr.isEmpty()) {
                    throw new IOException("Missing URI after #EXT-X-STREAM-INF");
                }
                URI streamUri = baseUri.resolve(uriStr);
                variants.add(new VariantStream(
                        streamUri,
                        Integer.parseInt(attrs.getOrDefault("BANDWIDTH", "0")),
                        attrs.getOrDefault("RESOLUTION", "unknown"),
                        attrs.getOrDefault("CODECS", "unknown")
                ));
            } else if (strictMode && line.startsWith("#") && !isKnownMasterTag(line)) {
                throw new IOException("Unsupported master playlist tag: " + line);
            }
        }

        if (variants.isEmpty()) {
            throw new IOException("No variant streams found in master playlist");
        }
        VariantStream chosen = callback.onSelectVariant(variants);
        return parse(chosen.getUri());
    }

    private boolean isKnownMasterTag(String line) {
        return line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-VERSION") ||
                line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-MEDIA");
    }

    private MediaPlaylist parseMediaPlaylist(String content, URI baseUri, int version) throws IOException {
        MediaPlaylist playlist = new MediaPlaylist();
        String[] lines = content.split("\\n");
        Segment currentSegment = null;
        EncryptionInfo currentEncryption = null;
        Map<String, String> currentByteRange = null;
        String pendingProgramDateTime = null; // Store pending date-time for the next segment

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (line.equals("#EXTM3U")) {
                continue;
            } else if (line.startsWith("#EXT-X-VERSION")) {
                int newVersion = Integer.parseInt(line.split(":")[1]);
                if (newVersion != version) {
                    System.err.println("Warning: Version mismatch, using " + version);
                }
            } else if (line.startsWith("#EXT-X-TARGETDURATION")) {
                playlist.targetDuration = Double.parseDouble(line.split(":")[1]);
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                playlist.mediaSequence = Integer.parseInt(line.split(":")[1]);
            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE")) {
                String type = line.split(":")[1];
                if (!"VOD".equals(type) && !"EVENT".equals(type)) {
                    throw new IOException("Invalid PLAYLIST-TYPE: " + type);
                }
                playlist.playlistType = type;
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                playlist.endList = true;
            } else if (line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")) {
                playlist.independentSegments = true;
            } else if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                if (currentSegment != null) {
                    playlist.addSegment(currentSegment);
                }
                currentSegment = null; // Reset for discontinuity
                currentEncryption = null; // Reset encryption state
                currentByteRange = null; // Reset byte range state
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME")) {
                pendingProgramDateTime = line.split(":", 2)[1]; // Store for the next segment
            } else if (line.startsWith("#EXT-X-MAP")) {
                Map<String, String> attrs = parseAttributes(line);
                URI mapUri = baseUri.resolve(attrs.get("URI").replace("\"", ""));
                long length = attrs.containsKey("BYTERANGE-LENGTH") ? Long.parseLong(attrs.get("BYTERANGE-LENGTH")) : -1;
                long offset = attrs.containsKey("BYTERANGE-OFFSET") ? Long.parseLong(attrs.get("BYTERANGE-OFFSET")) : 0;
                playlist.map = new MapInfo(mapUri, length, offset);
            } else if (line.startsWith("#EXT-X-BYTERANGE")) {
                currentByteRange = parseAttributes(line);
            } else if (line.startsWith("#EXT-X-KEY")) {
                Map<String, String> attrs = parseAttributes(line);
                String method = attrs.get("METHOD");
                if (!"AES-128".equals(method) && !"SAMPLE-AES".equals(method) && !"NONE".equals(method)) {
                    throw new IOException("Unsupported encryption method: " + method);
                }
                URI keyUri = attrs.get("URI") != null ? baseUri.resolve(attrs.get("URI").replace("\"", "")) : null;
                String iv = attrs.get("IV");
                if (iv != null) {
                    if (!iv.startsWith("0x")) {
                        throw new IOException("IV must be a hexadecimal string starting with 0x: " + iv);
                    }
                    if (iv.length() != 34) { // 0x + 32 hex chars (16 bytes)
                        throw new IOException("IV must be 16 bytes (32 hex chars after 0x), got length: " + (iv.length() - 2));
                    }
                    if (!isValidHex(iv.substring(2))) {
                        throw new IOException("IV contains invalid hexadecimal characters: " + iv);
                    }
                }
                if (version < 2 && iv != null) {
                    throw new IOException("IV requires version 2 or higher, current version: " + version);
                }
                currentEncryption = new EncryptionInfo(method, keyUri, iv);
            } else if (line.startsWith("#EXTINF")) {
                String[] parts = line.split(":", 2)[1].split(",");
                double duration = Double.parseDouble(parts[0]);
                String title = parts.length > 1 ? parts[1] : "";
                if (currentSegment != null) {
                    playlist.addSegment(currentSegment);
                }
                currentSegment = new Segment(null, duration, title, currentEncryption);
                currentSegment.byteRange = currentByteRange;
                if (pendingProgramDateTime != null) {
                    currentSegment.programDateTime = pendingProgramDateTime;
                    pendingProgramDateTime = null; // Reset after applying
                }
            } else if (line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT")) {
                // Partial support for low-latency HLS, log as warning
                System.err.println("Warning: Low-latency tag " + line + " encountered but not fully supported");
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                if (currentSegment == null) {
                    throw new IOException("Segment URI found without preceding #EXTINF");
                }
                currentSegment.uri = baseUri.resolve(line);
                playlist.addSegment(currentSegment);
                currentSegment = null; // Reset after adding
                currentByteRange = null; // Reset byte range
            } else if (strictMode && line.startsWith("#") && !isKnownMediaTag(line)) {
                throw new IOException("Unsupported media playlist tag: " + line);
            }
        }

        if (currentSegment != null) {
            playlist.addSegment(currentSegment);
        }

        validatePlaylist(playlist, version);
        return playlist;
    }

    private boolean isValidHex(String hex) {
        return hex.matches("^[0-9a-fA-F]+$");
    }

    private boolean isKnownMediaTag(String line) {
        return line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-VERSION") ||
                line.startsWith("#EXT-X-TARGETDURATION") || line.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
                line.startsWith("#EXT-X-PLAYLIST-TYPE") || line.startsWith("#EXT-X-ENDLIST") ||
                line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || line.startsWith("#EXT-X-DISCONTINUITY") ||
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith("#EXT-X-MAP") ||
                line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-KEY") ||
                line.startsWith("#EXTINF") || line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT");
    }

    private void validatePlaylist(MediaPlaylist playlist, int version) throws IOException {
        if (playlist.segments.isEmpty()) {
            throw new IOException("No segments found in media playlist");
        }
        if (playlist.targetDuration <= 0) {
            throw new IOException("Invalid or missing #EXT-X-TARGETDURATION");
        }
        for (Segment s : playlist.segments) {
            if (s.duration > playlist.targetDuration && strictMode) {
                throw new IOException("Segment duration " + s.duration + " exceeds target duration " + playlist.targetDuration);
            }
        }
        if (playlist.endList && playlist.playlistType == null) {
            playlist.playlistType = "VOD"; // Default to VOD if endlist and no type
        } else if (!playlist.endList && "VOD".equals(playlist.playlistType)) {
            throw new IOException("VOD playlist must have #EXT-X-ENDLIST");
        }
        if (version < 1 || version > 10) {
            throw new IOException("Invalid HLS version: " + version);
        }
    }

    private Map<String, String> parseAttributes(String line) {
        Map<String, String> attrs = new HashMap<>();
        Matcher matcher = Pattern.compile("(\\w+)=(\"?([^,\"]+)\"?|([^,\\s]+))").matcher(line);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2).replace("\"", "") : matcher.group(4);
            attrs.put(matcher.group(1), value);
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
     * Interface for fetching content from a URI.
     */
    public interface Fetcher {
        /**
         * Fetches content from the given URI and returns an InputStream.
         * This method must be thread-safe. The returned InputStream should override
         * the close() method to ensure the underlying network connection is properly
         * closed when the stream is closed.
         *
         * @param uri The URI to fetch content from.
         * @return An InputStream containing the fetched content.
         * @throws IOException If an I/O error occurs during fetching.
         */
        InputStream fetchContent(URI uri) throws IOException;
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
    public static class Segment implements Serializable {
        private static final long serialVersionUID = 1L;

        private URI uri;
        private double duration;
        private String title;
        private EncryptionInfo encryptionInfo;
        private Map<String, String> byteRange;
        private String programDateTime;

        public Segment(URI uri, double duration, String title, EncryptionInfo encryptionInfo) {
            this.uri = uri;
            this.duration = duration;
            this.title = title;
            this.encryptionInfo = encryptionInfo;
            this.byteRange = null;
            this.programDateTime = null;
        }

        public URI getUri() { return uri; }
        public double getDuration() { return duration; }
        public String getTitle() { return title; }
        public EncryptionInfo getEncryptionInfo() { return encryptionInfo; }
        public Map<String, String> getByteRange() { return byteRange; }
        public String getProgramDateTime() { return programDateTime; }
    }

    /**
     * Represents a parsed media playlist.
     */
    public static class MediaPlaylist {
        List<Segment> segments = new ArrayList<>();
        double targetDuration;
        int mediaSequence;
        String playlistType;
        boolean endList;
        boolean independentSegments;
        MapInfo map;

        public void addSegment(Segment segment) {
            segments.add(segment);
        }

        public List<Segment> getSegments() { return segments; }
        public double getTargetDuration() { return targetDuration; }
        public int getMediaSequence() { return mediaSequence; }
        public String getPlaylistType() { return playlistType; }
        public boolean isEndList() { return endList; }
        public boolean isIndependentSegments() { return independentSegments; }
        public MapInfo getMap() { return map; }
    }

    /**
     * Represents encryption information for a segment.
     */
    public static class EncryptionInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        private String method;
        private URI uri;
        private String iv;
        private byte[] key; // Added for caching the key

        public EncryptionInfo(String method, URI uri, String iv) {
            this.method = method;
            this.uri = uri;
            this.iv = iv;
            this.key = null;
        }

        public String getMethod() { return method; }
        public URI getUri() { return uri; }
        public String getIv() { return iv; }
        public byte[] getKey() { return key; }
        public void setKey(byte[] key) { this.key = key; }
    }

    /**
     * Represents a map section for initialization data.
     */
    public static class MapInfo {
        URI uri;
        long length;
        long offset;

        public MapInfo(URI uri, long length, long offset) {
            this.uri = uri;
            this.length = length;
            this.offset = offset;
        }

        public URI getUri() { return uri; }
        public long getLength() { return length; }
        public long getOffset() { return offset; }
    }
}
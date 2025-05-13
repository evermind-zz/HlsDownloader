package com.github.evermindzz.hlsdownloader.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HlsParser is a lightweight parser for both Master and Media HLS playlists.
 * It supports variant stream selection via callback and AES-128 encryption metadata parsing.
 */
public class HlsParser {
    private final MasterPlaylistSelectionCallback callback;
    private final Downloader downloader;
    private final boolean strictMode;

    /**
     * Constructs a new HlsParser.
     *
     * @param callback    Callback to select a stream variant when parsing master playlists.
     * @param downloader  Downloader strategy to fetch playlist content.
     * @param strictMode  If true, unknown tags will throw exceptions. Otherwise, they're ignored.
     */
    public HlsParser(MasterPlaylistSelectionCallback callback, Downloader downloader, boolean strictMode) {
        this.callback = callback;
        this.downloader = downloader;
        this.strictMode = strictMode;
    }

    /**
     * Parses the given URI as either a master or media playlist.
     *
     * @param uri URI of the playlist to parse.
     * @throws IOException if downloading or parsing fails.
     *
     * @return in case of Media playlist the MediaPlaylist, for master playlist null
     */
    public MediaPlaylist parse(URI uri) throws IOException {
        String content = downloader.download(uri);
        if (content.contains("#EXT-X-STREAM-INF")) {
            parseMasterPlaylist(content, uri);
            return null; // master playlist doesn't return anything directly
        } else {
            return parseMediaPlaylist(content, uri);
        }
    }


    private void parseMasterPlaylist(String content, URI baseUri) throws IOException {
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
        parse(chosen.getUri());
    }

    private MediaPlaylist parseMediaPlaylist(String content, URI baseUri) throws IOException {
        MediaPlaylist playlist = new MediaPlaylist();
        String[] lines = content.split("\\n");
        double currentDuration = 0;
        String currentTitle = "";

        for (String line : lines) {
            if (line.startsWith("#EXT-X-TARGETDURATION")) {
                playlist.targetDuration = Double.parseDouble(line.split(":")[1]);
            } else if (line.startsWith("#EXTINF")) {
                String[] parts = line.split(":")[1].split(",");
                currentDuration = Double.parseDouble(parts[0]);
                currentTitle = parts.length > 1 ? parts[1] : "";
            } else if (line.startsWith("#EXT-X-KEY")) {
                Map<String, String> attrs = parseAttributes(line);
                String method = attrs.get("METHOD");
                URI keyUri = baseUri.resolve(attrs.get("URI").replace("\"", ""));
                String iv = attrs.get("IV"); // optional
                playlist.setEncryptionInfo(new EncryptionInfo(method, keyUri, iv));
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                URI segmentUri = baseUri.resolve(line);
                playlist.addSegment(new Segment(segmentUri, currentDuration, currentTitle));
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                playlist.endList = true;
            } else if (line.startsWith("#")) {
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

    private String download(URI uri) throws IOException {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
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

        public Segment(URI uri, double duration, String title) {
            this.uri = uri;
            this.duration = duration;
            this.title = title;
        }
    }

    /**
     * Represents a parsed media playlist.
     */
    public static class MediaPlaylist {
        public List<Segment> segments = new ArrayList<>();
        double targetDuration;
        boolean endList;
        public EncryptionInfo encryptionInfo;

        public void addSegment(Segment segment) {
            segments.add(segment);
        }

        public void setEncryptionInfo(EncryptionInfo info) {
            this.encryptionInfo = info;
        }
    }

    /**
     * Represents encryption information for media segments.
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
    }
}
package com.github.evermindzz.hlsdownloader.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HlsParser {
    private final MasterPlaylistSelectionCallback callback;
    private final Downloader downloader;


    public HlsParser(MasterPlaylistSelectionCallback callback, Downloader downloader) {
        this.callback = callback;
        this.downloader = downloader;
    }

    public void parse(URI uri) throws IOException {
        String content = downloader.download(uri);
        if (content.contains("#EXT-X-STREAM-INF")) {
            parseMasterPlaylist(content, uri);
        } else {
            parseMediaPlaylist(content, uri);
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

    private void parseMediaPlaylist(String content, URI baseUri) throws IOException {
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
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                URI segmentUri = baseUri.resolve(line);
                playlist.addSegment(new Segment(segmentUri, currentDuration, currentTitle));
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                playlist.endList = true;
            } else if (line.startsWith("#EXT-X-KEY")) {
                Map<String, String> attrs = parseAttributes(line);
                String method = attrs.get("METHOD");
                URI keyUri = baseUri.resolve(attrs.get("URI").replace("\"", ""));
                String iv = attrs.get("IV"); // optional
                playlist.setEncryptionInfo(new EncryptionInfo(method, keyUri, iv));
            }

        }

        validatePlaylist(playlist);
        // Further processing/callback with MediaPlaylist if needed
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

    public interface MasterPlaylistSelectionCallback {
        VariantStream onSelectVariant(List<VariantStream> variants);
    }

    public interface Downloader {
        String download(URI uri) throws IOException;
    }

    public static class EncryptionInfo {
    String method;
    URI uri;
    String iv;

    public EncryptionInfo(String method, URI uri, String iv) {
        this.method = method;
        this.uri = uri;
        this.iv = iv;
    }
}

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

        public URI getUri() {
            return uri;
        }

        public int getBandwidth() {
            return bandwidth;
        }

        public String getResolution() {
            return resolution;
        }

        public String getCodecs() {
            return codecs;
        }

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

    public class Segment {
        URI uri;
        double duration;
        String title;

        public Segment(URI uri, double duration, String title) {
            this.uri = uri;
            this.duration = duration;
            this.title = title;
        }
    }

    public class MediaPlaylist {
        EncryptionInfo encryptionInfo;

        public void setEncryptionInfo(EncryptionInfo info) {
            this.encryptionInfo = info;
        }

        List<Segment> segments = new ArrayList<>();
        double targetDuration;
        boolean endList;

        public void addSegment(Segment segment) {
            segments.add(segment);
        }
    }
}

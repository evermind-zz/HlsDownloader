package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.MediaPlaylist;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.Segment;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * A downloader for HLS media playlists, designed to be Android-agnostic with hooks for integration
 * into systems like NewPipe's giga downloader. Supports pause/resume functionality and multi-threading.
 */
public class HlsMediaProcessor {
    private final HlsParser parser;
    private final String outputDir;
    private final String outputFile;
    private final StateManager stateManager;
    private final DownloadProgressCallback progressCallback;
    private final PostProcessingCallback postProcessingCallback;
    private final AfterPostProcessingCallback afterPostProcessingCallback;
    private final SegmentCombiner segmentCombiner;
    private final AtomicBoolean isCancelled;
    private final HlsParser.Fetcher fetcher;
    private final Decryptor decryptor;
    private MediaPlaylist playlist; // Store playlist for resuming

    /**
     * Constructs a new HlsMediaProcessor.
     *
     * @param parser                    The HlsParser instance to parse playlists.
     * @param outputDir                 Directory to store temporary segments.
     * @param outputFile                Final output file path for the combined segments.
     * @param fetcher                   Use this fetcher to download segments and keys.
     * @param decryptor                 Use this decryptor to decrypt segments.
     * @param stateManager              Handles loading, saving, and cleaning up state.
     * @param segmentCombiner           Handles combining segments.
     * @param progressCallback          Callback for download progress updates.
     * @param postProcessingCallback    Callback for post-processing (e.g., format conversion in NewPipe).
     * @param afterPostProcessingCallback Callback for actions after post-processing.
     */
    public HlsMediaProcessor(HlsParser parser,
                         String outputDir,
                         String outputFile,
                         HlsParser.Fetcher fetcher,
                         Decryptor decryptor,
                         StateManager stateManager,
                         SegmentCombiner segmentCombiner,
                         DownloadProgressCallback progressCallback,
                         PostProcessingCallback postProcessingCallback,
                         AfterPostProcessingCallback afterPostProcessingCallback) {
        this.parser = parser;
        this.outputDir = outputDir;
        this.outputFile = outputFile;
        String stateFile = outputDir + "/download_state.txt";
        this.fetcher = fetcher != null ? fetcher : new DefaultFetcher();
        this.decryptor = decryptor != null ? decryptor : new DefaultDecryptor();
        this.stateManager = stateManager != null ? stateManager : new DefaultStateManager(stateFile);
        this.segmentCombiner = segmentCombiner != null ? segmentCombiner : new DefaultSegmentCombiner();
        this.progressCallback = progressCallback != null ? progressCallback : (progress, total) -> {};
        this.postProcessingCallback = postProcessingCallback != null ? postProcessingCallback : () -> {};
        this.afterPostProcessingCallback = afterPostProcessingCallback != null ? afterPostProcessingCallback : () -> {};
        this.isCancelled = new AtomicBoolean(false);
    }

    /**
     * Downloads the HLS media playlist and its segments, with support for multi-threading.
     *
     * @param uri The URI of the HLS playlist.
     * @throws IOException If downloading or parsing fails.
     */
    public void download(URI uri) throws IOException {
        // Load previous state
        Set<Integer> completedIndices = stateManager.loadState();
        stateManager.saveState(completedIndices); // Initial save to ensure file exists

        // Parse the playlist if not already parsed
        if (playlist == null) {
            playlist = parser.parse(uri);
            if (playlist.getSegments().isEmpty()) {
                throw new IOException("No segments found in the playlist");
            }
        }

        List<Segment> segments = playlist.getSegments();

        // Pre-fetch all unique keys
        Set<HlsParser.EncryptionInfo> uniqueEncryptionInfos = new HashSet<>();
        for (HlsParser.Segment segment : segments) {
            HlsParser.EncryptionInfo encryptionInfo = segment.getEncryptionInfo();
            if (encryptionInfo != null && encryptionInfo.getKey() == null) {
                uniqueEncryptionInfos.add(encryptionInfo);
            }
        }
        for (HlsParser.EncryptionInfo encryptionInfo : uniqueEncryptionInfos) {
            try (InputStream keyStream = fetcher.fetchContent(encryptionInfo.getUri())) {
                byte[] key = keyStream.readAllBytes();
                encryptionInfo.setKey(key);
            }
        }

        // Create output directory
        Files.createDirectories(Paths.get(outputDir));

        // Download segments in parallel
        List<String> segmentFiles = Collections.synchronizedList(new ArrayList<>());
        ConcurrentSkipListSet<Integer> completedSet = new ConcurrentSkipListSet<>(completedIndices);
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        CountDownLatch latch = new CountDownLatch(segments.size() - completedSet.size());

        for (int i = 0; i < segments.size(); i++) {
            if (completedSet.contains(i)) continue; // Skip already completed segments
            int index = i;
            HlsParser.Segment segment = segments.get(i);
            executor.submit(() -> {
                try {
                    String segmentFile = outputDir + "/segment_" + (index + 1) + ".ts";
                    try (InputStream in = processSegment(segment)) {
                        Files.copy(in, Paths.get(segmentFile));
                    }
                    segmentFiles.add(segmentFile);
                    completedSet.add(index); // Add to completed set on success
                    synchronized (stateManager) {
                        stateManager.saveState(new HashSet<>(completedSet)); // Save state
                    }
                    progressCallback.onProgressUpdate(completedSet.size(), segments.size());
                    if (Thread.currentThread().isInterrupted() || isCancelled.get()) {
                        throw new IOException("Download cancelled");
                    }
                } catch (IOException e) {
                    postProcessingCallback.onPostProcessingComplete(); // Trigger cancellation callback
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } finally {
            executor.shutdown();
        }

        if (!segmentFiles.isEmpty()) {
            // Verify all required segment files exist before combining
            for (int i = 0; i < segments.size(); i++) {
                String segmentFile = outputDir + "/segment_" + (i + 1) + ".ts";
                if (completedSet.contains(i) && !Files.exists(Paths.get(segmentFile))) {
                    throw new IOException("Required segment file missing: " + segmentFile);
                }
            }

            // Combine segments
            segmentCombiner.combineSegments(outputDir, outputFile, segments.size());

            // Trigger post-processing
            postProcessingCallback.onPostProcessingComplete();

            // Clean up state file after post-processing
            stateManager.cleanupState();

            // Trigger after post-processing callback
            afterPostProcessingCallback.onAfterPostProcessingComplete();
        }
    }

    /**
     * Processes a segment by fetching its content and decrypting it if necessary.
     *
     * @param segment The segment to process.
     * @return An InputStream containing the processed (decrypted if needed) segment data.
     * @throws IOException If fetching or decryption fails.
     */
    private InputStream processSegment(HlsParser.Segment segment) throws IOException {
        InputStream segmentStream = fetcher.fetchContent(segment.getUri());
        if (segment.getEncryptionInfo() != null) {
            byte[] key = segment.getEncryptionInfo().getKey();
            if (key == null) {
                throw new IllegalStateException("Key should be pre-fetched for encryption info: " + segment.getEncryptionInfo().getUri());
            }
            return decryptor.decrypt(segmentStream, key, segment.getEncryptionInfo());
        }
        return segmentStream;
    }

    /**
     * Cancels the ongoing download.
     */
    public void cancel() {
        isCancelled.set(true);
    }

    /**
     * Interface for managing download state, including loading, saving, and cleaning up state.
     */
    public interface StateManager {
        /**
         * Loads the set of completed segment indices from persistent storage.
         *
         * @return The set of completed segment indices, or an empty set if no state exists.
         * @throws IOException If an error occurs while reading the state.
         */
        Set<Integer> loadState() throws IOException;

        /**
         * Saves the set of completed segment indices to persistent storage.
         *
         * @param completedIndices The set of completed segment indices to save.
         * @throws IOException If an error occurs while writing the state.
         */
        void saveState(Set<Integer> completedIndices) throws IOException;

        /**
         * Cleans up the state file or storage after the download is complete.
         *
         * @throws IOException If an error occurs while cleaning up the state.
         */
        void cleanupState() throws IOException;
    }

    /**
     * Default implementation of StateManager using file-based persistence.
     */
    private static class DefaultStateManager implements StateManager {
        private final String stateFile;

        DefaultStateManager(String stateFile) {
            this.stateFile = stateFile;
        }

        @Override
        public Set<Integer> loadState() throws IOException {
            if (Files.exists(Paths.get(stateFile))) {
                String content = Files.readString(Paths.get(stateFile)).trim();
                if (content.isEmpty()) {
                    return new HashSet<>();
                }
                return Arrays.stream(content.split(","))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .collect(Collectors.toSet());
            }
            return new HashSet<>();
        }

        @Override
        public void saveState(Set<Integer> completedIndices) throws IOException {
            Files.writeString(Paths.get(stateFile), completedIndices.stream()
                    .sorted()
                    .map(Object::toString)
                    .collect(Collectors.joining(",")));
        }

        @Override
        public void cleanupState() throws IOException {
            Files.deleteIfExists(Paths.get(stateFile));
        }
    }

    /**
     * Interface for combining segments into a single output file.
     */
    public interface SegmentCombiner {
        /**
         * Combines the downloaded segments into a single output file.
         *
         * @param outputDir    The directory containing the segment files.
         * @param outputFile   The path to the final combined file.
         * @param segmentCount The number of segments to combine.
         * @throws IOException If an error occurs during the combination process.
         */
        void combineSegments(String outputDir, String outputFile, int segmentCount) throws IOException;
    }

    /**
     * Default implementation of SegmentCombiner.
     */
    private static class DefaultSegmentCombiner implements SegmentCombiner {
        @Override
        public void combineSegments(String outputDir, String outputFile, int segmentCount) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
                for (int i = 1; i <= segmentCount; i++) {
                    String segmentFile = outputDir + "/segment_" + i + ".ts";
                    if (!Files.exists(Paths.get(segmentFile))) continue; // Skip missing segments (already processed)
                    try (FileInputStream fis = new FileInputStream(segmentFile)) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    Files.delete(Paths.get(segmentFile));
                }
            }
            System.out.println("Combined segments into: " + outputFile);
        }
    }

    /**
     * Interface for decrypting segments.
     */
    public interface Decryptor {
        /**
         * Decrypts an encrypted segment using the provided key and encryption info.
         *
         * @param encryptedStream The encrypted segment data.
         * @param key The decryption key.
         * @param encryptionInfo The encryption metadata (e.g., IV).
         * @return An InputStream containing the decrypted data.
         * @throws IOException If decryption fails.
         */
        InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo) throws IOException;
    }

    /**
     * Default implementation of Decryptor (no-op decryption for unencrypted segments).
     */
    private static class DefaultDecryptor implements Decryptor {
        @Override
        public InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo) throws IOException {
            // No-op: assumes the stream is already unencrypted
            return encryptedStream;
        }
    }

    /**
     * Default implementation of Fetcher using basic HTTP downloading.
     */
    private class DefaultFetcher implements HlsParser.Fetcher {
        HttpURLConnection connection;

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            disconnect();
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            return connection.getInputStream();
        }

        @Override
        public void disconnect() {
            if (connection != null) {
                connection.disconnect();
                connection = null;
            }
        }
    }

    /**
     * Callback interface for download progress updates, compatible with NewPipe's download system.
     */
    public interface DownloadProgressCallback {
        void onProgressUpdate(int progress, int totalSegments);
    }

    /**
     * Callback interface for post-processing, compatible with NewPipe's post-processing infrastructure.
     */
    public interface PostProcessingCallback {
        void onPostProcessingComplete();
    }

    /**
     * Callback interface for actions after post-processing is complete.
     */
    public interface AfterPostProcessingCallback {
        void onAfterPostProcessingComplete();
    }
}

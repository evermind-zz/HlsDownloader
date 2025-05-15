package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.MediaPlaylist;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.Segment;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A downloader for HLS media playlists, designed to be Android-agnostic with hooks for integration
 * into systems like NewPipe's giga downloader. Supports pause/resume functionality.
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
    private final AtomicBoolean isPaused;
    private final AtomicBoolean isCancelled;
    private final SegmentDownloader segmentDownloader;
    private MediaPlaylist playlist; // Store playlist for resuming
    int lastDownloadedSegmentIndex; // Track progress

    /**
     * Constructs a new HlsMediaProcessor.
     *
     * @param parser                    The HlsParser instance to parse playlists.
     * @param outputDir                 Directory to store temporary segments.
     * @param outputFile                Final output file path for the combined segments.
     * @param segmentDownloader         Use this downloader to download segments.
     * @param stateManager              Handles loading, saving, and cleaning up state.
     * @param segmentCombiner           Handles combining segments.
     * @param progressCallback          Callback for download progress updates.
     * @param postProcessingCallback    Callback for post-processing (e.g., format conversion in NewPipe).
     * @param afterPostProcessingCallback Callback for actions after post-processing.
     */
    public HlsMediaProcessor(HlsParser parser,
                         String outputDir,
                         String outputFile,
                         SegmentDownloader segmentDownloader,
                         StateManager stateManager,
                         SegmentCombiner segmentCombiner,
                         DownloadProgressCallback progressCallback,
                         PostProcessingCallback postProcessingCallback,
                         AfterPostProcessingCallback afterPostProcessingCallback) {
        this.parser = parser;
        this.outputDir = outputDir;
        this.outputFile = outputFile;
        String stateFile = outputDir + "/download_state.txt";
        this.segmentDownloader = segmentDownloader != null ? segmentDownloader : new DefaultDownloader();
        this.stateManager = stateManager != null ? stateManager : new DefaultStateManager(stateFile);
        this.segmentCombiner = segmentCombiner != null ? segmentCombiner : new DefaultSegmentCombiner();
        this.progressCallback = progressCallback != null ? progressCallback : (progress, total) -> {};
        this.postProcessingCallback = postProcessingCallback != null ? postProcessingCallback : () -> {};
        this.afterPostProcessingCallback = afterPostProcessingCallback != null ? afterPostProcessingCallback : () -> {};
        this.isPaused = new AtomicBoolean(false);
        this.isCancelled = new AtomicBoolean(false);
        this.lastDownloadedSegmentIndex = -1;
    }

    /**
     * Downloads the HLS media playlist and its segments, with support for pause/resume.
     *
     * @param uri The URI of the HLS playlist.
     * @throws IOException If downloading or parsing fails.
     */
    public void download(URI uri) throws IOException {
        // Load previous state if resuming
        lastDownloadedSegmentIndex = stateManager.loadState();
        stateManager.saveState(lastDownloadedSegmentIndex); // Save initial state

        // Parse the playlist if not already parsed
        if (playlist == null) {
            playlist = parser.parse(uri);
            if (playlist.getSegments().isEmpty()) {
                throw new IOException("No segments found in the playlist.");
            }
        }

        // Create output directory
        Files.createDirectories(Paths.get(outputDir));

        // Download segments starting from the last downloaded index
        int segmentCount = playlist.getSegments().size();
        for (int i = lastDownloadedSegmentIndex + 1; i < segmentCount; i++) {
            // Check for pause or cancellation
            while (isPaused.get()) {
                try {
                    Thread.sleep(1000); // Wait until resumed
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted during pause.");
                }
            }
            if (isCancelled.get()) {
                throw new IOException("Download cancelled.");
            }

            Segment segment = playlist.getSegments().get(i);
            String segmentFileName = outputDir + "/segment_" + (i + 1) + ".ts";
            downloadSegment(segment.getUri(), segmentFileName);

            // Update progress
            lastDownloadedSegmentIndex = i;
            stateManager.saveState(lastDownloadedSegmentIndex); // Save progress after each segment
            int progressPercent = (int) (((i + 1) / (double) segmentCount) * 100);
            progressCallback.onProgressUpdate(progressPercent, segmentCount);
        }

        // Combine segments using the SegmentCombiner
        segmentCombiner.combineSegments(outputDir, outputFile, segmentCount);

        // Trigger post-processing
        postProcessingCallback.onPostProcessingComplete();

        // Clean up state file after post-processing
        stateManager.cleanupState();

        // Trigger after post-processing callback
        afterPostProcessingCallback.onAfterPostProcessingComplete();
    }

    private class DefaultDownloader implements SegmentDownloader {
        HttpURLConnection connection;
        @Override
        public InputStream download(URI uri) throws IOException {
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
     * Downloads a single segment.
     *
     * @param segmentUri URI of the segment.
     * @param fileName   File path to save the segment.
     * @throws IOException If downloading fails.
     */
    void downloadSegment(URI segmentUri, String fileName) throws IOException {
        try (InputStream in = segmentDownloader.download(segmentUri);
             FileOutputStream out = new FileOutputStream(fileName)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (isPaused.get() || isCancelled.get()) {
                    throw new IOException(isPaused.get() ? "Download paused." : "Download cancelled.");
                }
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            segmentDownloader.disconnect();
        }
    }

    /**
     * Pauses the ongoing download.
     */
    public void pause() {
        isPaused.set(true);
    }

    /**
     * Resumes a paused download.
     */
    public void resume() {
        isPaused.set(false);
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
         * Loads the last downloaded segment index from persistent storage.
         *
         * @return The last downloaded segment index, or -1 if no state exists.
         * @throws IOException If an error occurs while reading the state.
         */
        int loadState() throws IOException;

        /**
         * Saves the current downloaded segment index to persistent storage.
         *
         * @param index The last downloaded segment index to save.
         * @throws IOException If an error occurs while writing the state.
         */
        void saveState(int index) throws IOException;

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
        public int loadState() throws IOException {
            File file = new File(stateFile);
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line = reader.readLine();
                    if (line != null) {
                        return Integer.parseInt(line.trim());
                    }
                }
            }
            return -1; // Default value if no state exists
        }

        @Override
        public void saveState(int index) throws IOException {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(stateFile))) {
                writer.write(String.valueOf(index));
            }
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
                    try (FileInputStream fis = new FileInputStream(segmentFile)) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    // Delete the segment file after combining
                    Files.delete(Paths.get(segmentFile));
                }
            }
            System.out.println("Combined segments into: " + outputFile);
        }
    }

    /**
     * Download interface to implement custom downloader.
     */
    public interface SegmentDownloader {
        InputStream download(URI uri) throws IOException;
        void disconnect();
    }

    /**
     * Callback interface for download progress updates, compatible with NewPipe's download system.
     */
    public interface DownloadProgressCallback {
        void onProgressUpdate(int progressPercent, int totalSegments);
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
        /**
         * Called after post-processing and segment combination are complete.
         * This can be used for additional cleanup or notifications.
         */
        void onAfterPostProcessingComplete();
    }
}

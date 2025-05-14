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
    private final String stateFile; // File to store download state
    private final DownloadProgressCallback progressCallback;
    private final PostProcessingCallback postProcessingCallback;
    protected final AtomicBoolean isPaused;
    protected final AtomicBoolean isCancelled;
    private MediaPlaylist playlist; // Store playlist for resuming
    int lastDownloadedSegmentIndex; // Track progress

    /**
     * Constructs a new HlsMediaProcessor.
     *
     * @param parser                The HlsParser instance to parse playlists.
     * @param outputDir             Directory to store temporary segments.
     * @param outputFile            Final output file path for the combined segments.
     * @param progressCallback      Callback for download progress updates (for NewPipe integration).
     * @param postProcessingCallback Callback for post-processing (e.g., format conversion in NewPipe).
     */
    public HlsMediaProcessor(HlsParser parser, String outputDir, String outputFile,
                         DownloadProgressCallback progressCallback, PostProcessingCallback postProcessingCallback) {
        this.parser = parser;
        this.outputDir = outputDir;
        this.outputFile = outputFile;
        this.stateFile = outputDir + "/download_state.txt"; // Store state in outputDir
        this.progressCallback = progressCallback != null ? progressCallback : (progress, total) -> {};
        this.postProcessingCallback = postProcessingCallback != null ? postProcessingCallback : () -> {};
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
        loadState();

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
            saveState(); // Save progress after each segment
            int progressPercent = (int) (((i + 1) / (double) segmentCount) * 100);
            progressCallback.onProgressUpdate(progressPercent, segmentCount);
        }

        // Combine segments
        combineSegments(segmentCount);

        // Clean up state file
        Files.deleteIfExists(Paths.get(stateFile));

        // Trigger post-processing
        postProcessingCallback.onPostProcessingComplete();
    }

    /**
     * Downloads a single segment.
     *
     * @param segmentUri URI of the segment.
     * @param fileName   File path to save the segment.
     * @throws IOException If downloading fails.
     */
    void downloadSegment(URI segmentUri, String fileName) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) segmentUri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        try (InputStream in = connection.getInputStream();
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
            connection.disconnect();
        }
    }

    /**
     * Combines downloaded segments into a single file.
     *
     * @param segmentCount Number of segments to combine.
     * @throws IOException If file operations fail.
     */
    void combineSegments(int segmentCount) throws IOException {
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
     * Saves the current download state to a file.
     */
    protected void saveState() throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(stateFile))) {
            writer.write(String.valueOf(lastDownloadedSegmentIndex));
        }
    }

    /**
     * Loads the download state from a file.
     */
    private void loadState() throws IOException {
        File file = new File(stateFile);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line = reader.readLine();
                if (line != null) {
                    lastDownloadedSegmentIndex = Integer.parseInt(line.trim());
                }
            }
        }
    }

    // ===== Integration Hooks for NewPipe =====

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
}
package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.MediaPlaylist;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.Segment;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * A downloader for HLS media playlists, designed to be Android-agnostic with hooks for integration
 * into systems like NewPipe's giga downloader. Supports pause/resume functionality and multi-threading.
 */
public class HlsMediaProcessor {
    private final HlsParser parser;
    private final String outputDir;
    private final String outputFile;
    private final SegmentStateManager segmentStateManager;
    private final DownloadProgressCallback progressCallback;
    private final DownloadStateCallback stateCallback;
    private final SegmentCombiner segmentCombiner;
    private final AtomicBoolean isCancelled;
    private final AtomicBoolean isPaused;
    private final HlsParser.Fetcher fetcher;
    private final Decryptor decryptor;
    private final int numThreads;
    private MediaPlaylist playlist; // Store playlist for resuming
    private ExecutorService executor;
    private CountDownLatch pauseLatch; // For pausing threads
    private final AtomicReference<DownloadState> lastNotifiedState; // Track last notified state

    // Enum for download states
    public enum DownloadState {
        STARTED, PAUSED, RESUMED, CANCELLED, COMPLETED, ERROR
    }

    // Error message constants
    private static final String ERROR_NO_SEGMENTS = "No segments found in playlist";
    private static final String ERROR_PARSING_PLAYLIST = "Failed to parse playlist: %s";
    private static final String ERROR_FETCHING_KEY = "Failed to fetch key: %s";
    private static final String ERROR_CREATING_DIRECTORY = "Failed to create output directory: %s";
    private static final String ERROR_MISSING_SEGMENT = "Missing segment file: %s";
    private static final String MESSAGE_CANCELLED_BY_USER = "Cancelled by user";
    private static final String MESSAGE_INTERRUPTED = "Interrupted: %s";

    /**
     * Constructs a new HlsMediaProcessor.
     *
     * @param parser                    The HlsParser instance to parse playlists.
     * @param outputDir                 Directory to store temporary segments.
     * @param outputFile                Final output file path for the combined segments.
     * @param fetcher                   Use this fetcher to download segments and keys.
     * @param decryptor                 Use this decryptor to decrypt segments.
     * @param numThreads                Number of threads to use for parallel downloads.
     * @param segmentStateManager       Handles loading, saving, and cleaning up segment state.
     * @param segmentCombiner           Handles combining segments.
     * @param progressCallback          Callback for download progress updates.
     * @param stateCallback             Callback for download state changes.
     */
    public HlsMediaProcessor(HlsParser parser,
                         String outputDir,
                         String outputFile,
                         HlsParser.Fetcher fetcher,
                         Decryptor decryptor,
                         int numThreads,
                         SegmentStateManager segmentStateManager,
                         SegmentCombiner segmentCombiner,
                         DownloadProgressCallback progressCallback,
                         DownloadStateCallback stateCallback) {
        this.parser = parser;
        this.outputDir = outputDir;
        this.outputFile = outputFile;
        String stateFile = outputDir + "/download_state.txt";
        this.fetcher = fetcher != null ? fetcher : new DefaultFetcher();
        this.decryptor = decryptor != null ? decryptor : new DefaultDecryptor();
        this.numThreads = Math.max(1, numThreads);
        this.segmentStateManager = segmentStateManager != null ? segmentStateManager : new DefaultSegmentStateManager(stateFile);
        this.segmentCombiner = segmentCombiner != null ? segmentCombiner : new DefaultSegmentCombiner();
        this.progressCallback = progressCallback != null ? progressCallback : (progress, total) -> {};
        this.stateCallback = stateCallback != null ? stateCallback : (state, message) -> {};
        this.isCancelled = new AtomicBoolean(false);
        this.isPaused = new AtomicBoolean(false);
        this.lastNotifiedState = new AtomicReference<>(DownloadState.STARTED);
    }

    /**
     * Downloads the HLS media playlist and its segments, with support for multi-threading.
     *
     * @param uri The URI of the HLS playlist.
     * @throws IOException If an I/O error occurs during download, parsing, or state management.
     */
    public void download(URI uri) throws IOException {
        // Load previous state
        Set<Integer> completedIndices = segmentStateManager.loadState();
        segmentStateManager.saveState(completedIndices); // Initial save to ensure file exists

        // Parse the playlist if not already parsed
        if (playlist == null) {
            try {
                playlist = parser.parse(uri);
                if (playlist.getSegments().isEmpty()) {
                    synchronized (lastNotifiedState) {
                        stateCallback.onDownloadState(DownloadState.ERROR, ERROR_NO_SEGMENTS);
                        lastNotifiedState.set(DownloadState.ERROR);
                    }
                    throw new IOException(ERROR_NO_SEGMENTS);
                }
            } catch (IOException e) {
                synchronized (lastNotifiedState) {
                    String message = String.format(ERROR_PARSING_PLAYLIST, e.getMessage());
                    stateCallback.onDownloadState(DownloadState.ERROR, message);
                    lastNotifiedState.set(DownloadState.ERROR);
                }
                throw e;
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
            } catch (IOException e) {
                synchronized (lastNotifiedState) {
                    String message = String.format(ERROR_FETCHING_KEY, e.getMessage());
                    stateCallback.onDownloadState(DownloadState.ERROR, message);
                    lastNotifiedState.set(DownloadState.ERROR);
                }
                throw e;
            }
        }

        // Create output directory
        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (IOException e) {
            synchronized (lastNotifiedState) {
                String message = String.format(ERROR_CREATING_DIRECTORY, e.getMessage());
                stateCallback.onDownloadState(DownloadState.ERROR, message);
                lastNotifiedState.set(DownloadState.ERROR);
            }
            throw e;
        }

        // Initialize executor and latch
        executor = Executors.newFixedThreadPool(numThreads);
        pauseLatch = new CountDownLatch(1);
        synchronized (lastNotifiedState) {
            if (lastNotifiedState.get() == DownloadState.STARTED) {
                stateCallback.onDownloadState(DownloadState.STARTED, "");
                lastNotifiedState.set(DownloadState.STARTED);
            }
        }

        // Download segments in parallel
        List<String> segmentFiles = Collections.synchronizedList(new ArrayList<>());
        ConcurrentSkipListSet<Integer> completedSet = new ConcurrentSkipListSet<>(completedIndices);
        CountDownLatch latch = new CountDownLatch(segments.size() - completedSet.size());

        for (int i = 0; i < segments.size(); i++) {
            if (completedSet.contains(i)) continue;
            int index = i;
            HlsParser.Segment segment = segments.get(i);
            executor.submit(() -> {
                try {
                    while (isPaused.get()) {
                        pauseLatch.await();
                        if (Thread.currentThread().isInterrupted()) break;
                    }
                    if (isCancelled.get() || Thread.currentThread().isInterrupted()) return;
                    String segmentFile = outputDir + "/segment_" + (index + 1) + ".ts";
                    try (InputStream in = processSegment(segment)) {
                        Files.copy(in, Paths.get(segmentFile), StandardCopyOption.REPLACE_EXISTING);
                    }
                    segmentFiles.add(segmentFile);
                    completedSet.add(index);
                    synchronized (segmentStateManager) {
                        segmentStateManager.saveState(new HashSet<>(completedSet));
                    }
                    progressCallback.onProgressUpdate(completedSet.size(), segments.size());
                } catch (IOException | InterruptedException e) {
                    // Ignore, let cancellation or pause handle the state
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
            if (isCancelled.get()) {
                synchronized (lastNotifiedState) {
                    if (lastNotifiedState.get() != DownloadState.CANCELLED) {
                        stateCallback.onDownloadState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
                        lastNotifiedState.set(DownloadState.CANCELLED);
                    }
                }
                segmentStateManager.cleanupState();
            } else if (isPaused.get()) {
                synchronized (lastNotifiedState) {
                    if (lastNotifiedState.get() != DownloadState.PAUSED && lastNotifiedState.get() != DownloadState.RESUMED) {
                        stateCallback.onDownloadState(DownloadState.PAUSED, "");
                        lastNotifiedState.set(DownloadState.PAUSED);
                    }
                }
            } else if (completedSet.size() == segments.size()) {
                for (int i = 0; i < segments.size(); i++) {
                    String segmentFile = outputDir + "/segment_" + (i + 1) + ".ts";
                    if (completedSet.contains(i) && !Files.exists(Paths.get(segmentFile))) {
                        synchronized (lastNotifiedState) {
                            String message = String.format(ERROR_MISSING_SEGMENT, segmentFile);
                            stateCallback.onDownloadState(DownloadState.ERROR, message);
                            lastNotifiedState.set(DownloadState.ERROR);
                        }
                        throw new IOException(String.format(ERROR_MISSING_SEGMENT, segmentFile));
                    }
                }
                segmentCombiner.combineSegments(outputDir, outputFile, segments.size());
                synchronized (lastNotifiedState) {
                    if (lastNotifiedState.get() != DownloadState.COMPLETED) {
                        stateCallback.onDownloadState(DownloadState.COMPLETED, "");
                        lastNotifiedState.set(DownloadState.COMPLETED);
                    }
                }
                segmentStateManager.cleanupState();
            }
        } catch (InterruptedException e) {
            synchronized (lastNotifiedState) {
                if (lastNotifiedState.get() != DownloadState.CANCELLED) {
                    String message = String.format(MESSAGE_INTERRUPTED, e.getMessage());
                    stateCallback.onDownloadState(DownloadState.CANCELLED, message);
                    lastNotifiedState.set(DownloadState.CANCELLED);
                }
            }
        } finally {
            if (executor != null) executor.shutdown();
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
        pauseLatch.countDown(); // Release waiting threads
        pauseLatch = new CountDownLatch(1); // Reset for next pause
        synchronized (lastNotifiedState) {
            if (lastNotifiedState.get() == DownloadState.PAUSED) {
                stateCallback.onDownloadState(DownloadState.RESUMED, "");
                lastNotifiedState.set(DownloadState.RESUMED);
            }
        }
    }

    /**
     * Cancels the ongoing download.
     */
    public void cancel() {
        isCancelled.set(true);
        if (executor != null) {
            executor.shutdownNow(); // Interrupt all threads
        }
    }

    /**
     * Interface for managing segment download state, including loading, saving, and cleaning up state.
     */
    public interface SegmentStateManager {
        Set<Integer> loadState() throws IOException;
        void saveState(Set<Integer> completedIndices) throws IOException;
        void cleanupState() throws IOException;
    }

    /**
     * Default implementation of SegmentStateManager using file-based persistence.
     */
    private static class DefaultSegmentStateManager implements SegmentStateManager {
        private final String stateFile;

        DefaultSegmentStateManager(String stateFile) {
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
                    if (!Files.exists(Paths.get(segmentFile))) continue;
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
        InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo) throws IOException;
    }

    /**
     * Default implementation of Decryptor (no-op decryption for unencrypted segments).
     */
    private static class DefaultDecryptor implements Decryptor {
        @Override
        public InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo) throws IOException {
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
     * Callback interface for download progress updates.
     */
    public interface DownloadProgressCallback {
        void onProgressUpdate(int progress, int totalSegments);
    }

    /**
     * Callback interface for download state changes.
     */
    public interface DownloadStateCallback {
        void onDownloadState(DownloadState state, String message);
    }
}

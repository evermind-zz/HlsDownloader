package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.MediaPlaylist;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.Segment;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicBoolean cancellationRequested;
    private final HlsParser.Fetcher fetcher;
    private final Decryptor decryptor;
    private final int numThreads;
    private MediaPlaylist playlist; // Store playlist for resuming
    private ExecutorService executor;
    private CountDownLatch pauseLatch; // For pausing threads
    private AtomicReference<DownloadState> currentState; // Track current state

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
    private static final String ERROR_DECRYPTION_FAILED = "Decryption failed: %s";
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
        this.cancellationRequested = new AtomicBoolean(false);
        this.currentState = new AtomicReference<>(DownloadState.STARTED);
    }

    /**
     * Downloads the HLS media playlist and its segments, with support for multi-threading.
     *
     * @param uri The URI of the HLS playlist.
     * @throws IOException If an I/O error occurs during download, parsing, or state management.
     */
    public void download(URI uri) throws IOException {
        initializeState();
        List<Segment> segments = parsePlaylist(uri);
        fetchEncryptionKeys(segments);
        createOutputDirectory();

        try {
            downloadSegments(segments);
            finalizeDownload(segments);
        } catch (Exception e) {
            handleDownloadException(e);
        } finally {
            cleanupExecutor();
            if (isDownloadCancelled()) {
                segmentStateManager.cleanupState();
                updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
            }
        }
    }

    private void initializeState() throws IOException {
        Set<Integer> completedIndices = segmentStateManager.loadState();
        segmentStateManager.saveState(completedIndices); // Initial save to ensure file exists
        executor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r);
            t.setUncaughtExceptionHandler((thread, ex) -> {
                System.err.println("Thread " + thread.getName() + " terminated with exception: " + ex.getMessage());
                ex.printStackTrace();
            });
            return t;
        });
        pauseLatch = new CountDownLatch(1);
        updateState(DownloadState.STARTED, "");
    }

    private List<Segment> parsePlaylist(URI uri) throws IOException {
        if (playlist == null) {
            try {
                playlist = parser.parse(uri);
            } catch (IOException e) {
                String message = String.format(ERROR_PARSING_PLAYLIST, e.getMessage());
                updateState(DownloadState.ERROR, message);
                throw e;
            }

            if (playlist.getSegments().isEmpty()) {
                updateState(DownloadState.ERROR, ERROR_NO_SEGMENTS);
                throw new IOException(ERROR_NO_SEGMENTS);
            }
        }
        return playlist.getSegments();
    }

    private void fetchEncryptionKeys(List<Segment> segments) throws IOException {
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
                if (key.length != 16) {
                    throw new IOException("Invalid key length: expected 16 bytes, got " + key.length);
                }
                encryptionInfo.setKey(key);
            } catch (IOException e) {
                String message = String.format(ERROR_FETCHING_KEY, e.getMessage());
                updateState(DownloadState.ERROR, message);
                throw e;
            } finally {
                fetcher.disconnect();
            }
        }
    }

    private void createOutputDirectory() throws IOException {
        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (IOException e) {
            String message = String.format(ERROR_CREATING_DIRECTORY, e.getMessage());
            updateState(DownloadState.ERROR, message);
            throw e;
        }
    }

    private void downloadSegments(List<Segment> segments) throws IOException {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        ConcurrentSkipListSet<Integer> completedSet = new ConcurrentSkipListSet<>(segmentStateManager.loadState());
        AtomicInteger progress = new AtomicInteger(completedSet.size());

        for (int i = 0; i < segments.size(); i++) {
            if (completedSet.contains(i)) continue;
            int index = i;
            HlsParser.Segment segment = segments.get(i);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    handlePause();
                    if (isDownloadCancelled()) return;

                    String segmentFile = outputDir + "/segment_" + (index + 1) + ".ts";
                    try (InputStream in = processSegment(segment, index)) {
                        if (isDownloadCancelled()) {
                            throw new InterruptedIOException("Download cancelled during I/O");
                        }
                        Files.copy(in, Paths.get(segmentFile), StandardCopyOption.REPLACE_EXISTING);
                    }
                    completedSet.add(index);
                    synchronized (segmentStateManager) {
                        segmentStateManager.saveState(new HashSet<>(completedSet));
                    }
                    int currentProgress = progress.incrementAndGet();
                    progressCallback.onProgressUpdate(currentProgress, segments.size());
                    if (isDownloadCancelled()) {
                        throw new InterruptedIOException("Download cancelled after progress");
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to process segment " + (index + 1), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Segment download interrupted", e);
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void handlePause() throws InterruptedException {
        if (isPaused.get()) {
            try {
                pauseLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private void finalizeDownload(List<Segment> segments) throws IOException {
        if (isDownloadCancelled()) {
            segmentStateManager.cleanupState();
            updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
        } else if (isPaused.get()) {
            updateState(DownloadState.PAUSED, "");
        } else {
            List<Path> tsSegments = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                String segmentFile = outputDir + "/segment_" + (i + 1) + ".ts";
                Path tsFile = Paths.get(segmentFile);
                if (!Files.exists(tsFile)) {
                    String message = String.format(ERROR_MISSING_SEGMENT, segmentFile);
                    updateState(DownloadState.ERROR, message);
                    throw new IOException(message);
                }
                tsSegments.add(tsFile);
            }
            segmentCombiner.combineSegments(tsSegments, outputDir, outputFile);
            updateState(DownloadState.COMPLETED, "");
            segmentStateManager.cleanupState();
        }
    }

    private void handleDownloadException(Exception e) throws IOException {
        if (e instanceof CompletionException) {
            CompletionException ce = (CompletionException) e;
            if (ce.getCause() instanceof InterruptedIOException) {
                segmentStateManager.cleanupState();
                updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
            } else if (ce.getCause() instanceof RuntimeException && ce.getCause().getCause() instanceof IOException) {
                IOException ioException = (IOException) ce.getCause().getCause();
                updateState(DownloadState.ERROR, ioException.getMessage());
                throw ioException;
            } else if (ce.getCause() instanceof InterruptedException) {
                segmentStateManager.cleanupState();
                updateState(DownloadState.CANCELLED, MESSAGE_INTERRUPTED);
            } else {
                updateState(DownloadState.ERROR, ce.getCause().getMessage());
                throw new IOException(ce.getCause());
            }
        } else {
            throw new IOException(e);
        }
    }

    private void cleanupExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isDownloadCancelled() {
        return isCancelled.get() || cancellationRequested.get() || Thread.interrupted();
    }

    /**
     * Processes a segment by fetching its content and decrypting it if necessary.
     *
     * @param segment The segment to process.
     * @param segmentIndex The index of the segment in the playlist.
     * @return An InputStream containing the processed (decrypted if needed) segment data.
     * @throws IOException If fetching or decryption fails.
     */
    InputStream processSegment(HlsParser.Segment segment, int segmentIndex) throws IOException {
        if (isDownloadCancelled()) {
            throw new InterruptedIOException("Download cancelled");
        }
        InputStream segmentStream = null;
        try {
            segmentStream = fetcher.fetchContent(segment.getUri());
            if (isDownloadCancelled()) {
                throw new InterruptedIOException("Download cancelled during fetch");
            }
            if (segment.getEncryptionInfo() != null) {
                byte[] key = segment.getEncryptionInfo().getKey();
                if (key == null) {
                    throw new IllegalStateException("Key should be pre-fetched for encryption info: " + segment.getEncryptionInfo().getUri());
                }
                try {
                    return decryptor.decrypt(segmentStream, key, segment.getEncryptionInfo(), segmentIndex);
                } catch (GeneralSecurityException e) {
                    throw new IOException(String.format(ERROR_DECRYPTION_FAILED, e.getMessage()), e);
                }
            }
            return segmentStream;
        } finally {
            if (segmentStream != null) {
                try {
                    segmentStream.close();
                } catch (IOException e) {
                    System.err.println("Failed to close segment stream: " + e.getMessage());
                }
            }
            fetcher.disconnect(); // Safe disconnect call
        }
    }

    /**
     * Pauses the ongoing download.
     */
    public void pause() {
        isPaused.set(true);
        updateState(DownloadState.PAUSED, "");
    }

    /**
     * Resumes a paused download.
     */
    public void resume() {
        isPaused.set(false);
        pauseLatch.countDown(); // Release waiting threads
        pauseLatch = new CountDownLatch(1); // Reset for next pause
        updateState(DownloadState.RESUMED, "");
    }

    /**
     * Cancels the ongoing download.
     */
    public void cancel() {
        cancellationRequested.set(true);
        isCancelled.set(true);
        updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
        if (executor != null) {
            executor.shutdownNow(); // Interrupt all threads
        }
    }

    /**
     * Updates the download state and notifies the callback.
     *
     * @param state   The new state.
     * @param message The message associated with the state change.
     */
    private void updateState(DownloadState state, String message) {
        currentState.set(state);
        stateCallback.onDownloadState(state, message);
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
    public static class DefaultSegmentStateManager implements SegmentStateManager {
        private final String stateFile;

        public DefaultSegmentStateManager(String stateFile) {
            this.stateFile = stateFile;
        }

        @Override
        public Set<Integer> loadState() throws IOException {
            if (Files.exists(Paths.get(stateFile))) {
                String content = Files.readString(Paths.get(stateFile)).trim();
                return content.isEmpty() ? new HashSet<>() : Arrays.stream(content.split(","))
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
            System.out.println("State file cleaned up: " + stateFile);
        }
    }

    /**
     * Interface for combining segments into a single output file.
     */
    public interface SegmentCombiner {
        void combineSegments(List<Path> tsSegments, String outputDir, String outputFile) throws IOException;
    }

    /**
     * Default implementation of SegmentCombiner.
     */
    private static class DefaultSegmentCombiner implements SegmentCombiner {
        @Override
        public void combineSegments(List<Path> tsSegments, String outputDir, String outputFile) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
                for (Path tsFile : tsSegments) {
                    if (!Files.exists(tsFile)) continue;
                    try (FileInputStream fis = new FileInputStream(tsFile.toFile())) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    Files.delete(tsFile);
                }
            }
            System.out.println("Combined segments into: " + outputFile);
        }
    }

    /**
     * Interface for decrypting segments.
     */
    public interface Decryptor {
        InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo, int segmentIndex) throws IOException, GeneralSecurityException;
    }

    /**
     * Default implementation of Decryptor using AES-128-CBC.
     */
    public static class DefaultDecryptor implements Decryptor {
        @Override
        public InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo, int segmentIndex) throws IOException, GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            byte[] iv = parseIv(encryptionInfo.getIv(), segmentIndex);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            ByteArrayOutputStream decryptedOutput = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = encryptedStream.read(buffer)) != -1) {
                byte[] decryptedBlock = cipher.update(buffer, 0, bytesRead);
                if (decryptedBlock != null) {
                    decryptedOutput.write(decryptedBlock);
                }
            }
            byte[] finalBlock = cipher.doFinal();
            if (finalBlock != null) {
                decryptedOutput.write(finalBlock);
            }

            return new ByteArrayInputStream(decryptedOutput.toByteArray());
        }

        private byte[] parseIv(String ivStr, int segmentIndex) {
            if (ivStr == null || ivStr.isEmpty()) {
                byte[] iv = new byte[16];
                Arrays.fill(iv, (byte) 0);
                iv[15] = (byte) (segmentIndex & 0xFF);
                return iv;
            }
            if (ivStr.startsWith("0x")) {
                ivStr = ivStr.substring(2);
            }
            if (ivStr.length() != 32) {
                throw new IllegalArgumentException("IV hex string must represent 16 bytes, got " + ivStr.length() / 2 + " bytes");
            }
            byte[] iv = new byte[16];
            for (int i = 0; i < 16; i++) {
                String byteStr = ivStr.substring(i * 2, i * 2 + 2);
                iv[i] = (byte) Integer.parseInt(byteStr, 16);
            }
            return iv;
        }
    }

    /**
     * Default implementation of Fetcher using basic HTTP downloading.
     */
    public static class DefaultFetcher implements HlsParser.Fetcher {
        private HttpURLConnection connection;

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            disconnect(); // Ensure previous connection is closed
            try {
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                return connection.getInputStream();
            } catch (IOException e) {
                disconnect(); // Clean up on failure
                throw e;
            }
        }

        @Override
        public void disconnect() {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception e) {
                    System.err.println("Failed to disconnect HttpURLConnection: " + e.getMessage());
                } finally {
                    connection = null; // Reset to avoid reusing stale reference
                }
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

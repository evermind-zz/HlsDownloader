package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.hlsdownloader.common.Fetcher;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.MediaPlaylist;
import com.github.evermindzz.hlsdownloader.parser.HlsParser.Segment;

import com.github.evermindzz.legacyfilesutils.Files;
import com.github.evermindzz.legacyfilesutils.Files.StandardCopyOption;
import com.github.evermindzz.legacyfilesutils.LegacyInputStream;
import com.github.evermindzz.legacyfilesutils.Paths;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
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
    private final Fetcher fetcher;
    private final Decryptor decryptor;
    private final int numThreads;
    private final boolean doCleanupSegments;
    private MediaPlaylist playlist; // Store playlist for resuming
    private ExecutorService executor;
    private CountDownLatch pauseLatch; // For pausing threads
    private AtomicReference<DownloadState> currentState; // Track current state
    private List<Segment> segments;

    // Enum for download states
    public enum DownloadState {
        STARTED, PAUSED, RESUMED, CANCELLED, COMPLETED, ERROR, STOPPED
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

    private static final boolean DEBUG = false;

    private static final int MAX_DOWNLOAD_RETRIES = 3;
    private static final int DOWNLOAD_RETRY_DELAY_MS = 1000;

    private static final int UNUSED_INDEX = -1;
    /**
     * Constructs a new HlsMediaProcessor.
     *
     * @param parser                    The HlsParser instance to parse playlists.
     * @param outputDir                 Directory to store temporary segments.
     * @param outputFile                Final output file path for the combined segments.
     * @param fetcher                   Use this fetcher to download segments and keys.
     *                                  if null {@link DefaultFetcher} is used.
     * @param decryptor                 Use this decryptor to decrypt segments.
     *                                  if null {@link DefaultDecryptor} is used.
     * @param numThreads                Number of threads to use for parallel downloads.
     * @param segmentStateManager       Handles loading, saving, and cleaning up segment state.
     *                                  If null {@link DefaultSegmentStateManager} is used.
     * @param segmentCombiner           Handles combining segments.
     *                                  If null {@link DefaultSegmentCombiner} is used.
     * @param progressCallback          Callback for download progress updates.
     *                                  If null the user has progress feedback
     *                                  {@link DownloadProgressCallback}
     * @param stateCallback             Callback for download state changes.
     *                                  {@link DownloadState}, {@link DownloadStateCallback}
     *                                  If null the user has no state change progress
     * @param doCleanupSegments         cleanup the downloaded segments files.
     */
    public HlsMediaProcessor(HlsParser parser,
                         String outputDir,
                         String outputFile,
                         Fetcher fetcher,
                         Decryptor decryptor,
                         int numThreads,
                         SegmentStateManager segmentStateManager,
                         SegmentCombiner segmentCombiner,
                         DownloadProgressCallback progressCallback,
                         DownloadStateCallback stateCallback,
                         boolean doCleanupSegments) {
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
        this.doCleanupSegments = doCleanupSegments;
    }

    /**
     * Downloads the HLS media playlist and its segments, with support for multi-threading.
     * <p>
     * This method is what you usually want to use. It will download the playlist and all
     * the segments mentioned in the playlist. There are other methods marked 'Step mode method'.
     * that can be if you just want segment meta data or the like.
     *
     * @param uri          media HLS playlist URI. If URI is master Playlists make sure HlsParser
     *                     has {@link HlsParser.MasterPlaylistSelectionCallback} to select media playlist.
     * @throws IOException If an I/O error occurs during download, parsing, or state management.
     */
    public void download(URI uri) throws IOException {
        initializeState();
        List<Segment> segments = parsePlaylist(uri);
        fetchEncryptionKeys(segments);
        this.segments = segments;
        createOutputDirectory();

        try {
            downloadSegments();
            finalizeDownload();
        } catch (Exception e) {
            handleDownloadException(e);
        } finally {
            cleanupExecutor();
            if (isDownloadCancelled()) {
                updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
            }
            updateState(DownloadState.STOPPED, "All operations stopped. EOW");
        }
    }

    /**
     * Step mode method: parse a media playlist URL and get all segments meta data.
     *
     * @param uri         media playlist uri. If Uri is master Playlists make sure HlsParser has
     *                    {@link HlsParser.MasterPlaylistSelectionCallback} to select media playlist.
     * @return the parsed segment meta data of the playlist.
     * @throws IOException on I/O work
     */
    public List<Segment> getPlayListSegmentData(URI uri) throws IOException {
        return parsePlaylist(uri);
    }

    /**
     * Step mode method: retrieve encryption keys for given segments if needed.
     *
     * @param segments     the segments you want to retrieve the encryption keys for.
     * @throws IOException on I/O work
     */
    public void retrieveEncryptionKeys(List<Segment> segments) throws IOException {
        fetchEncryptionKeys(segments);
    }


    private void initializeState() throws IOException {
        Set<Integer> completedIndices = segmentStateManager.loadState();
        segmentStateManager.saveState(completedIndices); // Initial save to ensure file exists
        AtomicInteger threadId = new AtomicInteger();
        executor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r);
            t.setName("DownloaderNo-" + threadId.getAndIncrement());
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
            try (InputStream keyStream = callFetchContent(encryptionInfo.getUri(), UNUSED_INDEX)) {
                byte[] key = LegacyInputStream.readAllBytes(keyStream);
                if (key.length != 16) {
                    throw new IOException("Invalid key length: expected 16 bytes, got " + key.length);
                }
                encryptionInfo.setKey(key);
            } catch (IOException e) {
                String message = String.format(ERROR_FETCHING_KEY, e.getMessage());
                updateState(DownloadState.ERROR, message);
                throw e;
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

    private void downloadSegments() throws IOException {
        checkIfSegmentsAvailable();
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

                    File segmentFile = getSegmentFileName(index);
                    try (InputStream in = processSegment(segment, index)) {
                        if (isDownloadCancelled()) {
                            throw new DownloadCancelledException("Download cancelled during I/O");
                        }
                        Files.copy(in, segmentFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    completedSet.add(index);
                    synchronized (segmentStateManager) {
                        segmentStateManager.saveState(new HashSet<>(completedSet));
                    }
                    int currentProgress = progress.incrementAndGet();
                    progressCallback.onProgressUpdate(currentProgress, segments.size());
                    if (isDownloadCancelled()) {
                        throw new DownloadCancelledException("Download cancelled after progress");
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

    private void checkIfSegmentsAvailable() {
       if (null == segments) {
          throw new RuntimeException("please set segments before. Use setSegments()");
       }
    }

    private File getSegmentFileName(int index) {
        return Paths.get(outputDir + "/segment_" + (index + 1) + ".ts");
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

    /**
     * Step mode method: Finalize the download aka postprocessing via {@link SegmentCombiner} and cleanup.
     * @throws IOException on I/O work
     */
    public void finalizeDownload() throws IOException {
        checkIfSegmentsAvailable();
        if (isDownloadCancelled()) {
            segmentStateManager.cleanupState();
            updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
        } else if (isPaused.get()) {
            updateState(DownloadState.PAUSED, "");
        } else {
            List<File> tsSegments = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                File segmentFile = getSegmentFileName(i);
                if (!Files.exists(segmentFile)) {
                    String message = String.format(ERROR_MISSING_SEGMENT, segmentFile);
                    updateState(DownloadState.ERROR, message);
                    throw new IOException(message);
                }
                tsSegments.add(segmentFile);
            }
            segmentCombiner.combineSegments(tsSegments, outputDir, outputFile);
            if (doCleanupSegments) {
                cleanupSegmentsFiles();
            }
            updateState(DownloadState.COMPLETED, "");
            segmentStateManager.cleanupState();
        }
    }

    public void cleanupSegmentsFiles() {
        for (int i = 0; i < segments.size(); i++) {
            File segmentFile = getSegmentFileName(i);
            if (Files.exists(segmentFile)) {
               Files.delete(segmentFile);
            }
        }
    }

    void printStackTraces(String custom, Throwable e) {
        if (DEBUG) {
            SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            String timestamp = TIMESTAMP_FORMAT.format(new Date());
            System.out.println("### " + timestamp + " "
                    + custom + " >NAME:" + e.getClass().getSimpleName() + " >MSG:" + e.getMessage() + " >CAUSE: " + e.getCause()
                    + " |--> " + Thread.currentThread().getName() + " " + Arrays.toString(Thread.currentThread().getStackTrace()).replace(',', '\n')
                    + " |--> exceptionBacktrace " + Arrays.toString(e.getStackTrace()).replace(',', '\n'));
        }
    }

    private void handleDownloadException(Exception e) throws IOException {
        if (e instanceof CompletionException) {
            CompletionException ce = (CompletionException) e;
            if (ce.getCause() instanceof DownloadCancelledException) {
                printStackTraces("instanceof InterruptException", ce);
                segmentStateManager.cleanupState();
                updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
            } else if (ce.getCause() instanceof RuntimeException && ce.getCause().getCause() instanceof DownloadCancelledException) {
                printStackTraces("wrapped DownloadCancelledException", ce.getCause().getCause());
                updateState(DownloadState.CANCELLED, MESSAGE_CANCELLED_BY_USER);
            } else if (ce.getCause() instanceof InterruptedException) {
                printStackTraces("instanceof InterruptedException", ce.getCause());
                segmentStateManager.cleanupState();
                updateState(DownloadState.CANCELLED, MESSAGE_INTERRUPTED);
            } else if (ce.getCause() instanceof RuntimeException && null != ce.getCause().getCause()) {
                // we have another wrapped one here --> unwrap and rethrow as IOException
               throw new IOException(ce.getCause().getCause());
            } else {
                printStackTraces("CompletionException", ce.getCause());
                updateState(DownloadState.ERROR, ce.getCause().getMessage());
                throw new IOException(ce.getCause());
            }
        } else { // unhandled Exception
            printStackTraces("UnknownException", e.getCause());
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

    private InputStream callFetchContent(URI uri, int segmentIndex) throws IOException{
        int attempt = 0;
        InputStream stream = null;
        while (attempt < MAX_DOWNLOAD_RETRIES) {
            try {
                System.out.println("Fetching URI: " + uri + " (Attempt " + (attempt + 1) + ")");
                stream = fetcher.fetchContent(uri);
                break;
            } catch (SocketException | SocketTimeoutException e) {
                System.err.println(e.getClass().getSimpleName() + " while fetching " + uri + ": " + e.getMessage());
                attempt++;

                if (attempt == MAX_DOWNLOAD_RETRIES) {
                    throw e;
                }

                // sleep before retry
                try {
                    int delay = DOWNLOAD_RETRY_DELAY_MS * (1 << attempt); // Exponential backoff: 1s, 2s, 4s
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry delay", ie);
                }
            }
        }

        return stream;
    }

    /**
     * Step mode method: set the segments. Useful only in combination with the other 'Step mode methods'.
     * @param segments the segments you want to work with.
     */
    public void setSegments(List<HlsParser.Segment> segments) {
        this.segments = segments;
    }

    /**
     * Step mode method: process all segments that are available.
     * <p>
     * The processed segments are stored in {@link HlsMediaProcessor#outputDir}.
     * @param inputStreamProvider the stream for the segments.
     */
    public void processSegments(InputStreamProvider inputStreamProvider) {
        for (int i = 0; i < segments.size(); i++) {
            HlsParser.Segment segment = segments.get(i);
            try {
                File segmentFile = getSegmentFileName(i);
                try (InputStream in = processSegment(segment, i, inputStreamProvider)) {
                    Files.copy(in, segmentFile, StandardCopyOption.REPLACE_EXISTING);
                }

            } catch (IOException e) {
                throw new RuntimeException("Failed to process segment " + (i + 1), e);
            }
        }
    }

    @FunctionalInterface
    public interface InputStreamProvider {

        /**
         * Provide the segment input stream.
         *
         * @param uri           the stream can be selected by a uri or segmentIndex
         * @param segmentIndex  the stream can be selected by a segmentIndex or uri
         * @return the segment as InputStream.
         * @throws IOException  input output error
         */
        InputStream get(URI uri, int segmentIndex) throws IOException;
    }

    /**
     * Step mode method: Processes a segment by fetching its content and decrypting it if necessary.
     *
     * @param segment             The segment to process.
     * @param segmentIndex        The index of the segment in the playlist.
     * @param inputStreamProvider provide the InputStream of the segment
     * @return InputStream containing the processed (decrypted if needed) segment.
     * @throws IOException        If fetching or decryption fails.
     */
    public InputStream processSegment(HlsParser.Segment segment, int segmentIndex, InputStreamProvider inputStreamProvider) throws IOException {
        if (isDownloadCancelled()) {
            throw new DownloadCancelledException("Download cancelled");
        }

        InputStream originalStream = inputStreamProvider.get(segment.getUri(), segmentIndex);

        if (isDownloadCancelled()) {
            throw new DownloadCancelledException("Download cancelled during get");
        }
        if (segment.getEncryptionInfo() != null) {
            byte[] key = segment.getEncryptionInfo().getKey();
            if (key == null) {
                throw new IllegalStateException("Key should be pre-fetched for encryption info: " + segment.getEncryptionInfo().getUri());
            }
            try {
                // expect wrapped stream from Decryptor as return
                return decryptor.decrypt(originalStream, key, segment.getEncryptionInfo(), segmentIndex);
            } catch (GeneralSecurityException e) {
                try {
                    originalStream.close();
                } catch (IOException closeException) {
                    System.err.println("Failed to close original stream: " + closeException.getMessage());
                }
                throw new IOException(String.format(ERROR_DECRYPTION_FAILED, e.getMessage()), e);
            }
        }
        return originalStream; // Return original stream if no decryption
    }

    InputStream processSegment(HlsParser.Segment segment, int segmentIndex) throws IOException {
        return processSegment(segment, segmentIndex, this::callFetchContent);
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
        DownloadState previousState = currentState.getAndSet(state);
        if (previousState != state) {
            stateCallback.onDownloadState(state, message);
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
    public static class DefaultSegmentStateManager implements SegmentStateManager {
        private final File stateFile;

        public DefaultSegmentStateManager(String stateFile) {
            this.stateFile = Paths.get(stateFile);
        }

        @Override
        public Set<Integer> loadState() throws IOException {
            if (Files.exists(stateFile)) {
                String content = Files.readString(stateFile).trim();
                return content.isEmpty() ? new HashSet<>() : Arrays.stream(content.split(","))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .collect(Collectors.toSet());
            }
            return new HashSet<>();
        }

        @Override
        public void saveState(Set<Integer> completedIndices) throws IOException {
            Files.writeString(stateFile, completedIndices.stream()
                    .sorted()
                    .map(Object::toString)
                    .collect(Collectors.joining(",")));
        }

        @Override
        public void cleanupState() {
            Files.deleteIfExists(stateFile);
            System.out.println("State file cleaned up: " + stateFile);
        }
    }

    /**
     * Interface for combining segments into a single output file.
     */
    public interface SegmentCombiner {
        /**
         * combine the segments into a single files.
         * @param tsSegments a list of files representing the segments
         * @param outputDir  in which folder you should operate
         * @param outputFile the output file that should be used
         * @throws IOException I/O work
         */
        void combineSegments(List<File> tsSegments, String outputDir, String outputFile) throws IOException;
    }

    /**
     * Default implementation of SegmentCombiner. Just concat files together.
     */
    private static class DefaultSegmentCombiner implements SegmentCombiner {
        @Override
        public void combineSegments(List<File> tsSegments, String outputDir, String outputFile) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
                for (File tsFile : tsSegments) {
                    if (!Files.exists(tsFile)) continue;
                    try (FileInputStream fis = new FileInputStream(tsFile)) {
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
     * Default implementation of Decryptor using AES-128-CBC with streaming decryption and PKCS#7 padding.
     */
    public static class DefaultDecryptor implements Decryptor {
        @Override
        public InputStream decrypt(InputStream encryptedStream, byte[] key, HlsParser.EncryptionInfo encryptionInfo, int segmentIndex) throws IOException, GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // Updated to use PKCS#7 padding
            byte[] iv = parseIv(encryptionInfo.getIv(), segmentIndex);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return new CipherInputStream(encryptedStream, cipher);
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
    public static class DefaultFetcher implements Fetcher {

        @Override
        public InputStream fetchContent(URI uri) throws IOException {
            HttpURLConnection connection = null;
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            return connection.getInputStream();
        }
    }

    /**
     * Callback interface for download progress updates.
     */
    public interface DownloadProgressCallback {
        /**
         * get the progress during segments downloads.
         * @param progress already completed segments.
         * @param totalSegments total segments to download.
         */
        void onProgressUpdate(int progress, int totalSegments);
    }

    /**
     * Callback interface for download state changes.
     */
    public interface DownloadStateCallback {
        /**
         * See the state of the Downloader.
         * @param state what kind of state happened.
         * @param message sometimes a message for the user is included.
         */
        void onDownloadState(DownloadState state, String message);
    }

    private static class DownloadCancelledException extends  InterruptedIOException {
        public DownloadCancelledException(String msg) {
            super(msg);
        }
    }
}

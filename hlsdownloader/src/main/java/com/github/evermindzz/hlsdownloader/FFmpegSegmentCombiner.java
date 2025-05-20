package com.github.evermindzz.hlsdownloader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A segment combiner implementation that uses FFmpeg to concatenate .ts files into a single output file.
 * This class leverages FFmpeg's concat demuxer to perform the combination without re-encoding,
 * preserving the original video and audio streams for efficiency. It monitors FFmpeg output to
 * detect inactivity and terminates the process if no progress is made within a specified time.
 */
public class FFmpegSegmentCombiner implements HlsMediaProcessor.SegmentCombiner {
    private static final long INACTIVITY_THRESHOLD_SECONDS = 30; // Terminate if no output for 30 seconds
    private static final int LOG_EXECUTOR_TIMEOUT_SECONDS = 1;   // Timeout for log executor shutdown

    /**
     * Combines a list of .ts segment files into a single output file using FFmpeg.
     *
     * @param tsSegments List of Path objects pointing to the .ts segment files to combine.
     *                   The order of files must match the intended playback sequence.
     * @param outputDir  The directory where temporary files and the final output will be stored.
     * @param outputFile The full path (including filename and extension) of the resulting file.
     *                   The extension determines the container format (e.g., .mp4, .mkv, .ts).
     *                   Ensure FFmpeg supports the chosen format with stream copying (-c copy).
     * @throws IOException            If file operations or FFmpeg execution fails.
     * @throws FFmpegCombinationException If the FFmpeg process is interrupted, times out, or stalls.
     */
    @Override
    public void combineSegments(List<Path> tsSegments, String outputDir, String outputFile) throws IOException {
        if (tsSegments == null || tsSegments.isEmpty()) {
            throw new IllegalArgumentException("Segment list cannot be null or empty");
        }

        try {
            combineTsToContainer(tsSegments, outputDir, outputFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupted status
            throw new FFmpegCombinationException("FFmpeg combination interrupted", e);
        }
        System.out.println("Combined segments into: " + outputFile); // Log completion
    }

    /**
     * Custom exception for FFmpeg-related combination failures.
     */
    private static class FFmpegCombinationException extends RuntimeException {
        FFmpegCombinationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Creates a temporary concat list file containing paths to the input .ts files.
     *
     * @param tsFiles   List of Path objects for the .ts files to include in the concat list.
     * @param outputDir Directory where the temporary concat list file will be created.
     * @return Path to the generated concat list file.
     * @throws IOException If file creation or writing fails.
     */
    private Path createConcatListFile(List<Path> tsFiles, String outputDir) throws IOException {
        Path concatFile = Files.createTempFile(Path.of(outputDir), "concat_list", ".lst");
        try (BufferedWriter writer = Files.newBufferedWriter(concatFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            writer.write("# FFmpeg concat list\n"); // Optional header for clarity
            for (Path tsFile : tsFiles) {
                String escapedPath = tsFile.toAbsolutePath().toString().replace("'", "'\\''"); // Escape single quotes
                writer.write("file '" + escapedPath + "'\n");
            }
        }
        return concatFile;
    }

    /**
     * Executes FFmpeg to combine the .ts files into a single output file without re-encoding.
     * The output container format is determined by the extension of the output file (e.g., .mp4, .mkv, .ts).
     * Monitors FFmpeg output to detect inactivity and terminates the process if no progress
     * is made within {@link #INACTIVITY_THRESHOLD_SECONDS}.
     *
     * @param tsFiles   List of Path objects for the .ts input files, ordered for playback.
     * @param outputDir Directory for temporary files and the output file.
     * @param outputFile The full path (including filename and extension) of the resulting file.
     * @throws IOException          If FFmpeg execution or file operations fail.
     * @throws InterruptedException If the FFmpeg process is interrupted.
     */
    private void combineTsToContainer(List<Path> tsFiles, String outputDir, String outputFile)
            throws IOException, InterruptedException {
        // Create concat list file
        Path concatList = createConcatListFile(tsFiles, outputDir);
        Process process = null;
        try (ExecutorService logExecutor = Executors.newSingleThreadExecutor()) {
            AtomicReference<Instant> lastOutputTime = new AtomicReference<>(Instant.now());
            try {
                // Configure FFmpeg process
                ProcessBuilder builder = new ProcessBuilder(
                        "ffmpeg",
                        "-f", "concat",
                        "-safe", "0",
                        "-i", concatList.toAbsolutePath().toString(),
                        "-c", "copy", // Copy streams without re-encoding
                        "-y",        // Overwrite output file if it exists
                        outputFile
                );
                builder.redirectErrorStream(true); // Merge stdout and stderr for logging
                process = builder.start();

                // Asynchronously log FFmpeg output and monitor inactivity
                Process finalProcess = process;
                logExecutor.submit(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(finalProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            System.out.println("[FFmpeg] " + line); // Log output
                            lastOutputTime.set(Instant.now());     // Update last output time
                        }
                    } catch (IOException e) {
                        System.err.println("Error reading FFmpeg output: " + e.getMessage());
                    }
                });

                // Watchdog to check for inactivity
                Instant start = Instant.now();
                while (process.isAlive()) {
                    Duration inactivityDuration = Duration.between(lastOutputTime.get(), Instant.now());
                    if (inactivityDuration.getSeconds() > INACTIVITY_THRESHOLD_SECONDS) {
                        System.err.println("FFmpeg stalled for " + INACTIVITY_THRESHOLD_SECONDS + " seconds, terminating...");
                        process.destroyForcibly();
                        throw new FFmpegCombinationException("FFmpeg stalled due to inactivity", null);
                    }
                    Thread.sleep(1000); // Check every second
                }

                // Measure execution time
                Duration duration = Duration.between(start, Instant.now());
                System.out.println("FFmpeg execution took " + duration.toMillis() + " ms");

                // Check exit code after process completes
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new IOException("FFmpeg failed with exit code: " + exitCode);
                }
            } finally {
                // Clean up process and temp file
                if (process != null) {
                    process.destroyForcibly(); // Ensure process is terminated
                }
                logExecutor.shutdown();
                try {
                    logExecutor.awaitTermination(LOG_EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    Files.deleteIfExists(concatList);
                } catch (IOException e) {
                    System.err.println("Failed to delete temp concat file: " + concatList + ", " + e.getMessage());
                }
            }
        }
    }
}
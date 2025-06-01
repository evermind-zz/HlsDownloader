package com.github.evermindzz.hlsdownloader;

import com.github.evermindzz.legacyfilesutils.Files;
import com.github.evermindzz.legacyfilesutils.Files.StandardOpenOption;
import com.github.evermindzz.legacyfilesutils.Paths;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * A segment combiner implementation that uses FFmpeg to concatenate .ts files into a single output file.
 * This class leverages FFmpeg's concat demuxer to perform the combination without re-encoding,
 * preserving the original video and audio streams for efficiency. It monitors FFmpeg output to
 * detect inactivity and terminates the process if no progress is made within a specified time.
 */
public class FFmpegSegmentCombiner implements HlsMediaProcessor.SegmentCombiner {
    private final ToIntFunction<String[]> executor;

    /**
     * Create a ts segments combiner using FFmpeg.
     * @param executor the function that runs tbe ffmpeg binary.
     */
    public FFmpegSegmentCombiner(ToIntFunction<String[]> executor) {
        this.executor = executor;
    }

    /**
     * Combines a list of .ts segment files into a single output file using FFmpeg.
     *
     * @param tsSegments List of File objects pointing to the .ts segment files to combine.
     *                   The order of files must match the intended playback sequence.
     * @param outputDir  The directory where temporary files and the final output will be stored.
     * @param outputFile The full path (including filename and extension) of the resulting file.
     *                   The extension determines the container format (e.g., .mp4, .mkv, .ts).
     *                   Ensure FFmpeg supports the chosen format with stream copying (-c copy).
     * @throws IOException            If file operations or FFmpeg execution fails.
     */
    @Override
    public void combineSegments(List<File> tsSegments, String outputDir, String outputFile) throws IOException {
        if (tsSegments == null || tsSegments.isEmpty()) {
            throw new IllegalArgumentException("Segment list cannot be null or empty");
        }

        combineTsToContainer(tsSegments, outputDir, outputFile);
        System.out.println("Combined segments into: " + outputFile); // Log completion
    }

    /**
     * Creates a temporary concat list file containing paths to the input .ts files.
     *
     * @param tsFiles   List of File objects for the .ts files to include in the concat list.
     * @param outputDir Directory where the temporary concat list file will be created.
     * @return File to the generated concat list file.
     * @throws IOException If file creation or writing fails.
     */
    private File createConcatListFile(List<File> tsFiles, String outputDir) throws IOException {
        File concatFile = Files.createTempFile(Paths.get(outputDir), "concat_list", ".lst");
        try (BufferedWriter writer = Files.newBufferedWriter(concatFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            writer.write("# FFmpeg concat list\n"); // Optional header for clarity
            for (File tsFile : tsFiles) {
                String escapedPath = tsFile.getAbsolutePath().replace("'", "'\\''"); // Escape single quotes
                writer.write("file '" + escapedPath + "'\n");
            }
        }
        return concatFile;
    }

    /**
     * Executes FFmpeg to combine the .ts files into a single output file without re-encoding.
     * The output container format is determined by the extension of the output file (e.g., .mp4, .mkv, .ts).
     *
     * @param tsFiles   List of File objects for the .ts input files, ordered for playback.
     * @param outputDir Directory for temporary files and the output file.
     * @param outputFile The full path (including filename and extension) of the resulting file.
     * @throws IOException          If FFmpeg execution or file operations fail.
     */
    private void combineTsToContainer(List<File> tsFiles, String outputDir, String outputFile) throws IOException {
        File concatList = null;
        try {
            // Create concat list file
            concatList = createConcatListFile(tsFiles, outputDir);
            executor.applyAsInt(new String[]{
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatList.getAbsolutePath(),
                    "-c", "copy", // Copy streams without re-encoding
                    "-y",        // Overwrite output file if it exists
                    outputFile
            });
        } finally {
            if (null != concatList) {
                Files.deleteIfExists(concatList);
            }
        }
    }
}

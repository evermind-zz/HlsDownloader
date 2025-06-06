# HlsDownloader

A Java library for downloading and processing HTTP Live Streaming (HLS) content,
including parsing M3U8 playlists and downloading media segments. This is an
Android-agnostic library but can be used in Android apps with desugaring
to support a minimum SDK of 19 (KitKat).

## Overview
HlsDownloader is a lightweight Java library designed to simplify working with
HLS streams. It provides two core classes: `HlsMediaProcessor.java` for downloading
and processing HLS content. `HlsParser.java` for parsing M3U8 playlist files
(both master and media playlists).

## Features
- Parse M3U8 playlists with `HlsParser.java`
- Download HLS media segments with `HlsMediaProcessor.java`
- Lightweight and easy to integrate
- Callbacks for state and progress updates
- Interfaces for custom content fetchers, decryptors, input stream providers and segment combiners
- One-stop method: `download(URI)` for end-to-end processing
- Step mode methods for fine-grained control, callable step by step as needed
- Additional `FFmpegSegmentCombiner` class that uses the `ffmpeg` binary to combine segments
  A lightweight FFmpeg-based TS-to-MP4 converter is available at [slimhls-converter](https://github.com/evermind-zz/slimhls-converter)

## Installation
### Add as a Dependency
#### Gradle
Include HlsDownloader in your `build.gradle` file:

```gradle
dependencies {
    implementation 'com.evermind-zz:hlsdownloader:1.0.0' // Replace with the latest version
}
```

#### Repository
Releases are distributed via jitpack. Make sure you include jitpack in
your `build.gradle`:

```gradle
repositories {
    maven { url "https://jitpack.io" }
    // other sources
}
```

## Library Usage
These examples illustrate potential usage of the library. They are not fully
functional out of the box and may require additional setup (e.g., custom
implementations, file paths, or dependencies like FFmpeg).

### Standard Usage of HlsMediaProcessor
For straightforward cases, the all-in-one `download(URI)` method fetches an
HLS stream and generates a single, ready-to-watch video file. You can customize
its behavior by providing your own implementations of the interfaces mentioned above.
For even more control, refer to [Step mode Methods](#step-mode-methods).

Additional examples (mocked tests) are available:
- [HlsParserTest](hlsdownloader/src/test/java/com/github/evermindzz/hlsdownloader/parser/HlsParserTest.java)
- [HlsMediaProcessorTest](hlsdownloader/src/test/java/com/github/evermindzz/hlsdownloader/HlsMediaProcessorTest.java)
- [HlsMediaProcessorDecryptorTest](hlsdownloader/src/test/java/com/github/evermindzz/hlsdownloader/HlsMediaProcessorDecryptorTest.java)

```java
import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;
// ...

public class Main {
  public static void main(String[] args) {
    RunBinary runBinary = new RunBinary("ffmpeg", "ffmpeg");
    String localTestUri = "https://example.com/input_hls/playlist.m3u8";
    String outputFile = "output.mp4";
    Fetcher defaultFetcher = new HlsMediaProcessor.DefaultFetcher();
    HlsParser = parser = new HlsParser(null /* no variants */, defaultFetcher, true /* strictMode */);
    HlsMediaProcessor hlsMediaProcessor = new HlsMediaProcessor(parser, outputDir, outputFile,
            defaultFetcher,
            null,     // use build in Decryptor
            2,        // two download threads
            new HlsMediaProcessor.DefaultSegmentStateManager(stateFile),
            new FFmpegSegmentCombiner(args -> {
              try {
                return runBinary.execute(args);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            }),
            (progress, total) -> System.out.println("Progress: " + progress + " of " + total),
            (state, message) -> System.out.println("State: " + state.name() + ", Message: " + message),
            true // cleanup temporary files
    );

    // Perform the download and processing at once
    hlsMediaProcessor.download(URI.create(localTestUri));
    // Final file is available at outputDir/outputFile
  }
}
```

### Step mode Methods
The `HlsMediaProcessor` class typically handles everything via `download(URI)`. For
more control, use the 'step mode method's to execute the process step by step.
Below are example scenarios.

#### 1. Storing Parsed Segment Data
Use `HlsParser` and `HlsMediaProcessor` to parse and store segment data from
a playlist without downloading.

```java
import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;
// ...

public class Main {
  public static void main(String[] args) {
    // Setup HlsParser and HlsMediaProcessor
    // you can replace DefaultFetcher with your implementation
    final Fetcher fetcher = new HlsMediaProcessor.DefaultFetcher();
    final HlsParser parser = new HlsParser(
            variants -> { }, // select variant if you parse a master playlist
                             // useful in combination with HlsMediaProcessor
            fetcher,
            false // strictMode
    );
    final HlsMediaProcessor hlsMediaProcessor = new HlsMediaProcessor(
            parser,
            null, // outputDir not needed
            null, // outputFile not needed
            fetcher,
            null,     // use built-in decryptor
            1,        // download threads: 1 as downloading is not used
            null,     // default segment state manager set but not used
            null,     // default segment combiner set but not used
            (progress, total) -> { /* ignored */ },
            (state, message) -> { /* ignored */ },
            false     // no cleanup
    );

    String playlistUrl = "https://example.com/path/to/playlist.m3u8";
    List<HlsParser.Segment> segments = hlsMediaProcessor.getPlayListSegmentData(playlistUrl);
    // Optional: Retrieve encryption keys if needed
    hlsMediaProcessor.retrieveEncryptionKeys(segments);

    // Serialize segments for later use
    yourSerializer(segments);
    // Or iterate over segments for custom processing
    for (final HlsParser.Segment segment : segments) {
        doSomethingWith(segment.getUri());
    }
    // ...
  }
}
```

#### 2. Processing Pre-Fetched Segments
Process already-downloaded segments (e.g., via a third party) for
decryption and combination into a single video file.

```java
import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;
// ...

public class Main {
  File tempDir = new File("path/to/tempDir");
  File remuxedFile = new File(tempDir, "output.mp4");

  public void processRawSegments(
          List<HlsParser.Segment> segments,
          InputStream[] segmentStreams,
          OutputStream outputStream)
          throws IOException, InterruptedException {
    SegmentInputStreamProvider segmentInputStreamProvider = new SegmentInputStreamProvider(segmentStreams);
    // Setup HlsMediaProcessor
    HlsMediaProcessor hlsMediaProcessor = new HlsMediaProcessor(
            null, // parser not needed; segments set later
            tempDir.getAbsolutePath(),
            remuxedFile.getAbsolutePath(),
            null,     // Default Fetcher
            null,     // Default Decryptor
            1,        // download threads: 1 as downloading is not used
            null,     // default segment state manager set but not used here.
            yourCombinerImplementation(), // custom combiner
            (progress, total) -> { /* ignored */ },
            (state, message) -> { /* ignored */ },
            true      // cleanup temporary files
    );

    hlsMediaProcessor.setSegments(segments);
    hlsMediaProcessor.processSegments(segmentInputStreamProvider);
    hlsMediaProcessor.finalizeDownload();
    Files.copy(remuxedFile.toPath(), outputStream);
  }

  public static void main(String[] args) throws Exception {
    // Recreate serialized parsed playlist segment data
    List<HlsParser.Segment> segments = yourDeserializer();
    // Combine segments into a single file
    new Main().processRawSegments(
            segments,
            getYourSegmentsInputStreams(),
            getYourSingleOutputStream()
    );
  }
}
```

## Building from Source

To build the library yourself:

```bash
git clone https://github.com/evermind-zz/HlsDownloader.git
cd HlsDownloader
./gradlew build
```

The compiled artifact will be in the `build/libs` directory.

## Contributing
Contributions are welcome! Follow these steps:
1. Fork the repository.
2. Create a new branch for your changes.
3. Submit a pull request with clear commit messages.


## License
This project is licensed under the GPLv3. See the [LICENSE](LICENSE)
file for details.

## Contact
For issues, feature requests, or questions, please open an issue on the
[GitHub repository](https://github.com/evermind-zz/HlsDownloader).

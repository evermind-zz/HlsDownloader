package com.github.evermindzz.hlsdownloader.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Interface for fetching content from a URI.
 */
public interface Fetcher {
    /**
     * Fetches content from the given URI and returns an InputStream.
     * This method must be thread-safe. The returned InputStream should override
     * the close() method to ensure the underlying network connection is properly
     * closed when the stream is closed.
     *
     * @param uri The URI to fetch content from.
     * @return An InputStream containing the fetched content.
     * @throws IOException If an I/O error occurs during fetching.
     */
    InputStream fetchContent(URI uri) throws IOException;
}

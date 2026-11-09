package com.downloader.request;

/** Runs PRDownloader's actual file task in JVM tests without starting an Android main Looper. */
public final class QuietDownloadRequest extends DownloadRequest {
    public QuietDownloadRequest(DownloadRequestBuilder builder) {
        super(builder);
    }

    @Override public void deliverStartEvent() {
        // No application start listener is needed by the transport/file integration tests.
    }
}

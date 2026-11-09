package nie.translator.rtranslator.downloader;

import com.downloader.Status;
import com.downloader.httpclient.HttpClient;
import com.downloader.internal.ComponentHolder;
import com.downloader.request.DownloadRequest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A transport adapter for PRDownloader, not a second download engine. The canonical URL,
 * download ID, temporary file, database, pause/resume and notifications remain PRDownloader's.
 * Each source is tried at most once per request, including retries after a short read.
 */
public final class FallbackHttpClient implements HttpClient {
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 15_000;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final int MAX_REDIRECTS = 5;

    interface Sources {
        List<String> urls(String canonicalUrl);
        ModelDownloadManifest.Entry metadata(String canonicalUrl);
    }

    private static final Sources MODEL_SOURCES = new Sources() {
        @Override public List<String> urls(String url) {
            // Only public, explicitly pinned model assets go through third-party proxies.
            if (ModelDownloadManifest.forUrl(url) == null) return Collections.singletonList(url);
            return DownloadSourceOrder.candidates(url);
        }
        @Override public ModelDownloadManifest.Entry metadata(String url) {
            return ModelDownloadManifest.forUrl(url);
        }
    };

    private final Sources sources;
    private DownloadRequest request;
    private ModelDownloadManifest.Entry metadata;
    private List<String> candidates;
    private int nextCandidate;
    private HttpURLConnection connection;
    private InputStream stream;
    private SegmentedModelStream segmentedStream;
    private long startOffset;
    private long position;
    private long total;
    private String firstETag;
    private MessageDigest digest;
    private IOException failure;
    private boolean ready;
    private boolean verified;
    private volatile boolean closed;

    public FallbackHttpClient() { this(MODEL_SOURCES); }

    // Package-visible injection lets tests use local HTTP servers without changing production URLs.
    FallbackHttpClient(Sources sources) { this.sources = sources; }

    @Override public HttpClient clone() { return new FallbackHttpClient(sources); }

    @Override public void connect(DownloadRequest request) {
        close();
        this.request = request;
        closed = false;
        ready = false;
        verified = false;
        failure = null;
        firstETag = null;
        nextCandidate = 0;
        digest = null;
        segmentedStream = null;
        metadata = sources.metadata(request.getUrl());
        candidates = sources.urls(request.getUrl());
        DownloadTrace.state("准备模型：" + request.getFileName()
                + "；尝试 " + candidates.size() + " 个来源");
        startOffset = request.getDownloadedBytes();
        position = startOffset;
        total = metadata != null ? metadata.sizeBytes : request.getTotalBytes();
        try {
            checkActive();
            if (startOffset < 0 || (total > 0 && startOffset > total)) {
                throw corrupt("Invalid saved download offset");
            }
            prepareDigest();
            if (total > 0 && startOffset == total) {
                verifyDigest();
                stream = new ByteArrayInputStream(new byte[0]);
            } else if (metadata != null && candidates.size() > 1
                    && startOffset < total) {
                // Range-verified 4 MiB chunks; PRDownloader still receives a sequential stream.
                // If all chunk sources fail, the existing sequential fallback can retry.
                segmentedStream = new SegmentedModelStream(request.getUrl(), candidates,
                        new File(request.getDirPath()), startOffset, total);
                stream = segmentedStream;
                DownloadTrace.state("启用并发分块下载：" + request.getFileName());
            } else {
                openNextSource();
            }
            ready = true;
        } catch (IOException e) {
            failure = e;
            disconnectTransport();
            // PRDownloader 1.0.2 deletes an existing .temp on an exception from connect(),
            // even before it knows whether it is resumable. A failed HTTP response preserves it.
        }
    }

    private void prepareDigest() throws IOException {
        File partial = partialFile();
        if (startOffset > 0 && (!partial.isFile() || partial.length() < startOffset)) {
            throw corrupt("Saved download is shorter than its checkpoint");
        }
        // RandomAccessFile in PRDownloader does not truncate. Discard only bytes beyond the
        // persisted checkpoint, including an old oversized tail when restarting at offset zero.
        if (partial.isFile() && partial.length() != startOffset) {
            try (RandomAccessFile file = new RandomAccessFile(partial, "rw")) {
                file.setLength(startOffset);
            }
        }
        if (metadata == null || metadata.sha256 == null) return;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        // The database checkpoint may precede the physical EOF after process death. Hash only
        // the persisted prefix; PRDownloader overwrites everything after that offset on resume.
        if (startOffset > 0) {
            try (InputStream prefix = new FileInputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = startOffset;
                while (remaining > 0) {
                    checkActive();
                    int count = prefix.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw corrupt("Saved download prefix is incomplete");
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
    }

    private void openNextSource() throws IOException {
        IOException lastFailure = null;
        while (nextCandidate < candidates.size()) {
            checkActive();
            disconnectTransport();
            String candidate = candidates.get(nextCandidate++);
            String sourceLabel = DownloadSourceOrder.label(candidate);
            DownloadTrace.trying(sourceLabel, request.getFileName(),
                    nextCandidate, candidates.size(), position, total);
            try {
                URL url = new URL(candidate);
                String requiredScheme = url.getProtocol();
                if (!"https".equals(requiredScheme) && sources == MODEL_SOURCES) {
                    throw new IOException("Model downloads require HTTPS");
                }
                for (int redirects = 0; ; redirects++) {
                    checkActive();
                    if (!requiredScheme.equals(url.getProtocol()) || url.getUserInfo() != null) {
                        throw new IOException("Unsafe model download redirect");
                    }
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(request.getConnectTimeout());
                    connection.setReadTimeout(request.getReadTimeout());
                    connection.setRequestProperty("Range", "bytes=" + position + "-");
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setRequestProperty("User-Agent", request.getUserAgent());
                    // Never forward Authorization, cookies or other application headers to a proxy.
                    int code = connection.getResponseCode();
                    checkActive();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || redirects >= MAX_REDIRECTS) {
                            throw new IOException("Invalid or excessive model redirects");
                        }
                        URL redirected = new URL(url, location);
                        disconnectTransport();
                        url = redirected;
                        continue;
                    }
                    validateResponse(code);
                    DownloadTrace.connected(sourceLabel, code);
                    stream = connection.getInputStream();
                    if (firstETag == null) firstETag = connection.getHeaderField("ETag");
                    return;
                }
            } catch (IOException e) {
                lastFailure = e;
                DownloadTrace.failed(sourceLabel, e.getClass().getSimpleName() + ": " + e.getMessage());
                checkActive();
            }
        }
        disconnectTransport();
        DownloadTrace.state("所有模型下载来源均失败；请切换来源或使用本地文件导入");
        throw new IOException("All model download sources failed", lastFailure);
    }

    private void validateResponse(int code) throws IOException {
        if (code != 200 && code != 206) throw new IOException("Model source HTTP " + code);
        String type = connection.getContentType();
        if (type != null) {
            String lower = type.toLowerCase(Locale.ROOT);
            if (lower.contains("text/") || lower.contains("html") || lower.contains("json")) {
                throw new IOException("Model source returned a web page, not a model");
            }
        }
        String encoding = connection.getContentEncoding();
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            throw new IOException("Encoded model response cannot be resumed safely");
        }
        long length = connection.getContentLengthLong();
        long responseTotal;
        if (code == 206) {
            String value = connection.getHeaderField("Content-Range");
            Matcher match = CONTENT_RANGE.matcher(value == null ? "" : value);
            if (!match.matches()) throw new IOException("Missing or invalid Content-Range");
            try {
                long first = Long.parseLong(match.group(1));
                long last = Long.parseLong(match.group(2));
                responseTotal = Long.parseLong(match.group(3));
                if (first != position || responseTotal <= position || last != responseTotal - 1
                        || (length >= 0 && length != responseTotal - position)) {
                    throw new IOException("Model source returned the wrong byte range");
                }
            } catch (NumberFormatException e) {
                throw new IOException("Invalid model byte range", e);
            }
        } else {
            if (position != 0) throw new IOException("Model source ignored resume Range");
            // Some CDN mirrors stream a complete HTTP 200 with chunked encoding.
            // The exact upstream manifest gives an authoritative expected size, and
            // the transfer still has to reach that size (plus SHA-256 when published).
            responseTotal = length > 0 ? length : (metadata == null ? -1 : metadata.sizeBytes);
        }
        if (responseTotal <= 0 || (total > 0 && responseTotal != total)) {
            throw new IOException("Model source returned the wrong file size");
        }
        String eTag = connection.getHeaderField("ETag");
        if (position > startOffset && (metadata == null || metadata.sha256 == null)
                && (firstETag == null || firstETag.startsWith("W/") || !firstETag.equals(eTag))) {
            throw new IOException("Cannot safely combine changed model sources");
        }
        total = responseTotal;
    }

    @Override public int getResponseCode() {
        // This is the logical, validated resumable stream, NOT a claim that the remote returned
        // HTTP 206. PRDownloader only persists checkpoints for 206 and otherwise discards them
        // on failure. A full HTTP 200 is accepted only at offset zero; all later transport
        // requests require a correct 206 range, possibly from another source.
        return ready ? HttpURLConnection.HTTP_PARTIAL : HttpURLConnection.HTTP_UNAVAILABLE;
    }

    @Override public InputStream getInputStream() throws IOException {
        if (!ready) throw failure != null ? failure : new IOException("No model response");
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                int count = read(one, 0, 1);
                return count < 0 ? -1 : one[0] & 0xff;
            }

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) return 0;
                for (;;) {
                    if (request.getStatus() == Status.PAUSED) return 0;
                    checkActive();
                    try {
                        int count = stream.read(buffer, offset,
                                (int) Math.min(length, total - position + 1));
                        if (request.getStatus() == Status.PAUSED) return 0;
                        checkActive();
                        if (count < 0) {
                            if (position != total) throw new IOException("Model response ended early");
                            verifyDigest();
                            return -1;
                        }
                        if (count > total - position) throw corrupt("Model response exceeded its size");
                        if (digest != null) digest.update(buffer, offset, count);
                        position += count;
                        DownloadTrace.transferred(position, total);
                        if (position == total) {
                            verifyDigest();
                            DownloadTrace.state("模型传输完成：" + request.getFileName());
                        } else if (position / (8L * 1024 * 1024)
                                > (position - count) / (8L * 1024 * 1024)) {
                            DownloadTrace.record("已收取 " + (position / (1024 * 1024))
                                    + " MiB / " + (total / (1024 * 1024)) + " MiB");
                        }
                        return count;
                    } catch (IntegrityException e) {
                        throw e;
                    } catch (IOException e) {
                        if (request.getStatus() == Status.PAUSED) return 0;
                        DownloadTrace.failed(DownloadSourceOrder.label(
                                candidates.get(Math.max(0, nextCandidate - 1))),
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                        checkActive();
                        try {
                            openNextSource();
                        } catch (InterruptedIOException stopped) {
                            if (request.getStatus() == Status.PAUSED) return 0;
                            throw stopped;
                        }
                    }
                }
            }

            @Override public void close() { FallbackHttpClient.this.close(); }
        };
    }

    private void verifyDigest() throws IOException {
        if (verified) return;
        if (digest != null) {
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            if (!metadata.sha256.equalsIgnoreCase(value.toString())) {
                throw corrupt("Model SHA-256 did not match the official release");
            }
        }
        verified = true;
        if (segmentedStream != null) segmentedStream.markVerified();
    }

    private IntegrityException corrupt(String message) {
        // A corrupt full-length checkpoint must not trap every later retry at EOF. Delete only
        // this request's temporary file and DB record, never a completed model or imported source.
        File partial = partialFile();
        if (partial.exists() && !partial.delete()) {
            message += "; could not remove the corrupt temporary file";
        }
        ComponentHolder.getInstance().getDbHelper().remove(request.getDownloadId());
        request.setDownloadedBytes(0);
        request.setTotalBytes(0);
        return new IntegrityException(message);
    }

    private File partialFile() { return new File(request.getDirPath(), request.getFileName() + ".temp"); }

    private void checkActive() throws InterruptedIOException {
        if (closed || Thread.currentThread().isInterrupted() || request.getStatus() == Status.CANCELLED
                || request.getStatus() == Status.PAUSED) {
            throw new InterruptedIOException("Model download paused or cancelled");
        }
    }

    @Override public long getContentLength() { return ready ? total - startOffset : -1; }

    @Override public String getResponseHeader(String name) {
        if (!ready) return null;
        if ("ETag".equalsIgnoreCase(name)) return firstETag;
        if ("Content-Length".equalsIgnoreCase(name)) return Long.toString(total - startOffset);
        if ("Content-Range".equalsIgnoreCase(name) && startOffset < total) {
            return "bytes " + startOffset + "-" + (total - 1) + "/" + total;
        }
        return null; // Redirects were already followed without mutating the canonical request URL.
    }

    @Override public Map<String, List<String>> getHeaderFields() {
        return connection == null ? Collections.emptyMap() : connection.getHeaderFields();
    }

    @Override public InputStream getErrorStream() {
        return new ByteArrayInputStream((failure == null ? "No usable model source" : failure.toString())
                .getBytes(StandardCharsets.UTF_8));
    }

    private void disconnectTransport() {
        if (stream != null) {
            try { stream.close(); } catch (IOException ignored) { }
            stream = null;
            segmentedStream = null;
        }
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    @Override public void close() {
        closed = true;
        disconnectTransport();
    }

    private static final class IntegrityException extends IOException {
        IntegrityException(String message) { super(message); }
    }
}

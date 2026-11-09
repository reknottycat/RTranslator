package nie.translator.rtranslator.downloader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An on-disk, bounded-memory 4 MiB segmented prefetch stream. PRDownloader continues to own
 * the canonical URL, its destination and its sequential checkpoint. Out-of-order chunks are
 * cached with a SHA-256 journal, so a process restart keeps successful prefetched chunks too.
 * Each range is strictly checked; a proxy returning HTTP 200 cannot poison resumed content.
 */
public final class SegmentedModelStream extends InputStream {
    public static final int BLOCK_BYTES = 4 * 1024 * 1024;
    static final int MAX_ATTEMPTS = 8;
    private static final int REDIRECT_LIMIT = 5;
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 15_000;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private final List<String> sources;
    private final String canonical;
    private final long total;
    private final int blockCount;
    private final int parallelism;
    private final File cacheDir;
    private final File journal;
    private final ExecutorService executor;
    private final Map<Integer, Future<File>> tasks = new HashMap<>();
    private final Map<Integer, String> checksums = new HashMap<>();
    private final Object journalLock = new Object();
    private final Map<String, Integer> mirrorSuccess = new HashMap<>();
    private volatile boolean closed;
    private boolean fullyVerified;
    private long offset;
    private int activeBlock = -1;
    private InputStream activeInput;
    private int doneBlocks;

    public SegmentedModelStream(String canonicalUrl, List<String> endpoints,
                                File destinationDir, long startOffset, long expectedLength)
            throws IOException {
        if (canonicalUrl == null || endpoints == null || endpoints.isEmpty()
                || destinationDir == null || expectedLength <= 0 || startOffset < 0
                || startOffset >= expectedLength) {
            throw new IllegalArgumentException("Invalid segmented model download parameters");
        }
        this.canonical = canonicalUrl;
        this.sources = new ArrayList<>(endpoints);
        this.total = expectedLength;
        this.offset = startOffset;
        long blocks = (expectedLength + BLOCK_BYTES - 1) / BLOCK_BYTES;
        if (blocks > Integer.MAX_VALUE) throw new IOException("Model contains too many blocks");
        this.blockCount = (int) blocks;
        this.parallelism = Math.min(blockCount, 14);
        String token = sha256((canonicalUrl + "|" + expectedLength).getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).substring(0, 32);
        this.cacheDir = new File(new File(destinationDir, ".model-chunks"), token);
        this.journal = new File(cacheDir, "chunks.done");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            throw new IOException("Cannot create segmented model cache directory");
        }
        loadJournal();
        this.executor = Executors.newFixedThreadPool(parallelism, new ThreadFactory() {
            private int number = 0;
            @Override public synchronized Thread newThread(Runnable runnable) {
                Thread worker = new Thread(runnable, "model-chunk-" + (++number));
                worker.setDaemon(true);
                return worker;
            }
        });
        DownloadTrace.record("启用 4 MiB 分块下载；并发 " + parallelism + "；"
                + blockCount + " 个分块；有效缓存 " + doneBlocks + " 块");
        scheduleWindow();
    }

    private void loadJournal() throws IOException {
        if (!journal.isFile()) return;
        List<String> lines = Files.readAllLines(journal.toPath(),
                java.nio.charset.StandardCharsets.UTF_8);
        synchronized (journalLock) {
            for (String line : lines) {
                String[] parts = line.split(" ");
                if (parts.length != 3) continue;
                try {
                    int idx = Integer.parseInt(parts[0]);
                    long length = Long.parseLong(parts[1]);
                    String digest = parts[2];
                    if (idx >= 0 && idx < blockCount
                            && length == blockLength(idx) && digest.matches("[0-9a-f]{64}")) {
                        checksums.put(idx, digest);
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        // Do not trust only filename or file size after a crash: verify every cached block.
        for (Map.Entry<Integer, String> item : new ArrayList<>(checksums.entrySet())) {
            File file = chunkFile(item.getKey());
            if (file.isFile() && file.length() == blockLength(item.getKey())
                    && item.getValue().equals(sha256(file))) {
                doneBlocks++;
            } else {
                checksums.remove(item.getKey());
                Files.deleteIfExists(file.toPath());
            }
        }
    }

    private long blockLength(int index) {
        return Math.min(BLOCK_BYTES, total - (long) index * BLOCK_BYTES);
    }

    private File chunkFile(int index) {
        return new File(cacheDir, String.format(Locale.ROOT, "%08d.chunk", index));
    }

    private void scheduleWindow() {
        int first = (int) (offset / BLOCK_BYTES);
        int lastExclusive = Math.min(blockCount, first + parallelism);
        synchronized (tasks) {
            if (closed) return;
            for (int idx = first; idx < lastExclusive; idx++) {
                if (!tasks.containsKey(idx)) {
                    final int next = idx;
                    tasks.put(idx, executor.submit(() -> acquireBlock(next)));
                }
            }
        }
    }

    private File acquireBlock(int index) throws IOException {
        checkActive();
        synchronized (journalLock) {
            if (checksums.containsKey(index) && chunkFile(index).isFile()) {
                return chunkFile(index);
            }
        }
        IOException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            checkActive();
            String endpoint = sources.get((index + attempt) % sources.size());
            String label = DownloadSourceOrder.label(endpoint);
            try {
                downloadBlock(index, endpoint);
                synchronized (journalLock) {
                    doneBlocks++;
                    mirrorSuccess.put(label, mirrorSuccess.containsKey(label)
                            ? mirrorSuccess.get(label) + 1 : 1);
                    DownloadTrace.segmentProgress(doneBlocks, blockCount, label);
                }
                return chunkFile(index);
            } catch (IOException error) {
                last = error;
                DownloadTrace.failed(label, "分块 " + (index + 1) + "/" + blockCount
                        + "，重试 " + (attempt + 1) + "/" + MAX_ATTEMPTS
                        + "：" + error.getClass().getSimpleName() + ": " + error.getMessage());
                if (attempt + 1 < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(Math.min(3000L, 250L * (attempt + 1))
                                + (long) (index % 7) * 40L);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Segment retry interrupted");
                    }
                }
            }
        }
        throw new IOException("分块 " + (index + 1) + "/" + blockCount
                + " 已尝试 " + MAX_ATTEMPTS + " 次，所有候选来源仍失败", last);
    }

    private void downloadBlock(int index, String endpoint) throws IOException {
        long from = (long) index * BLOCK_BYTES;
        long to = from + blockLength(index) - 1;
        URL current = new URL(endpoint);
        if (!"https".equals(current.getProtocol()) || current.getUserInfo() != null) {
            throw new IOException("Model segment source must use HTTPS");
        }
        HttpURLConnection connection = null;
        File temp = new File(cacheDir, String.format(Locale.ROOT, "%08d.part", index));
        try {
            for (int redirects = 0; ; redirects++) {
                checkActive();
                if (redirects > REDIRECT_LIMIT) throw new IOException("Too many model redirects");
                connection = (HttpURLConnection) current.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Range", "bytes=" + from + "-" + to);
                connection.setRequestProperty("User-Agent", "RTranslator-CN/3");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect destination");
                    URL redirected = new URL(current, location);
                    if (!"https".equals(redirected.getProtocol())
                            || redirected.getUserInfo() != null) {
                        throw new IOException("Unsafe HTTPS model redirect");
                    }
                    connection.disconnect();
                    connection = null;
                    current = redirected;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("HTTP " + code + " (expected 206 Range)");
                }
                String type = connection.getContentType();
                if (type != null) {
                    String lower = type.toLowerCase(Locale.ROOT);
                    if (lower.contains("text/") || lower.contains("html") || lower.contains("json")) {
                        throw new IOException("Mirror returned HTML/text instead of model bytes");
                    }
                }
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("Mirror compressed a byte-range response");
                }
                Matcher match = CONTENT_RANGE.matcher(
                        connection.getHeaderField("Content-Range") == null
                                ? "" : connection.getHeaderField("Content-Range"));
                if (!match.matches()) throw new IOException("Missing or invalid Content-Range");
                long gotFirst, gotLast, gotTotal;
                try {
                    gotFirst = Long.parseLong(match.group(1));
                    gotLast = Long.parseLong(match.group(2));
                    gotTotal = Long.parseLong(match.group(3));
                } catch (NumberFormatException ex) {
                    throw new IOException("Invalid numerical Content-Range", ex);
                }
                if (gotFirst != from || gotLast != to || gotTotal != total) {
                    throw new IOException("Incorrect Range boundaries or total size");
                }
                long announced = connection.getContentLengthLong();
                if (announced >= 0 && announced != blockLength(index)) {
                    throw new IOException("Incorrect block Content-Length");
                }
                MessageDigest digest = newDigest();
                long bytes = 0;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(temp, false)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkActive();
                        if (read == 0) continue;
                        if (read > blockLength(index) - bytes) {
                            throw new IOException("Range response exceeded expected block length");
                        }
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        bytes += read;
                    }
                    if (bytes != blockLength(index)) {
                        throw new IOException("Truncated range: " + bytes
                                + "/" + blockLength(index) + " bytes");
                    }
                    output.getFD().sync();
                }
                checkActive();
                Files.move(temp.toPath(), chunkFile(index).toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                String hash = hex(digest.digest());
                synchronized (journalLock) {
                    // Only publish journal entries after the atomic chunk file is complete.
                    try (FileOutputStream append = new FileOutputStream(journal, true)) {
                        append.write((index + " " + blockLength(index) + " " + hash + "\n")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        append.getFD().sync();
                    }
                    checksums.put(index, hash);
                }
                return;
            }
        } finally {
            if (connection != null) connection.disconnect();
            Files.deleteIfExists(temp.toPath());
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static String sha256(byte[] input) { return hex(newDigest().digest(input)); }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private void checkActive() throws InterruptedIOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model chunk transfer paused or cancelled");
        }
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] bytes, int off, int len) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (off < 0 || len < 0 || off > bytes.length - len) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) return 0;
        checkActive();
        if (offset >= total) return -1;
        int index = (int) (offset / BLOCK_BYTES);
        if (activeInput == null || activeBlock != index) {
            if (activeInput != null) activeInput.close();
            activeInput = null;
            Future<File> future;
            synchronized (tasks) { future = tasks.get(index); }
            if (future == null) {
                scheduleWindow();
                synchronized (tasks) { future = tasks.get(index); }
            }
            if (future == null) throw new IOException("Missing model chunk future");
            File file;
            try {
                file = future.get();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted waiting for model chunk");
            } catch (CancellationException cancelled) {
                throw new InterruptedIOException("Model chunk download cancelled");
            } catch (ExecutionException error) {
                throw new IOException("Model chunk failed after mirror rotation", error.getCause());
            }
            activeInput = new FileInputStream(file);
            activeBlock = index;
            long skip = offset - (long) index * BLOCK_BYTES;
            while (skip > 0) {
                long step = activeInput.skip(skip);
                if (step <= 0) throw new IOException("Cached chunk ended before resume position");
                skip -= step;
            }
            synchronized (tasks) { tasks.remove(index); }
            scheduleWindow();
        }
        int allowed = (int) Math.min(len, Math.min(total - offset,
                BLOCK_BYTES - (offset % BLOCK_BYTES)));
        int count = activeInput.read(bytes, off, allowed);
        if (count <= 0) throw new IOException("Cached block truncated while reading");
        offset += count;
        if (offset >= total || offset / BLOCK_BYTES != index) {
            activeInput.close();
            activeInput = null;
            activeBlock = -1;
            scheduleWindow();
        }
        return count;
    }

    /** The enclosing HTTP adapter marks completion only after validating the full model. */
    public void markVerified() { fullyVerified = true; }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
        synchronized (tasks) {
            for (Future<File> future : tasks.values()) future.cancel(true);
            tasks.clear();
        }
        if (activeInput != null) {
            activeInput.close();
            activeInput = null;
        }
        if (fullyVerified) {
            // Cache is not needed after the checked sequential model has completed.
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) Files.deleteIfExists(file.toPath());
            }
            Files.deleteIfExists(cacheDir.toPath());
        }
    }
}

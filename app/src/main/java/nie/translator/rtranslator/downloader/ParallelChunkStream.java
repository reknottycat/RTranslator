package nie.translator.rtranslator.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded 4 MiB Range-stream feeding the existing PRDownloader writer.
 * PRDownloader owns the official destination, resume DB and progress; this class
 * fetches verified ranges ahead of its sequential reader. Finished out-of-order
 * blocks live in app-private cache with their own checksum, and can survive a
 * process restart. Already-consumed blocks are removed to avoid double storage.
 */
final class ParallelChunkStream extends InputStream {
    static final int CHUNK_SIZE = 4 * 1024 * 1024;
    static final int CONCURRENCY = 14;
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final long total;
    private final List<String> sources;
    private final File cache;
    private final ExecutorService workers;
    private final Map<Integer, Future<File>> futures = new HashMap<>();
    private final int count;
    private final int parallelism;
    private int readIndex;
    private int scheduleIndex;
    private long position;
    private FileInputStream active;
    private volatile boolean closed;
    private volatile boolean completed;

    ParallelChunkStream(File outputFolder, String canonicalUrl, List<String> sources,
                        long start, long total) throws IOException {
        this(outputFolder, canonicalUrl, sources, start, total, CONCURRENCY);
    }

    ParallelChunkStream(File outputFolder, String canonicalUrl, List<String> sources,
                        long start, long total, int parallelism) throws IOException {
        if (total <= 0 || start < 0 || start >= total || total > (long) Integer.MAX_VALUE * CHUNK_SIZE
                || sources == null || sources.isEmpty() || parallelism < 1 || parallelism > 28) {
            throw new IOException("Invalid parallel model download parameters");
        }
        this.total = total;
        this.sources = new ArrayList<>(sources);
        this.position = start;
        this.count = (int) ((total - 1) / CHUNK_SIZE + 1);
        this.readIndex = (int) (start / CHUNK_SIZE);
        this.scheduleIndex = this.readIndex;
        this.parallelism = parallelism;
        this.cache = new File(outputFolder, ".model-blocks-" + digestName(canonicalUrl + "/" + total));
        if (!cache.isDirectory() && !cache.mkdirs() && !cache.isDirectory()) {
            throw new IOException("Cannot prepare temporary chunk directory");
        }
        ThreadFactory threadFactory = job -> {
            Thread thread = new Thread(job, "model-range-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        workers = Executors.newFixedThreadPool(parallelism, threadFactory);
        prefetch();
        DownloadTrace.record("启用 4 MiB 分块下载；并发：" + parallelism
                + "；分块总数：" + count + "；断点：" + start + " B");
    }

    private static String digestName(String text) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] data = hash.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 8; i++) result.append(String.format(Locale.ROOT, "%02x", data[i] & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private synchronized void prefetch() {
        while (!closed && scheduleIndex < count
                && scheduleIndex < readIndex + parallelism) {
            final int chunk = scheduleIndex++;
            futures.put(chunk, workers.submit(() -> obtainChunk(chunk)));
        }
    }

    @Override public int read() throws IOException {
        byte[] buffer = new byte[1];
        return read(buffer, 0, 1) < 0 ? -1 : buffer[0] & 0xff;
    }

    @Override public int read(byte[] destination, int offset, int length) throws IOException {
        if (destination == null) throw new NullPointerException("buffer");
        if (offset < 0 || length < 0 || offset > destination.length - length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        while (true) {
            checkRunning();
            if (position >= total) {
                completed = true;
                return -1;
            }
            if (active == null) {
                File file = awaitChunk(readIndex);
                active = new FileInputStream(file);
                long skip = position - (long) readIndex * CHUNK_SIZE;
                while (skip > 0) {
                    long moved = active.skip(skip);
                    if (moved <= 0) throw new IOException("Truncated cached block " + readIndex);
                    skip -= moved;
                }
            }
            int withinBlock = (int) (position - (long) readIndex * CHUNK_SIZE);
            int expected = (int) Math.min(length, Math.min(total - position, CHUNK_SIZE - withinBlock));
            int got = active.read(destination, offset, expected);
            if (got <= 0) throw new IOException("Missing bytes in complete downloaded block " + readIndex);
            position += got;
            if (withinBlock + got == Math.min((long) CHUNK_SIZE, total - (long) readIndex * CHUNK_SIZE)) {
                active.close();
                active = null;
                deleteConsumedChunk(readIndex);
                readIndex++;
                prefetch();
            }
            if (position >= total) completed = true;
            return got;
        }
    }

    private File awaitChunk(int index) throws IOException {
        Future<File> task;
        synchronized (this) { task = futures.get(index); }
        if (task == null) throw new IOException("No download task for block " + index);
        try {
            File result = task.get();
            synchronized (this) { futures.remove(index); }
            checkRunning();
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Model block download interrupted");
        } catch (ExecutionException error) {
            throw new IOException("Unable to fetch block " + (index + 1) + "/" + count,
                    error.getCause());
        }
    }

    private File obtainChunk(int index) throws IOException {
        long begin = (long) index * CHUNK_SIZE;
        long end = Math.min(total - 1, begin + CHUNK_SIZE - 1);
        long required = end - begin + 1;
        File block = new File(cache, index + ".block");
        File checksum = new File(cache, index + ".sha256");
        if (validCached(block, checksum, required)) {
            DownloadTrace.record("复用已校验的断点分块 " + (index + 1) + "/" + count);
            return block;
        }
        IOException lastFailure = null;
        // Mirror-first distributes first attempts among mirrors, never starting with
        // the official URL unless the user explicitly selected official-first.
        int initialWindow = DownloadSourceOrder.get() == DownloadSourceOrder.MIRROR_FIRST
                ? Math.min(3, sources.size()) : 1;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            checkRunning();
            int candidate = (index % initialWindow + attempt) % sources.size();
            String url = sources.get(candidate);
            String label = DownloadSourceOrder.label(url);
            File temporary = new File(cache, index + "-" + Thread.currentThread().getId() + ".tmp");
            try {
                DownloadTrace.blockTrying(label, index + 1, count, attempt + 1, MAX_ATTEMPTS);
                fetchBlock(url, label, begin, end, required, temporary);
                checkRunning();
                String hash = fileHash(temporary);
                File sumTmp = new File(cache, index + ".sha256.tmp");
                Files.write(sumTmp.toPath(), hash.getBytes(StandardCharsets.US_ASCII));
                Files.move(temporary.toPath(), block.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                Files.move(sumTmp.toPath(), checksum.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                DownloadTrace.record("完成分块 " + (index + 1) + "/" + count + "，来源：" + label);
                return block;
            } catch (IOException error) {
                lastFailure = error;
                DownloadTrace.failed(label, "分块 " + (index + 1) + ": "
                        + error.getClass().getSimpleName() + " " + error.getMessage());
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) {}
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Parallel model download stopped");
                }
            }
        }
        throw new IOException("Model block exhausted all " + MAX_ATTEMPTS
                + " attempts: " + (index + 1) + "/" + count, lastFailure);
    }

    private void fetchBlock(String source, String label, long begin, long end, long required,
                            File temporary) throws IOException {
        URL url = new URL(source);
        HttpURLConnection connection = null;
        try {
            for (int redirect = 0; ; redirect++) {
                checkRunning();
                if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
                    throw new IOException("Unsafe model download redirect");
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(FallbackHttpClient.CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(FallbackHttpClient.READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Range", "bytes=" + begin + "-" + end);
                connection.setRequestProperty("User-Agent", "RTranslator-CN/3.0");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect >= MAX_REDIRECTS) {
                        throw new IOException("Invalid model redirect");
                    }
                    URL redirected = new URL(url, location);
                    connection.disconnect();
                    connection = null;
                    url = redirected;
                    continue;
                }
                if (code != 206) throw new IOException("Expected HTTP 206 for exact chunk, received " + code);
                String type = connection.getContentType();
                if (type != null && (type.toLowerCase(Locale.ROOT).contains("text/")
                        || type.toLowerCase(Locale.ROOT).contains("html")
                        || type.toLowerCase(Locale.ROOT).contains("json"))) {
                    throw new IOException("Proxy returned an HTML/text response");
                }
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("Unexpected compressed range");
                }
                Matcher match = RANGE.matcher(connection.getHeaderField("Content-Range") == null
                        ? "" : connection.getHeaderField("Content-Range"));
                boolean matchesRange = false;
                if (match.matches()) {
                    try {
                        matchesRange = Long.parseLong(match.group(1)) == begin
                                && Long.parseLong(match.group(2)) == end
                                && Long.parseLong(match.group(3)) == total;
                    } catch (NumberFormatException invalid) {
                        throw new IOException("Invalid Content-Range number", invalid);
                    }
                }
                if (!matchesRange) {
                    throw new IOException("Model proxy returned incorrect Content-Range");
                }
                long size = connection.getContentLengthLong();
                if (size > 0 && size != required) {
                    throw new IOException("Proxy returned incorrect block length");
                }
                DownloadTrace.connected(label, code);
                long received = 0;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        checkRunning();
                        if (n > required - received) throw new IOException("Block exceeds promised range");
                        out.write(buffer, 0, n);
                        received += n;
                    }
                    if (received != required) throw new IOException("Short range " + received + "/" + required);
                    out.getFD().sync();
                }
                return;
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String fileHash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) > 0) digest.update(buffer, 0, n);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static boolean validCached(File file, File checksum, long required) {
        if (!file.isFile() || !checksum.isFile() || file.length() != required) return false;
        try {
            String known = new String(Files.readAllBytes(checksum.toPath()), StandardCharsets.US_ASCII).trim();
            return known.length() == 64 && known.equals(fileHash(file));
        } catch (IOException error) {
            return false;
        }
    }

    private void deleteConsumedChunk(int chunk) {
        new File(cache, chunk + ".block").delete();
        new File(cache, chunk + ".sha256").delete();
    }

    private void checkRunning() throws InterruptedIOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model range download cancelled");
        }
    }

    @Override public void close() throws IOException {
        closed = true;
        workers.shutdownNow();
        if (active != null) { active.close(); active = null; }
        if (completed && cache.isDirectory()) {
            File[] residue = cache.listFiles();
            if (residue != null) for (File file : residue) file.delete();
            cache.delete();
        }
    }
}

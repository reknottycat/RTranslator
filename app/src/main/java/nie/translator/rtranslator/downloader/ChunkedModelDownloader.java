package nie.translator.rtranslator.downloader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chunked HTTP Range transport for large PUBLIC model assets. Each 4MiB chunk is written
 * by a separate worker and synced to disk BEFORE its completion marker is appended.
 * A killed process can pick up only missing chunks. Does not require curl on Android.
 *
 * The canonical filename, exact upstream size and SHA-256 (when published) remain authoritative.
 * For old ONNX assets without an upstream digest, the app's existing ONNX loading test still runs.
 */
public final class ChunkedModelDownloader {
    public static final int CHUNK_BYTES = 4 * 1024 * 1024;
    public static final long MIN_PARALLEL_BYTES = 8L * 1024 * 1024;
    private static final int MAX_TRIES_PER_CHUNK = 8;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final String CHECKPOINT_VERSION = "rtranslator-cn-chunks-v1";
    private final Object checkpointLock = new Object();
    private static final java.util.concurrent.Semaphore CONNECTIONS = new java.util.concurrent.Semaphore(28, true);
    interface SourceProvider { List<String> candidates(String canonical); }
    private final SourceProvider sourceProvider;
    private final boolean loopbackTest;
    public ChunkedModelDownloader() { this(DownloadSourceOrder::candidates, false); }
    // Test-only injection permits HTTP exclusively on loopback. Production requires HTTPS.
    ChunkedModelDownloader(SourceProvider provider, boolean loopbackTest) {
        this.sourceProvider = provider;
        this.loopbackTest = loopbackTest;
    }

    public interface Progress {
        void onProgress(long completeBytes, long totalBytes);
    }

    public void download(String canonicalUrl, File destination, ModelDownloadManifest.Entry entry,
                         int workers, Progress callback) throws IOException {
        if (entry == null || entry.sizeBytes <= 0) throw new IOException("No verified release manifest entry");
        if (workers < 1 || workers > 28) throw new IllegalArgumentException("Threads must be between 1 and 28");
        checkInterrupted();
        File dir = destination.getAbsoluteFile().getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()))
            throw new IOException("Cannot create model directory");
        File staging = new File(dir, destination.getName() + ".cnchunks.part");
        File checkpoint = new File(dir, destination.getName() + ".chunks.done");
        int count = (int) ((entry.sizeBytes + CHUNK_BYTES - 1) / CHUNK_BYTES);
        BitSet done = readCheckpoint(checkpoint, staging, canonicalUrl, entry, count);
        if (!staging.exists()) {
            if (checkpoint.exists() && !checkpoint.delete()) throw new IOException("Cannot reset checkpoint");
            done.clear();
        }
        try (RandomAccessFile sizeFile = new RandomAccessFile(staging, "rw")) {
            sizeFile.setLength(entry.sizeBytes);
        }
        final BitSet completed = done;
        final List<String> sources = sourceProvider.candidates(canonicalUrl);
        if (sources.isEmpty()) throw new IOException("No model download sources");
        AtomicInteger index = new AtomicInteger(0);
        AtomicLong completedBytes = new AtomicLong(countDoneBytes(done, entry.sizeBytes, count));
        int threads = Math.min(workers, count);
        DownloadTrace.state("4 MiB 分块下载 · " + threads + " 路并发 · "
                + done.cardinality() + "/" + count + " 块已保存");
        DownloadTrace.trying(DownloadSourceOrder.label(sources.get(0)), destination.getName(),
                1, sources.size(), completedBytes.get(), entry.sizeBytes);
        DownloadTrace.transferred(completedBytes.get(), entry.sizeBytes);
        callback.onProgress(completedBytes.get(), entry.sizeBytes);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> tasks = new ArrayList<>();
        IOException error = null;
        try {
            for (int t = 0; t < threads; t++) {
                tasks.add(pool.submit(() -> {
                    for (;;) {
                        if (Thread.currentThread().isInterrupted()) throw new RuntimeException(
                                new InterruptedIOException("Download paused"));
                        int chunkIndex = index.getAndIncrement();
                        if (chunkIndex >= count) return;
                        synchronized (checkpointLock) { if (completed.get(chunkIndex)) continue; }
                        long begin = ((long) chunkIndex) * CHUNK_BYTES;
                        long end = Math.min(entry.sizeBytes - 1, begin + CHUNK_BYTES - 1);
                        try {
                            getChunk(staging, sources, chunkIndex, begin, end, entry.sizeBytes);
                            synchronized (checkpointLock) {
                                // Durable marker is appended only AFTER the chunk file was synced.
                                appendCheckpoint(checkpoint, canonicalUrl, entry, chunkIndex);
                                completed.set(chunkIndex);
                            }
                            long received = completedBytes.addAndGet(end - begin + 1);
                            DownloadTrace.transferred(received, entry.sizeBytes);
                            callback.onProgress(received, entry.sizeBytes);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }));
            }
            for (Future<?> future : tasks) {
                try {
                    future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Chunked model download paused");
                } catch (ExecutionException ex) {
                    Throwable root = ex.getCause();
                    if (root instanceof RuntimeException && root.getCause() != null) root = root.getCause();
                    throw root instanceof IOException ? (IOException) root : new IOException("Chunk worker failed", root);
                }
            }
        } catch (IOException ex) {
            error = ex;
        } finally {
            pool.shutdownNow();
            // Do not return to the caller (which can restart downloads) until ALL writers stop.
            boolean interrupted = false;
            while (!pool.isTerminated()) {
                try { pool.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (error != null) throw error;
        checkInterrupted();
        if (completed.cardinality() != count || staging.length() != entry.sizeBytes)
            throw new IOException("Model download has missing chunks");
        DownloadTrace.state("所有分块完成，校验模型完整性：" + destination.getName());
        if (entry.sha256 != null && !entry.sha256.equalsIgnoreCase(sha256(staging))) {
            // Never publish an assembled model that fails the official release hash.
            if (!checkpoint.delete()) DownloadTrace.record("不能清理校验失败的断点文件");
            if (!staging.delete()) DownloadTrace.record("不能清理校验失败的临时文件");
            throw new IOException("Assembled model SHA-256 mismatch with official release");
        }
        checkInterrupted();
        Files.move(staging.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (checkpoint.exists() && !checkpoint.delete())
            DownloadTrace.record("模型已完成，但不能删除旧断点文件");
        DownloadTrace.state("模型下载完成且已发布：" + destination.getName());
        callback.onProgress(entry.sizeBytes, entry.sizeBytes);
    }

    private BitSet readCheckpoint(File checkpoint, File staging, String url,
                                  ModelDownloadManifest.Entry metadata, int count) throws IOException {
        BitSet completed = new BitSet(count);
        if (!checkpoint.exists() || !staging.exists() || staging.length() != metadata.sizeBytes) {
            // A journal cannot describe a missing/truncated staging file.
            Files.deleteIfExists(checkpoint.toPath());
            return completed;
        }
        byte[] journalBytes = Files.readAllBytes(checkpoint.toPath());
        String journalText = new String(journalBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!journalText.endsWith("\n")) {
            // A partial numeric record (e.g. "12" cut to "1") is not a completed chunk.
            // Remove it before future appends, otherwise records could concatenate.
            int end = journalText.lastIndexOf('\n') + 1;
            journalText = journalText.substring(0, end);
            try (RandomAccessFile journal = new RandomAccessFile(checkpoint, "rw")) {
                journal.setLength(end); // Journal format is strictly ASCII.
                journal.getFD().sync();
            }
        }
        String[] lines = journalText.split("\\n");
        String expectedHeader = header(url, metadata);
        if (lines.length == 0 || !expectedHeader.equals(lines[0])) {
            // A new official asset or changed transfer format must never reuse prior chunks.
            if (!checkpoint.delete() || !staging.delete()) {
                throw new IOException("Could not reset incompatible chunk checkpoint");
            }
            return completed;
        }
        for (int i = 1; i < lines.length; i++) {
            try {
                int idx = Integer.parseInt(lines[i].trim());
                if (idx >= 0 && idx < count) completed.set(idx);
            } catch (NumberFormatException ignored) {
                // Incomplete last line after power loss is ignored.
            }
        }
        return completed;
    }

    private static String header(String url, ModelDownloadManifest.Entry metadata) {
        return CHECKPOINT_VERSION + " " + metadata.sizeBytes + " " + CHUNK_BYTES
                + " " + Integer.toHexString(url.hashCode()) + " "
                + (metadata.sha256 == null ? "-" : metadata.sha256);
    }

    private void appendCheckpoint(File checkpoint, String url, ModelDownloadManifest.Entry entry,
                                  int index) throws IOException {
        boolean fresh = !checkpoint.exists() || checkpoint.length() == 0;
        try (FileOutputStream output = new FileOutputStream(checkpoint, true)) {
            if (fresh) output.write((header(url, entry) + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write((index + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static long countDoneBytes(BitSet done, long total, int count) {
        long bytes = 0;
        for (int i = done.nextSetBit(0); i >= 0 && i < count; i = done.nextSetBit(i + 1))
            bytes += Math.min(CHUNK_BYTES, total - ((long) i) * CHUNK_BYTES);
        return bytes;
    }

    private void getChunk(File staging, List<String> sources, int chunkIndex,
                                 long start, long end, long total) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MAX_TRIES_PER_CHUNK; attempt++) {
            checkInterrupted();
            // Every chunk respects ModelScope-first; rotate only after a real failure.
            String candidate = sources.get(attempt % sources.size());
            String label = DownloadSourceOrder.label(candidate);
            DownloadTrace.blockTrying(label, chunkIndex + 1,
                    (int) ((total + CHUNK_BYTES - 1) / CHUNK_BYTES),
                    attempt + 1, MAX_TRIES_PER_CHUNK);
            try {
                boolean acquired = false;
                try {
                    CONNECTIONS.acquire();
                    acquired = true;
                    fetchRange(candidate, staging, start, end, total);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Connection wait interrupted");
                } finally {
                    if (acquired) CONNECTIONS.release();
                }
                DownloadTrace.record("完成第 " + (chunkIndex + 1) + " 块 · " + label);
                return;
            } catch (IOException ex) {
                last = ex;
                DownloadTrace.failed(label + " 第 " + (chunkIndex + 1) + " 块",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
                if (attempt < MAX_TRIES_PER_CHUNK - 1 && !loopbackTest) {
                    try { Thread.sleep(Math.min(2000, 200L << Math.min(attempt, 3))); }
                    catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Chunk retry interrupted");
                    }
                }
            }
        }
        throw new IOException("Chunk " + (chunkIndex + 1) + " failed after "
                + MAX_TRIES_PER_CHUNK + " attempts", last);
    }

    private void fetchRange(String initial, File staging, long start, long end,
                                   long total) throws IOException {
        URL url = new URL(initial);
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            checkInterrupted();
            boolean localTest = loopbackTest && "http".equals(url.getProtocol())
                    && ("localhost".equals(url.getHost()) || "127.0.0.1".equals(url.getHost()));
            if ((!"https".equalsIgnoreCase(url.getProtocol()) && !localTest) || url.getUserInfo() != null)
                throw new IOException("Unsafe redirect target");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "RTranslator/3 CN Android");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (hops == MAX_REDIRECTS) throw new IOException("Too many HTTPS redirects");
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    url = new URL(url, location);
                    continue;
                }
                if (code != 206) throw new IOException("HTTP " + code + " does not support precise ranges");
                String type = connection.getContentType();
                if (type != null && (type.toLowerCase(Locale.ROOT).contains("html")
                        || type.toLowerCase(Locale.ROOT).contains("json")
                        || type.toLowerCase(Locale.ROOT).startsWith("text/")))
                    throw new IOException("Proxy returned HTML/text, not a model");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding))
                    throw new IOException("Compressed response cannot be used as a byte range");
                Matcher match = RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                if (!match.matches()) throw new IOException("Invalid Content-Range");
                try {
                    if (Long.parseLong(match.group(1)) != start
                            || Long.parseLong(match.group(2)) != end
                            || Long.parseLong(match.group(3)) != total)
                        throw new IOException("Proxy returned the wrong range");
                } catch (NumberFormatException ex) {
                    throw new IOException("Bad Content-Range number", ex);
                }
                if (connection.getContentLengthLong() >= 0
                        && connection.getContentLengthLong() != end - start + 1)
                    throw new IOException("Wrong Content-Length for chunk");
                DownloadTrace.connected(DownloadSourceOrder.label(initial), code);
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
                     RandomAccessFile writer = new RandomAccessFile(staging, "rw")) {
                    writer.seek(start);
                    byte[] data = new byte[64 * 1024];
                    long left = end - start + 1;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                    while (left > 0) {
                        checkInterrupted();
                        if (System.nanoTime() > deadline) throw new IOException("Chunk stalled for 90 seconds");
                        int n = input.read(data, 0, (int) Math.min(data.length, left));
                        if (n < 0) throw new IOException("Chunk response ended early");
                        writer.write(data, 0, n);
                        left -= n;
                    }
                    // A proxy that sends more bytes violates the requested range.
                    if (input.read() != -1) throw new IOException("Chunk response has extra bytes");
                    writer.getFD().sync();
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[256 * 1024];
                int n;
                while ((n = input.read(bytes)) != -1) {
                    checkInterrupted();
                    digest.update(bytes, 0, n);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest())
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Model download paused");
    }
}

package nie.translator.rtranslator.downloader;

import com.downloader.Response;
import com.downloader.Status;
import com.downloader.database.DbHelper;
import com.downloader.database.DownloadModel;
import com.downloader.httpclient.HttpClient;
import com.downloader.internal.ComponentHolder;
import com.downloader.request.DownloadRequest;
import com.downloader.request.DownloadRequestBuilder;
import com.downloader.request.QuietDownloadRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises the real PRDownloader 1.0.2 DownloadTask, including its temp file and DB handling. */
public class PrDownloaderTransportTest {
    private static final String CANONICAL = "https://github.com/example/models/releases/download/v1/model.zip";
    private static final String ETAG = "\"model-version-1\"";
    private static final int REQUEST_ID = 142;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private byte[] payload;
    private HttpServer server;
    private File directory;
    private MemoryDb database;
    private Object oldClient;
    private Object oldDatabase;
    private final AtomicInteger requests = new AtomicInteger();

    @Before public void setUp() throws Exception {
        payload = new byte[24 * 1024 + 137];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + 17);
        directory = temporary.newFolder("models");
        database = new MemoryDb();
        oldClient = field("httpClient").get(ComponentHolder.getInstance());
        oldDatabase = field("dbHelper").get(ComponentHolder.getInstance());
        field("dbHelper").set(ComponentHolder.getInstance(), database);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> serveModel(exchange, payload));
        server.createContext("/failed", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        installClient(client("/model"));
    }

    @After public void tearDown() throws Exception {
        if (server != null) server.stop(0);
        field("httpClient").set(ComponentHolder.getInstance(), oldClient);
        field("dbHelper").set(ComponentHolder.getInstance(), oldDatabase);
    }

    @Test public void freshTaskTruncatesStaleUntrackedTempTail() throws Exception {
        Files.write(partial().toPath(), Arrays.copyOf(payload, payload.length + 200));

        Response response = runTask(request());

        assertSuccessfulFile(response);
        assertEquals(1, requests.get());
        assertNull(database.find(REQUEST_ID));
    }

    @Test public void resumedTaskTruncatesTailBeyondSavedCheckpoint() throws Exception {
        seedCheckpoint(7000, Arrays.copyOf(payload, payload.length + 200));

        Response response = runTask(request());

        assertSuccessfulFile(response);
        assertEquals(1, requests.get());
        assertNull(database.find(REQUEST_ID));
    }

    @Test public void completedCheckpointHasExactLengthEvenWithoutAnotherHttpRequest() throws Exception {
        seedCheckpoint(payload.length, Arrays.copyOf(payload, payload.length + 200));

        Response response = runTask(request());

        assertSuccessfulFile(response);
        assertEquals(0, requests.get());
    }

    @Test public void connectionFailurePreservesCheckpointForLaterResume() throws Exception {
        int checkpoint = 7000;
        seedCheckpoint(checkpoint, Arrays.copyOf(payload, checkpoint));
        installClient(client("/failed"));

        Response failed = runTask(request());

        assertNotNull(failed.getError());
        assertFalse(failed.isSuccessful());
        assertArrayEquals(Arrays.copyOf(payload, checkpoint), Files.readAllBytes(partial().toPath()));
        assertEquals(checkpoint, database.find(REQUEST_ID).getDownloadedBytes());
        assertFalse(destination().exists());

        installClient(client("/model"));
        assertSuccessfulFile(runTask(request()));
    }

    @Test public void pauseDuringReadIsPausedAndCanResumeFromPersistedFile() throws Exception {
        installClient(new PauseDuringReadClient(client("/model")));

        Response paused = runTask(request());

        assertTrue("Pause must not become a connection error", paused.isPaused());
        assertNull(paused.getError());
        assertFalse(destination().exists());
        DownloadModel checkpoint = database.find(REQUEST_ID);
        assertNotNull(checkpoint);
        assertTrue(checkpoint.getDownloadedBytes() > 0);
        assertTrue(checkpoint.getDownloadedBytes() < payload.length);
        assertEquals(checkpoint.getDownloadedBytes(), partial().length());

        installClient(client("/model"));
        assertSuccessfulFile(runTask(request()));
    }

    @Test public void badSavedPrefixIsRejectedAndNextAttemptCanStartFresh() throws Exception {
        byte[] prefix = Arrays.copyOf(payload, 7000);
        prefix[24] ^= 0x55;
        seedCheckpoint(prefix.length, prefix);

        Response failed = runTask(request());

        assertNotNull(failed.getError());
        assertFalse(destination().exists());
        assertFalse(partial().exists());
        assertNull(database.find(REQUEST_ID));
        assertSuccessfulFile(runTask(request()));
    }

    private FallbackHttpClient client(String... paths) throws Exception {
        List<String> candidates = new ArrayList<>();
        for (String path : paths) candidates.add("http://127.0.0.1:" + server.getAddress().getPort() + path);
        ModelDownloadManifest.Entry entry = new ModelDownloadManifest.Entry(payload.length, sha256(payload));
        return new FallbackHttpClient(new FallbackHttpClient.Sources() {
            @Override public List<String> urls(String url) { return candidates; }
            @Override public ModelDownloadManifest.Entry metadata(String url) { return entry; }
        });
    }

    private void installClient(HttpClient client) throws Exception {
        field("httpClient").set(ComponentHolder.getInstance(), client);
    }

    private QuietDownloadRequest request() {
        QuietDownloadRequest request = new QuietDownloadRequest(new DownloadRequestBuilder(
                CANONICAL, directory.getAbsolutePath(), "model.zip")
                .setUserAgent("RTranslator-JVM-Test").setReadTimeout(1000).setConnectTimeout(1000));
        request.setDownloadId(REQUEST_ID);
        request.setStatus(Status.RUNNING);
        return request;
    }

    private Response runTask(DownloadRequest request) throws Exception {
        Class<?> taskType = Class.forName("com.downloader.internal.DownloadTask");
        Method create = taskType.getDeclaredMethod("create", DownloadRequest.class);
        create.setAccessible(true);
        Object task = create.invoke(null, request);
        Method run = taskType.getDeclaredMethod("run");
        run.setAccessible(true);
        return (Response) run.invoke(task);
    }

    private void seedCheckpoint(int bytes, byte[] diskBytes) throws IOException {
        Files.write(partial().toPath(), diskBytes);
        DownloadModel model = new DownloadModel();
        model.setId(REQUEST_ID);
        model.setUrl(CANONICAL);
        model.setDirPath(directory.getAbsolutePath());
        model.setFileName("model.zip");
        model.setETag(ETAG);
        model.setDownloadedBytes(bytes);
        model.setTotalBytes(payload.length);
        database.insert(model);
    }

    private void assertSuccessfulFile(Response response) throws IOException {
        assertNull(response.getError());
        assertTrue(response.isSuccessful());
        assertEquals(payload.length, destination().length());
        assertArrayEquals(payload, Files.readAllBytes(destination().toPath()));
        assertFalse(partial().exists());
    }

    private File partial() { return new File(directory, "model.zip.temp"); }
    private File destination() { return new File(directory, "model.zip"); }

    private void serveModel(HttpExchange exchange, byte[] bytes) throws IOException {
        requests.incrementAndGet();
        String range = exchange.getRequestHeaders().getFirst("Range");
        int start = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("ETag", ETAG);
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-"
                + (bytes.length - 1) + "/" + bytes.length);
        exchange.sendResponseHeaders(206, bytes.length - start);
        exchange.getResponseBody().write(bytes, start, bytes.length - start);
        exchange.close();
    }

    private static Field field(String name) throws Exception {
        Field field = ComponentHolder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static final class MemoryDb implements DbHelper {
        private DownloadModel record;
        @Override public DownloadModel find(int id) { return record != null && record.getId() == id ? record : null; }
        @Override public void insert(DownloadModel model) { record = model; }
        @Override public void update(DownloadModel model) { record = model; }
        @Override public void updateProgress(int id, long bytes, long modified) {
            DownloadModel model = find(id);
            if (model != null) {
                model.setDownloadedBytes(bytes);
                model.setLastModifiedAt(modified);
            }
        }
        @Override public void remove(int id) { if (find(id) != null) record = null; }
        @Override public List<DownloadModel> getUnwantedModels(int days) { return Collections.emptyList(); }
        @Override public void clear() { record = null; }
    }

    /** Sets PAUSED between two actual PRDownloader reads, rather than before the task starts. */
    private static final class PauseDuringReadClient implements HttpClient {
        private final HttpClient delegate;
        private DownloadRequest request;
        PauseDuringReadClient(HttpClient delegate) { this.delegate = delegate; }
        @Override public HttpClient clone() { return new PauseDuringReadClient(delegate.clone()); }
        @Override public void connect(DownloadRequest request) throws IOException {
            this.request = request;
            delegate.connect(request);
        }
        @Override public InputStream getInputStream() throws IOException {
            return new FilterInputStream(delegate.getInputStream()) {
                private int reads;
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (++reads == 2) request.setStatus(Status.PAUSED);
                    return in.read(bytes, offset, length);
                }
            };
        }
        @Override public int getResponseCode() throws IOException { return delegate.getResponseCode(); }
        @Override public long getContentLength() { return delegate.getContentLength(); }
        @Override public String getResponseHeader(String name) { return delegate.getResponseHeader(name); }
        @Override public void close() { delegate.close(); }
        @Override public Map<String, List<String>> getHeaderFields() { return delegate.getHeaderFields(); }
        @Override public InputStream getErrorStream() throws IOException { return delegate.getErrorStream(); }
    }
}

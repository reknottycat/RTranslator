package nie.translator.rtranslator.downloader;

import com.downloader.Status;
import com.downloader.request.DownloadRequest;
import com.downloader.request.DownloadRequestBuilder;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class FallbackHttpClientTest {
    private MockWebServer server;
    private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
    private Path directory;
    private byte[] model;
    private DownloadRequest request;
    private FallbackHttpClient client;

    @Before public void setUp() throws Exception {
        directory = Files.createTempDirectory("model-http-");
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                Function<RecordedRequest, MockResponse> route = routes.get(request.getPath());
                return route == null ? error(404) : route.apply(request);
            }
        });
        server.start();
        model = new byte[32 * 1024];
        for (int i = 0; i < model.length; i++) model[i] = (byte) (i * 31 + 7);
        request = new DownloadRequestBuilder("https://github.com/niedev/example/model.zip",
                directory.toString(), "model.zip")
                .setConnectTimeout(500).setReadTimeout(500).setUserAgent("model-test")
                .setHeader("Authorization", "test-must-not-be-forwarded").build();
        request.setStatus(Status.RUNNING);
        request.setDownloadId(4242);
    }

    @After public void tearDown() throws Exception {
        if (client != null) client.close();
        server.shutdown();
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }

    private String url(String path) { return server.url(path).toString(); }

    private void connect(boolean withDigest, String... paths) throws Exception {
        ModelDownloadManifest.Entry entry = new ModelDownloadManifest.Entry(model.length,
                withDigest ? sha(model) : null);
        List<String> urls = new java.util.ArrayList<>();
        for (String path : paths) urls.add(url(path));
        client = new FallbackHttpClient(new FallbackHttpClient.Sources() {
            @Override public List<String> urls(String canonical) { return urls; }
            @Override public ModelDownloadManifest.Entry metadata(String canonical) { return entry; }
        });
        client.connect(request);
    }

    private static String sha(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        }
        return result.toString();
    }

    private int rangeStart(RecordedRequest request) {
        String range = request.getHeader("Range");
        return Integer.parseInt(range.substring(6, range.length() - 1));
    }

    private MockResponse good(RecordedRequest request) {
        int start = rangeStart(request);
        return new MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes " + start + "-" + (model.length - 1) + "/" + model.length)
                .setHeader("ETag", "\"official\"")
                .setBody(new Buffer().write(model, start, model.length - start));
    }

    private MockResponse error(int status) {
        return new MockResponse().setResponseCode(status);
    }

    private void resumeAt(int offset) throws IOException {
        request.setDownloadedBytes(offset);
        request.setTotalBytes(model.length);
        Files.write(directory.resolve("model.zip.temp"), Arrays.copyOf(model, offset));
    }

    private byte[] readAll() throws IOException {
        assertEquals(206, client.getResponseCode());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream stream = client.getInputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            request.setDownloadedBytes(request.getDownloadedBytes() + count);
        }
        return output.toByteArray();
    }

    @Test public void errorsRotateWithoutChangingCanonicalIdentityOrForwardingCredentials() throws Exception {
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        routes.put("/fail", ex -> { failed.incrementAndGet(); return error(503); });
        routes.put("/good", ex -> { authorization.set(ex.getHeader("Authorization")); return good(ex); });
        String canonical = request.getUrl();
        connect(true, "/fail", "/good");
        assertArrayEquals(model, readAll());
        assertEquals(1, failed.get());
        assertEquals(canonical, request.getUrl());
        assertEquals(4242, request.getDownloadId());
        assertNull(authorization.get());
    }

    @Test public void html200IsRejectedEvenWhenItsSizeMatches() throws Exception {
        routes.put("/html", ex -> new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html").setBody(new Buffer().write(model)));
        routes.put("/good", this::good);
        connect(true, "/html", "/good");
        assertArrayEquals(model, readAll());
    }

    @Test public void initial200UsesLogicalResumeAdapterButResumed200IsRejected() throws Exception {
        routes.put("/full", ex -> new MockResponse().setResponseCode(200).setBody(new Buffer().write(model)));
        connect(true, "/full");
        assertArrayEquals(model, readAll());
        client.close();
        resumeAt(100);
        connect(true, "/full");
        assertEquals(503, client.getResponseCode());
        assertEquals(100, request.getDownloadedBytes());
        assertEquals(100, Files.size(directory.resolve("model.zip.temp")));
    }

    @Test public void wrongRangeIsRejectedAndResumeHashesSavedPrefix() throws Exception {
        resumeAt(1234);
        routes.put("/wrong", ex -> new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-" + (model.length - 1) + "/" + model.length)
                .setBody(new Buffer().write(model)));
        routes.put("/good", this::good);
        connect(true, "/wrong", "/good");
        assertArrayEquals(Arrays.copyOfRange(model, 1234, model.length), readAll());
    }

    @Test public void allConnectionFailuresPreserveCheckpoint() throws Exception {
        resumeAt(4096);
        routes.put("/a", ex -> error(404));
        routes.put("/b", ex -> error(429));
        connect(true, "/a", "/b");
        assertEquals(503, client.getResponseCode());
        assertEquals(4096, request.getDownloadedBytes());
        assertArrayEquals(Arrays.copyOf(model, 4096), Files.readAllBytes(directory.resolve("model.zip.temp")));
    }

    @Test public void interruptedBodyRotatesAtExactConsumedOffset() throws Exception {
        AtomicReference<String> resumedRange = new AtomicReference<>();
        routes.put("/short", ex -> new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-" + (model.length - 1) + "/" + model.length)
                .setHeader("ETag", "\"official\"")
                .setBody(new Buffer().write(model, 0, 5000))
                .setHeader("Content-Length", model.length)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
        routes.put("/good", ex -> { resumedRange.set(ex.getHeader("Range")); return good(ex); });
        connect(true, "/short", "/good");
        assertArrayEquals(model, readAll());
        assertEquals("bytes=5000-", resumedRange.get());
    }

    @Test public void badDigestResetsCorruptCheckpointAndDoesNotSilentlyComplete() throws Exception {
        byte[] wrong = model.clone();
        wrong[0] ^= 1;
        Files.write(directory.resolve("model.zip.temp"), new byte[0]);
        routes.put("/bad", ex -> new MockResponse().setResponseCode(200).setBody(new Buffer().write(wrong)));
        connect(true, "/bad");
        try { readAll(); fail("Corrupt file must fail"); } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
        assertEquals(0, request.getDownloadedBytes());
        assertEquals(0, request.getTotalBytes());
        assertFalse(Files.exists(directory.resolve("model.zip.temp")));
    }

    @Test public void excessBodyBytesCannotBecomeACompletedFile() throws Exception {
        routes.put("/extra", ex -> new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-" + (model.length - 1) + "/" + model.length)
                // Deliberately chunked, so a bad server can overrun its declared Range.
                .setChunkedBody(new Buffer().write(model).writeByte(99), 1024));
        connect(true, "/extra");
        try { readAll(); fail("Oversized file must fail"); } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeded"));
        }
        assertEquals(0, request.getDownloadedBytes());
    }

    @Test public void pauseReturnsControlToPrDownloaderWithoutTryingAnotherSource() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        routes.put("/good", this::good);
        routes.put("/unused", ex -> { fallback.incrementAndGet(); return good(ex); });
        connect(true, "/good", "/unused");
        request.setStatus(Status.PAUSED);
        assertEquals(0, client.getInputStream().read(new byte[20]));
        assertEquals(Status.PAUSED, request.getStatus());
        assertEquals(0, fallback.get());
    }

    @Test public void cancelledDownloadNeverStartsAFallbackRequest() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        routes.put("/good", this::good);
        routes.put("/unused", ex -> { fallback.incrementAndGet(); return good(ex); });
        connect(true, "/good", "/unused");
        request.setStatus(Status.CANCELLED);
        try { client.getInputStream().read(); fail("Cancelled request must stop"); } catch (IOException expected) { }
        assertEquals(0, fallback.get());
    }

    @Test public void redirectsDoNotChangeCanonicalUrl() throws Exception {
        routes.put("/redirect", ex -> new MockResponse().setResponseCode(302).setHeader("Location", "/good"));
        routes.put("/good", this::good);
        String original = request.getUrl();
        connect(true, "/redirect");
        assertArrayEquals(model, readAll());
        assertEquals(original, request.getUrl());
    }

    @Test public void bytesPastCheckpointAreDiscardedBeforeAResume() throws Exception {
        resumeAt(4096);
        Files.write(directory.resolve("model.zip.temp"), Arrays.copyOf(model, model.length + 55));
        routes.put("/good", this::good);
        connect(true, "/good");
        assertEquals(4096, Files.size(directory.resolve("model.zip.temp")));
        assertArrayEquals(Arrays.copyOfRange(model, 4096, model.length), readAll());
    }

    @Test public void unknownAssetsAreNotOptedIntoThirdPartyMirrors() {
        assertNull(ModelDownloadManifest.forUrl("https://github.com/private/repository/releases/download/v1/model.zip"));
        ModelDownloadManifest.Entry hy = ModelDownloadManifest.forUrl(
                "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/HY-MT.zip");
        assertEquals(1926506894L, hy.sizeBytes);
        assertNotNull(hy.sha256);
        assertNotNull(ModelDownloadManifest.forUrl(
                "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_initializer.onnx").sha256);
    }
}

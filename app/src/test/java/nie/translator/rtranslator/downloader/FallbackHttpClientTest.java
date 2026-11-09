package nie.translator.rtranslator.downloader;

import com.downloader.Status;
import com.downloader.request.DownloadRequest;
import com.downloader.request.DownloadRequestBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class FallbackHttpClientTest {
    private HttpServer server;
    private Path directory;
    private byte[] model;
    private DownloadRequest request;
    private FallbackHttpClient client;

    @Before public void setUp() throws Exception {
        directory = Files.createTempDirectory("model-http-");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        server.stop(0);
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }

    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

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

    private int rangeStart(HttpExchange exchange) {
        String range = exchange.getRequestHeaders().getFirst("Range");
        return Integer.parseInt(range.substring(6, range.length() - 1));
    }

    private void good(HttpExchange exchange) throws IOException {
        int start = rangeStart(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (model.length - 1) + "/" + model.length);
        exchange.getResponseHeaders().set("ETag", "\"official\"");
        exchange.sendResponseHeaders(206, model.length - start);
        exchange.getResponseBody().write(model, start, model.length - start);
        exchange.close();
    }

    private void error(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
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
        server.createContext("/fail", ex -> { failed.incrementAndGet(); error(ex, 503); });
        server.createContext("/good", ex -> { authorization.set(ex.getRequestHeaders().getFirst("Authorization")); good(ex); });
        String canonical = request.getUrl();
        connect(true, "/fail", "/good");
        assertArrayEquals(model, readAll());
        assertEquals(1, failed.get());
        assertEquals(canonical, request.getUrl());
        assertEquals(4242, request.getDownloadId());
        assertNull(authorization.get());
    }

    @Test public void html200IsRejectedEvenWhenItsSizeMatches() throws Exception {
        server.createContext("/html", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(200, model.length);
            ex.getResponseBody().write(model);
            ex.close();
        });
        server.createContext("/good", this::good);
        connect(true, "/html", "/good");
        assertArrayEquals(model, readAll());
    }

    @Test public void initial200UsesLogicalResumeAdapterButResumed200IsRejected() throws Exception {
        server.createContext("/full", ex -> {
            ex.sendResponseHeaders(200, model.length);
            ex.getResponseBody().write(model);
            ex.close();
        });
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
        server.createContext("/wrong", ex -> {
            ex.getResponseHeaders().set("Content-Range", "bytes 0-" + (model.length - 1) + "/" + model.length);
            ex.sendResponseHeaders(206, model.length);
            ex.getResponseBody().write(model);
            ex.close();
        });
        server.createContext("/good", this::good);
        connect(true, "/wrong", "/good");
        assertArrayEquals(Arrays.copyOfRange(model, 1234, model.length), readAll());
    }

    @Test public void allConnectionFailuresPreserveCheckpoint() throws Exception {
        resumeAt(4096);
        server.createContext("/a", ex -> error(ex, 404));
        server.createContext("/b", ex -> error(ex, 429));
        connect(true, "/a", "/b");
        assertEquals(503, client.getResponseCode());
        assertEquals(4096, request.getDownloadedBytes());
        assertArrayEquals(Arrays.copyOf(model, 4096), Files.readAllBytes(directory.resolve("model.zip.temp")));
    }

    @Test public void interruptedBodyRotatesAtExactConsumedOffset() throws Exception {
        AtomicReference<String> resumedRange = new AtomicReference<>();
        server.createContext("/short", ex -> {
            ex.getResponseHeaders().set("Content-Range", "bytes 0-" + (model.length - 1) + "/" + model.length);
            ex.getResponseHeaders().set("ETag", "\"official\"");
            ex.sendResponseHeaders(206, model.length);
            ex.getResponseBody().write(model, 0, 5000);
            ex.close();
        });
        server.createContext("/good", ex -> { resumedRange.set(ex.getRequestHeaders().getFirst("Range")); good(ex); });
        connect(true, "/short", "/good");
        assertArrayEquals(model, readAll());
        assertEquals("bytes=5000-", resumedRange.get());
    }

    @Test public void badDigestResetsCorruptCheckpointAndDoesNotSilentlyComplete() throws Exception {
        byte[] wrong = model.clone();
        wrong[0] ^= 1;
        Files.write(directory.resolve("model.zip.temp"), new byte[0]);
        server.createContext("/bad", ex -> {
            ex.sendResponseHeaders(200, wrong.length);
            ex.getResponseBody().write(wrong);
            ex.close();
        });
        connect(true, "/bad");
        try { readAll(); fail("Corrupt file must fail"); } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
        assertEquals(0, request.getDownloadedBytes());
        assertEquals(0, request.getTotalBytes());
        assertFalse(Files.exists(directory.resolve("model.zip.temp")));
    }

    @Test public void excessBodyBytesCannotBecomeACompletedFile() throws Exception {
        server.createContext("/extra", ex -> {
            ex.getResponseHeaders().set("Content-Range", "bytes 0-" + (model.length - 1) + "/" + model.length);
            ex.sendResponseHeaders(206, 0); // Deliberately chunked, so a bad server can overrun its Range.
            ex.getResponseBody().write(model);
            ex.getResponseBody().write(99);
            ex.close();
        });
        connect(true, "/extra");
        try { readAll(); fail("Oversized file must fail"); } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeded"));
        }
        assertEquals(0, request.getDownloadedBytes());
    }

    @Test public void pauseReturnsControlToPrDownloaderWithoutTryingAnotherSource() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        server.createContext("/good", this::good);
        server.createContext("/unused", ex -> { fallback.incrementAndGet(); good(ex); });
        connect(true, "/good", "/unused");
        request.setStatus(Status.PAUSED);
        assertEquals(0, client.getInputStream().read(new byte[20]));
        assertEquals(Status.PAUSED, request.getStatus());
        assertEquals(0, fallback.get());
    }

    @Test public void cancelledDownloadNeverStartsAFallbackRequest() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        server.createContext("/good", this::good);
        server.createContext("/unused", ex -> { fallback.incrementAndGet(); good(ex); });
        connect(true, "/good", "/unused");
        request.setStatus(Status.CANCELLED);
        try { client.getInputStream().read(); fail("Cancelled request must stop"); } catch (IOException expected) { }
        assertEquals(0, fallback.get());
    }

    @Test public void redirectsDoNotChangeCanonicalUrl() throws Exception {
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().set("Location", "/good");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/good", this::good);
        String original = request.getUrl();
        connect(true, "/redirect");
        assertArrayEquals(model, readAll());
        assertEquals(original, request.getUrl());
    }

    @Test public void bytesPastCheckpointAreDiscardedBeforeAResume() throws Exception {
        resumeAt(4096);
        Files.write(directory.resolve("model.zip.temp"), Arrays.copyOf(model, model.length + 55));
        server.createContext("/good", this::good);
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
        assertNull(ModelDownloadManifest.forUrl(
                "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_initializer.onnx").sha256);
    }
}

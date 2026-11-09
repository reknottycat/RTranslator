package nie.translator.rtranslator.downloader;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.*;

public class ParallelChunkStreamTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        new Random(123456789L).nextBytes(data);
        return data;
    }

    private static MockResponse serveRange(RecordedRequest request, byte[] content) {
        String range = request.getHeader("Range");
        if (range == null || !range.startsWith("bytes=")) {
            return new MockResponse().setResponseCode(400);
        }
        String[] parts = range.substring(6).split("-");
        int first = Integer.parseInt(parts[0]);
        int last = Integer.parseInt(parts[1]);
        if (first < 0 || last >= content.length || last < first) {
            return new MockResponse().setResponseCode(416);
        }
        return new MockResponse().setResponseCode(206)
                .addHeader("Content-Type", "application/octet-stream")
                .addHeader("Content-Range", "bytes " + first + "-" + last + "/" + content.length)
                .setBody(new Buffer().write(content, first, last - first + 1));
    }

    private static byte[] readAll(ParallelChunkStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = stream.read(buf, 0, buf.length)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Test public void reassemblesOverlappingParallelRangesExactly() throws Exception {
        byte[] content = payload(ParallelChunkStream.CHUNK_SIZE * 2 + 913);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest r) {
                    return serveRange(r, content);
                }
            });
            server.start();
            File temp = temporary.newFolder("reassemble");
            try (ParallelChunkStream stream = new ParallelChunkStream(temp, "model-a",
                    Collections.singletonList(server.url("/model").toString()),
                    0, content.length, 3)) {
                assertArrayEquals(content, readAll(stream));
            }
        }
    }

    @Test public void resumesFromNonAlignedByteCheckpoint() throws Exception {
        byte[] content = payload(ParallelChunkStream.CHUNK_SIZE + 10_043);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest r) {
                    return serveRange(r, content);
                }
            });
            server.start();
            File temp = temporary.newFolder("resume");
            int resume = 1_000_000;
            try (ParallelChunkStream initial = new ParallelChunkStream(temp, "model-b",
                    Collections.singletonList(server.url("/model").toString()),
                    0, content.length, 2)) {
                byte[] prefix = new byte[resume];
                int position = 0;
                while (position < resume) {
                    int got = initial.read(prefix, position, resume - position);
                    assertTrue(got > 0);
                    position += got;
                }
                assertArrayEquals(java.util.Arrays.copyOfRange(content, 0, resume), prefix);
            }
            try (ParallelChunkStream resumed = new ParallelChunkStream(temp, "model-b",
                    Collections.singletonList(server.url("/model").toString()),
                    resume, content.length, 2)) {
                assertArrayEquals(java.util.Arrays.copyOfRange(content, resume, content.length),
                        readAll(resumed));
            }
        }
    }

    @Test public void refusesServerThatIgnoresRange() throws Exception {
        byte[] content = payload(ParallelChunkStream.CHUNK_SIZE + 22);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest r) {
                    return new MockResponse().setResponseCode(200)
                            .setBody(new Buffer().write(content));
                }
            });
            server.start();
            File temp = temporary.newFolder("bad");
            try (ParallelChunkStream stream = new ParallelChunkStream(temp, "model-c",
                    Collections.singletonList(server.url("/model").toString()),
                    0, content.length, 1)) {
                try {
                    readAll(stream);
                    fail("A non-range-compliant proxy must be rejected");
                } catch (java.io.IOException expected) {
                    assertTrue(expected.getMessage().contains("Unable to fetch block"));
                }
            }
        }
    }
}

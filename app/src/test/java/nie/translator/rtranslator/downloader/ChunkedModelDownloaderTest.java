package nie.translator.rtranslator.downloader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Random;

import static org.junit.Assert.*;

/** Checkpoint safety tests with no network access. Live Range correctness needs a TLS device test. */
public class ChunkedModelDownloaderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private static String header(String url, long size, String digest) {
        return "rtranslator-cn-chunks-v1 " + size + " " + ChunkedModelDownloader.CHUNK_BYTES
                + " " + Integer.toHexString(url.hashCode()) + " " + digest;
    }

    @Test public void reusesEveryDurableCompletedChunkAndPublishesExactBytes() throws Exception {
        final String url = "https://github.com/example/official/releases/download/model.bin";
        int size = ChunkedModelDownloader.CHUNK_BYTES + 3477;
        byte[] payload = new byte[size];
        new Random(17).nextBytes(payload);
        String digest = hash(payload);
        File dir = temp.newFolder("checkpoint");
        File destination = new File(dir, "model.bin");
        File staging = new File(dir, "model.bin.cnchunks.part");
        File checkpoint = new File(dir, "model.bin.chunks.done");
        Files.write(staging.toPath(), payload);
        Files.write(checkpoint.toPath(),
                (header(url, size, digest) + "\n0\n1\n").getBytes(StandardCharsets.UTF_8));
        final long[] progress = {-1};
        new ChunkedModelDownloader().download(url, destination,
                new ModelDownloadManifest.Entry(size, digest), 14,
                (completed, total) -> progress[0] = completed);
        assertArrayEquals(payload, Files.readAllBytes(destination.toPath()));
        assertEquals(size, progress[0]);
        assertFalse(staging.exists());
        assertFalse(checkpoint.exists());
    }

    @Test public void rejectsBadAssembledChecksumInsteadOfPublishing() throws Exception {
        String url = "https://github.com/example/official/releases/download/model.bin";
        int size = 1024;
        byte[] content = new byte[size];
        File dir = temp.newFolder("corrupt");
        File output = new File(dir, "model.bin");
        Files.write(new File(dir, "model.bin.cnchunks.part").toPath(), content);
        String expected = hash(new byte[]{1, 2, 3});
        Files.write(new File(dir, "model.bin.chunks.done").toPath(),
                (header(url, size, expected) + "\n0\n").getBytes(StandardCharsets.UTF_8));
        try {
            new ChunkedModelDownloader().download(url, output,
                    new ModelDownloadManifest.Entry(size, expected), 28, (done, total) -> {});
            fail("Must not publish incorrect assembled bytes");
        } catch (java.io.IOException correct) {
            assertTrue(correct.getMessage().contains("SHA-256"));
        }
        assertFalse(output.exists());
        assertFalse(new File(dir, "model.bin.chunks.done").exists());
    }

    @Test public void rejectsInvalidThreadCountBeforeMutation() throws Exception {
        File dir = temp.newFolder("badthreads");
        try {
            new ChunkedModelDownloader().download("https://example.com/file",
                    new File(dir, "file"), new ModelDownloadManifest.Entry(10, null),
                    29, (done, total) -> {});
            fail("28 is the upper limit");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, dir.list().length);
        }
    }
}

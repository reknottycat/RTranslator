package nie.translator.rtranslator.downloader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

public class PickedModelStageTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }

    @Test public void stagesValidModelAndPreservesOriginalSelectedStream() throws Exception {
        byte[] data = "official-test-file".getBytes(StandardCharsets.UTF_8);
        File destination = new File(folder.getRoot(), "HY-MT.zip");
        LocalModelFiles.stagePickedModel(new ByteArrayInputStream(data), destination,
                data.length, sha(data));
        assertArrayEquals(data, Files.readAllBytes(destination.toPath()));
    }

    @Test public void rejectsWrongHashWithoutOverwritingAnExistingFile() throws Exception {
        File destination = new File(folder.getRoot(), "Whisper_encoder.onnx");
        Files.write(destination.toPath(), new byte[]{1, 2, 3});
        byte[] selected = {4, 5, 6, 7};
        try {
            LocalModelFiles.stagePickedModel(new ByteArrayInputStream(selected),
                    destination, selected.length, sha(new byte[]{3, 2, 1, 0}));
            fail("Expected invalid hash rejection");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(destination.toPath()));
    }

    @Test public void rejectsTruncatedAndOversizedInput() throws Exception {
        File destination = new File(folder.getRoot(), "HY-MT.zip");
        for (int size : Arrays.asList(2, 5)) {
            try {
                LocalModelFiles.stagePickedModel(new ByteArrayInputStream(new byte[size]),
                        destination, 3, null);
                fail("Expected size rejection");
            } catch (java.io.IOException expected) {
                assertFalse(destination.exists());
            }
        }
    }

    @Test public void onlyKnownNamesAccepted() {
        assertNotNull(ModelDownloadManifest.forFilename("HY-MT.zip"));
        assertNotNull(ModelDownloadManifest.forFilename("Whisper_cache_initializer.onnx"));
        assertNull(ModelDownloadManifest.forFilename("../HY-MT.zip"));
        assertNull(ModelDownloadManifest.forFilename("evil.apk"));
        assertNull(ModelDownloadManifest.forFilename("folder/HY-MT.zip"));
        assertNull(ModelDownloadManifest.forFilename("x\\\\HY-MT.zip"));
    }

    @Test public void mirrorListPreservesOfficialCanonicalAsset() {
        String url = "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_cache_initializer.onnx";
        assertTrue(DownloadSourceOrder.candidates(url).stream().anyMatch(url::equals));
        assertTrue(DownloadSourceOrder.candidates(url).get(0).startsWith("https://www.modelscope.cn/"));
    }
}

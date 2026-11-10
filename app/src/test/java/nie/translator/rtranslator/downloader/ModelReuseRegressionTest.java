package nie.translator.rtranslator.downloader;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ModelReuseRegressionTest {
    private static final String ROOT = "https://github.com/niedev/RTranslator/releases/download/2.0.0/";
    @Test public void sixWhisperAssetsPreferExactModelScopeMirrors() {
        String[] names = {"Whisper_cache_initializer.onnx", "Whisper_cache_initializer_batch.onnx", "Whisper_encoder.onnx", "Whisper_decoder.onnx", "Whisper_detokenizer.onnx", "Whisper_initializer.onnx"};
        for (String name : names) {
            String original = ROOT + name;
            List<String> sources = DownloadSourceOrder.candidates(original, DownloadSourceOrder.MIRROR_FIRST);
            assertTrue(sources.get(0).startsWith("https://www.modelscope.cn/api/v1/models/liaowenbin/rtranslator-whisper-small-int8/repo?"));
            assertTrue(sources.get(0).endsWith("FilePath=" + name));
            assertEquals(original, sources.get(sources.size()-1));
            assertNotNull(ModelDownloadManifest.forUrl(original).sha256);
        }
    }
    @Test public void hyMtUsesTheOriginalOnnxArchiveNotUnrelatedWeights() {
        String url = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/HY-MT.zip";
        assertEquals("https://www.modelscope.cn/api/v1/models/liaowenbin/rtranslator-hy-mt1.5-1.8b-int8/repo?Revision=master&FilePath=HY-MT.zip", DownloadSourceOrder.candidates(url).get(0));
        assertEquals("15d5dd9b0634fa0daac27fd50a06ec46c940eaadac2409d51361d3aa0ce3aff8", ModelDownloadManifest.forUrl(url).sha256);
    }
    @Test public void unmappedAssetsNeverInventAModelScopeUrl() {
        String url = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla.zip";
        assertNull(VerifiedModelMirrors.forUrl(url));
        for (String s : DownloadSourceOrder.candidates(url)) assertFalse(s.contains("modelscope.cn"));
    }
    @Test public void unknownUrlsNeverReachThirdPartyProxies() {
        String privateUrl = "https://example.org/private-model?token=secret";
        assertEquals(java.util.Collections.singletonList(privateUrl), DownloadSourceOrder.candidates(privateUrl));
    }
    @Test public void explicitOfficialAndMirrorsOnlyStrategiesRemainAvailable() {
        String url = ROOT + "Whisper_initializer.onnx";
        assertEquals(url, DownloadSourceOrder.candidates(url, DownloadSourceOrder.OFFICIAL_FIRST).get(0));
        assertFalse(DownloadSourceOrder.candidates(url, DownloadSourceOrder.MIRRORS_ONLY).contains(url));
    }
    @Test public void importedNamesMustBeOriginalAndPathSafe() {
        assertNull(ModelDownloadManifest.forFilename("../Whisper_encoder.onnx"));
        assertNull(ModelDownloadManifest.forFilename("folder/Whisper_encoder.onnx"));
        assertNull(ModelDownloadManifest.forFilename("..\\Whisper_encoder.onnx"));
        assertNull(ModelDownloadManifest.forFilename("HY-MT"));
        assertNull(ModelDownloadManifest.forFilename("model.safetensors"));
        assertNotNull(ModelDownloadManifest.forFilename("HY-MT.zip"));
    }
    @Test public void allMappedModelsHavePinnedHashesAndSixtyMappings() {
        int count = 0;
        for (Map.Entry<String,ModelDownloadManifest.Entry> e : ModelDownloadManifest.entries().entrySet()) {
            if (VerifiedModelMirrors.forUrl(e.getKey()) != null) {
                count++;
                assertTrue(e.getValue().sha256.matches("[0-9a-f]{64}"));
            }
        }
        assertEquals(60, count);
    }
}

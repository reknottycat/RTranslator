package nie.translator.rtranslator.downloader;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Exact upstream release metadata read on 2026-11-09; never trust proxy-supplied hashes.
 * Sources: api.github.com/repos/niedev/RTranslator/releases/tags/2.0.0 and
 * api.github.com/repos/niedev/OnnxModelsEnhancer/releases/tags/v1.0.0-beta.
 * The older ONNX assets have no upstream digest: retain the existing ONNX validation.
 */
public final class ModelDownloadManifest {
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    static {
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_cache_initializer.onnx", 25368443L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_decoder.onnx", 179109694L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_embed_and_lm_head.onnx", 524712277L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_encoder.onnx", 266487014L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_cache_initializer.onnx", 14315416L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_cache_initializer_batch.onnx", 14314991L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_decoder.onnx", 181741988L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_detokenizer.onnx", 472377L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_encoder.onnx", 92487048L, null);
        add("https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_initializer.onnx", 71378L, null);
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/HY-MT.zip", 1926506894L, "15d5dd9b0634fa0daac27fd50a06ec46c940eaadac2409d51361d3aa0ce3aff8");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Madlad.zip", 1482639037L, "ec3183e6106182938fe095d8e48057e2412ded278b560c2dc818c72cfedd682d");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla.zip", 2122038340L, "20ed17ecb78a180086a3c203637cc2c7854809b6dd178c943ad0ceec013210ad");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ar.zip", 50803894L, "89bc7167cab49f3addbde95094939f2f1d70196e9201eeae45db9f28a585eb86");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_bg.zip", 50421613L, "a1d4c8239313b867b7a6c4d78694bc394f67136dc8c826e705a5487aeb1a4522");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_bn.zip", 30222728L, "c64980237f2ebf74550f6bdb57bda4fa24e4e5a6801d4e91f722419b0f7f821d");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ca.zip", 51113805L, "2fff9292f0f92ce4c98e4de79c044eda6b78c87d072b1222ac394b945ab20afb");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_cs.zip", 51226547L, "733c8724eee3a8abed501f6afb7df09b3aad6300e3f27708aa27c35d504a4cc1");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_da.zip", 29796163L, "df13e7c65538523d2aa2c4328c3e51ac53048e77fae272a9b58a1a277fbfe6a1");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_de.zip", 51156184L, "145b202b587331fafedd0dc452e270a980d3b96085b41e2e8171e99866c5520a");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_el.zip", 29541936L, "6ccb31021bbb8ea96cdd724d75387429ab4e28dbb92e264b3c333ddb5a5062f4");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_es.zip", 51601622L, "a65fc7524707d3b7cd8532c25478f1fa7e1602bed245cba6ec318cbc6eece53b");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_et.zip", 50740703L, "42ffd5f7601137b6c878c0bfc7532142acd7b3fc1dd2da4b1d03d9d1e20f8e91");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_eu.zip", 50662777L, "3f705785adf1a449966e4826d31132b39303dcf5057ff90813bc23dce3fb462c");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_fa.zip", 30627759L, "719339730dd0ddf4a0b728630a767742c2e817ac1cfc8ed483600c8f3510e176");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_fi.zip", 50785989L, "8819e2cbbc0919632beb2a917eeadfaeab984db0add5db12fc076eceb56ffe62");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_fr.zip", 51976983L, "d71c6aeea3aa1640b1f4964234152400594be825dbc62d788c7f67e44cd701c6");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_gl.zip", 51088708L, "834c587eee667f5897f8cbb5d5bc93f2f2e57372eb853265e16f432e47451254");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_gu.zip", 30001433L, "23a1aaf31eace8d62f4c020cde9ff86d331f5c2e868d12f75bda9b17471bf275");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_he.zip", 30593572L, "43faeb398602b6ad8df6cbfc935d9c513c9a6a907505ce1899f041c174dc4cfd");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_hi.zip", 30351217L, "0ccc37dcd2eb2ab0914a437e64767b1e4e2c5a34ae947d4426502333da395327");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_hu.zip", 42255798L, "8b9a1e2254f37e34b1e63f13f3b7c9228d188c5a4721a4fae789a6b29c4416e0");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_id.zip", 29822409L, "65af4a36dcf309f1e65c7566cbe8f2978aa0bd8052be0f4d3645dd1331556163");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_is.zip", 50622311L, "e2823167d7e24c34099e638f4f37e651ee3a1e3039acdb70ac319211757503ed");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_it.zip", 52740817L, "f40ce9dcc349c6664c35b7e1a88ce54497b14a2e8e9e02cc64e6acbba1c0bcad");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ja.zip", 84873778L, "7dc89da8423afde983f101e01d14c9739b81dc73bd7750f33ea71ee375cd7e9f");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_kn.zip", 30523663L, "1640526d5a93219d1c7ef9a8e066fdb79a1ffe22ad09b0ed19575478b4f0f7ed");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ko.zip", 98098818L, "f9efbe1afe382d291a71acf14e4bcec535eaf5f277d553ce67fd6c06d21bfb65");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_lt.zip", 40315762L, "ad935eb335be7a390389768ddca128265e2c209c14a835af69f50b6d2ec29bdd");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_lv.zip", 41081846L, "30d16500e23ae87bcff48fd9b0eec1fd78290d4d85f0812cc9d42927a427f01a");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ml.zip", 29836904L, "0ab0eb8619843b7fc2b0b36fb5b3b47722ab4c9e229a3b5e108b020ede919475");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ms.zip", 30567332L, "27453dad3549658c3de03a24521dab689f4cebc576d528197415dea36ef35479");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_nb.zip", 31230239L, "4256cbad49056138a3509bb21d7ad7c82b6a2cd30d323236f8381d62ef132d1d");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_nl.zip", 51612817L, "3bf627a6d046cc6a77516933c44c5822c9b83e54c0c34f14b4eb8919aa15ce57");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_no.zip", 31230239L, "f3863592894636e126ff9fe4e71f846f9ef91293851e31abe365645c1c4cc8f8");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_pl.zip", 50385588L, "f7609e4970120fbe47296271b9a5637ff95b07678abbcf34565156584a142739");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_pt.zip", 51457385L, "af138cfc59a580d7305c30f0dbffb9ef431bbd765e73c1f2972d471e56dc1e21");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ro.zip", 29723676L, "f387dd11879241c9048d78202783db438ec9668974a9074620507aaa30f61914");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ru.zip", 47451389L, "07c6ac87f5c44bd1d6dee009a2c73aad446b85212dcfa31a0f0610ff3659cb9a");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_sk.zip", 40577569L, "c30c1a1b72d5888c96b2e0a85b64d089f01ad95b8af1e3ec7fd2b77ea502734f");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_sl.zip", 51063944L, "c06ec66c6e04699c06ad3a47ead3ce6bee4ddc52f5060bc35077e218566e7dad");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_sv.zip", 30497449L, "ec90a285b3f547d7f68750ad90ea54c8b4ef880c2575e71ee4702f37f73bd728");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_ta.zip", 51471942L, "8279dd0260e3988f6ae5e88d8c009e4cadb92de2b8ae8eb17018c4f8f4c47482");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_te.zip", 30324559L, "6b1d5c48eb3c2209c82aed789feaa24b3fa013f9cbe3e82ee0d2fc0b500783b2");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_th.zip", 50681175L, "96fae0295e77a5ddedcab7903f0f5b297befdcd2af1f537e6d8a6ccf5f663721");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_tr.zip", 30340095L, "f249196facb03bac707c84167f89b297723a4f271576f62090149b492322fca8");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_uk.zip", 39743992L, "b4f8229ce5ca82f474407809471f2cd1f692fd0bfa06907640b3ca87bf0a53d6");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_vi.zip", 42060193L, "c6c2dc34448337c3ee61e5d0d10140771323a812b8bc7ea161619c221f3b751f");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_zh.zip", 86662393L, "371315e902b20618d2a45a250ca05d8904a7834c4e4c261287d4eca5fccf03eb");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Mozilla_zh_hant.zip", 74146433L, "5125d35931afb61c0b4e1f6a39337c6ffad17f5864ed0016f8b0a7039c23a549");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/Tatoeba.zip", 625386360L, "6bb6f6054ee4870c8f6bc9de43fedfe7b62a7e368c50dc765f0a7a28512f9dd5");
        add("https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/TranslationDictionaries.zip", 113506724L, "250d00e1ae22a6b1e7ea999a5b1dc3746d37e0ef1eba6b884895dc5e7b458477");
    }

    private ModelDownloadManifest() {}

    private static void add(String url, long size, String sha256) {
        String pinned = sha256 == null ? VerifiedModelMirrors.digestForUrl(url) : sha256;
        ENTRIES.put(url, new Entry(size, pinned));
    }

    public static Entry forUrl(String canonicalUrl) {
        return ENTRIES.get(canonicalUrl);
    }

    /** Exact, allowlisted filenames only; never trust the file-picker display name as a path. */
    public static Entry forFilename(String filename) {
        if (filename == null || filename.isEmpty() || filename.contains("/")
                || filename.indexOf((char) 92) >= 0 || filename.contains("..")) return null;
        for (Map.Entry<String, Entry> entry : ENTRIES.entrySet()) {
            if (entry.getKey().endsWith("/" + filename)) return entry.getValue();
        }
        return null;
    }

    public static Map<String, Entry> entries() {
        return Collections.unmodifiableMap(ENTRIES);
    }

    public static final class Entry {
        public final long sizeBytes;
        public final String sha256;

        Entry(long sizeBytes, String sha256) {
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }
    }
}


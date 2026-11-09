package nie.translator.rtranslator.downloader;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Determines the order of validated download endpoints for an official public release asset. */
public final class DownloadSourceOrder {
    public static final int MIRROR_FIRST = 0;
    public static final int OFFICIAL_FIRST = 1;
    public static final int MIRRORS_ONLY = 2;
    private static final String PREFS = "default";
    private static final String KEY = "cn_model_download_source";
    private static volatile int mode = MIRROR_FIRST;

    private DownloadSourceOrder() {}

    public static void load(Context context) {
        mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, MIRROR_FIRST);
    }

    public static void set(Context context, int selected) {
        if (selected < MIRROR_FIRST || selected > MIRRORS_ONLY) {
            throw new IllegalArgumentException("Invalid source order");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, selected).apply();
        mode = selected;
        DownloadTrace.state("已选择下载策略：" + modeName(selected));
    }

    public static int get() { return mode; }

    public static String modeName(int selected) {
        switch (selected) {
            case OFFICIAL_FIRST: return "官方优先";
            case MIRRORS_ONLY: return "只使用镜像";
            default: return "国内镜像优先";
        }
    }

    /** The CDN hosts are third-party mirrors, not an official mainland-China model repository. */
    public static List<String> candidates(String officialUrl) {
        List<String> result = new ArrayList<>();
        if (ModelDownloadManifest.forUrl(officialUrl) == null) {
            result.add(officialUrl);
            return result;
        }
        // ModelScope is deliberately NOT advertised unless the exact RTranslator asset
        // (including digest) has been verified. Similar model names are not interchangeable.
        String mirrorA = "https://gh-proxy.org/" + officialUrl;
        String mirrorB = "https://ghproxy.imciel.com/" + officialUrl;
        String mirrorC = "https://gh-proxy.com/" + officialUrl;
        if (mode == OFFICIAL_FIRST) result.add(officialUrl);
        result.add(mirrorA);
        result.add(mirrorB);
        result.add(mirrorC);
        if (mode == MIRROR_FIRST) result.add(officialUrl);
        return result;
    }

    public static int parallelThreads(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("cn_model_parallel_threads", 14) == 28 ? 28 : 14;
    }

    public static void setParallelThreads(Context context, int threads) {
        if (threads != 14 && threads != 28) throw new IllegalArgumentException("Invalid thread count");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("cn_model_parallel_threads", threads).apply();
        DownloadTrace.record("并发下载设置：" + threads + " 条连接，每块 4 MiB");
    }

    public static String label(String url) {
        if (url.startsWith("https://gh-proxy.org/")) return "国内代理 A · gh-proxy.org";
        if (url.startsWith("https://ghproxy.imciel.com/")) return "国内代理 B · imciel";
        if (url.startsWith("https://gh-proxy.com/")) return "国内代理 C · gh-proxy.com";
        return "GitHub 官方";
    }
}

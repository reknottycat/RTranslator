package nie.translator.rtranslator.downloader;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Alternate endpoints are allowed only for fixed public model assets. */
public final class DownloadSourceOrder {
    public static final int MIRROR_FIRST = 0;
    public static final int OFFICIAL_FIRST = 1;
    public static final int MIRRORS_ONLY = 2;
    private static final String PREFS = "default";
    private static final String KEY = "cn_model_download_source";
    private static volatile int mode = MIRROR_FIRST;
    private DownloadSourceOrder() {}
    public static void load(Context context) {
        int saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, MIRROR_FIRST);
        mode = saved >= 0 && saved <= 2 ? saved : MIRROR_FIRST;
    }
    public static void set(Context context, int selected) {
        if (selected < 0 || selected > 2) throw new IllegalArgumentException("Invalid source order");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, selected).apply();
        mode = selected;
        DownloadTrace.state("已选择下载策略：" + modeName(selected));
    }
    public static int get() { return mode; }
    public static String modeName(int selected) {
        switch (selected) {
            case OFFICIAL_FIRST: return "GitHub 官方优先";
            case MIRRORS_ONLY: return "ModelScope / 代理，不走 GitHub 直连";
            default: return "ModelScope 国内优先";
        }
    }
    public static List<String> candidates(String canonicalUrl) {
        List<String> urls = new ArrayList<>();
        if (ModelDownloadManifest.forUrl(canonicalUrl) == null) {
            urls.add(canonicalUrl);
            return urls;
        }
        int selected = mode;
        if (selected == OFFICIAL_FIRST) urls.add(canonicalUrl);
        String verified = VerifiedModelMirrors.forUrl(canonicalUrl);
        if (verified != null) urls.add(verified);
        urls.add("https://gh-proxy.org/" + canonicalUrl);
        urls.add("https://ghproxy.imciel.com/" + canonicalUrl);
        urls.add("https://gh-proxy.com/" + canonicalUrl);
        if (selected == MIRROR_FIRST) urls.add(canonicalUrl);
        return urls;
    }
    public static int parallelThreads(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("cn_model_parallel_threads", 14) == 28 ? 28 : 14;
    }
    public static void setParallelThreads(Context context, int threads) {
        if (threads != 14 && threads != 28) throw new IllegalArgumentException("Invalid thread count");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("cn_model_parallel_threads", threads).apply();
        DownloadTrace.record("并发下载设置：" + threads + " 条连接，每块 4 MiB");
    }
    public static String label(String url) {
        if (url.startsWith("https://www.modelscope.cn/")) return "ModelScope 魔搭 · 已核验的社区镜像";
        if (url.startsWith("https://gh-proxy.org/")) return "代理 A · gh-proxy.org";
        if (url.startsWith("https://ghproxy.imciel.com/")) return "代理 B · ghproxy.imciel.com";
        if (url.startsWith("https://gh-proxy.com/")) return "代理 C · gh-proxy.com";
        return "GitHub 官方";
    }
}

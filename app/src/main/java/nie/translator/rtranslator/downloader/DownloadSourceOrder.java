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
        String ghfast = "https://ghfast.top/" + officialUrl;
        String ghproxy = "https://ghproxy.net/" + officialUrl;
        String backup1 = "https://gh-proxy.org/" + officialUrl;
        String backup2 = "https://ghproxy.imciel.com/" + officialUrl;
        if (mode == OFFICIAL_FIRST) result.add(officialUrl);
        result.add(ghfast);
        result.add(ghproxy);
        result.add(backup1);
        result.add(backup2);
        if (mode == MIRROR_FIRST) result.add(officialUrl);
        return result;
    }

    public static String label(String url) {
        if (url.startsWith("https://ghfast.top/")) return "国内加速 A · ghfast.top";
        if (url.startsWith("https://ghproxy.net/")) return "国内加速 B · ghproxy.net";
        if (url.startsWith("https://gh-proxy.org/")) return "备用镜像 C · gh-proxy.org";
        if (url.startsWith("https://ghproxy.imciel.com/")) return "备用镜像 D · imciel";
        return "GitHub 官方";
    }
}

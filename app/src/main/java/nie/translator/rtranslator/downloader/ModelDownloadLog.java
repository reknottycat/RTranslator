package nie.translator.rtranslator.downloader;

import android.util.Log;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bounded, in-memory diagnostics for the onboarding model download screen. */
public final class ModelDownloadLog {
    private static final String TAG = "RTranslatorModelDownload";
    private static final int MAX_ENTRIES = 20;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static String activeSource = "尚未连接下载源";
    private static String latestEvent = "等待模型下载";
    private ModelDownloadLog() {}

    public static synchronized void record(String event, String sourceUrl) {
        String label = label(sourceUrl);
        activeSource = label;
        latestEvent = event;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = time + "  " + label + "  " + event;
        if (EVENTS.size() == MAX_ENTRIES) EVENTS.removeFirst();
        EVENTS.addLast(line);
        Log.i(TAG, line);
    }

    public static synchronized String summary() {
        return "当前来源：" + activeSource + "\n状态：" + latestEvent;
    }

    public static synchronized String history() {
        StringBuilder sb = new StringBuilder();
        for (String line : EVENTS) sb.append(line).append('\n');
        return sb.toString();
    }

    private static String label(String raw) {
        if (raw == null) return "未知来源";
        try {
            URL url = new URL(raw);
            String host = url.getHost();
            if ("github.com".equalsIgnoreCase(host)) return "GitHub 官方";
            if ("gh-proxy.org".equalsIgnoreCase(host)) return "gh-proxy.org";
            if ("ghproxy.imciel.com".equalsIgnoreCase(host)) return "ghproxy.imciel.com";
            return host;
        } catch (Exception ignored) {
            return "未知来源";
        }
    }
}

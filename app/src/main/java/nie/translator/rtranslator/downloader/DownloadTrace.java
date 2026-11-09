package nie.translator.rtranslator.downloader;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small, process-local diagnostic log. Never store credentials, query strings or full URLs. */
public final class DownloadTrace {
    private static final int MAX_LINES = 120;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static String source = "尚未连接";
    private static String asset = "";
    private static String phase = "等待下载";
    private static long bytes = 0;
    private static long total = -1;

    private DownloadTrace() {}

    public static synchronized void record(String message) {
        String safe = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        if (safe.length() > 240) safe = safe.substring(0, 240);
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = stamp + "  " + safe;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
        try { Log.i("RTranslatorDownload", line); } catch (RuntimeException notAndroidRuntime) { /* JVM tests */ }
    }

    public static synchronized void trying(String sourceName, String fileName,
                                             int ordinal, int count, long offset, long expected) {
        source = sourceName;
        asset = fileName;
        phase = "正在连接 · 第 " + ordinal + "/" + count + " 个来源";
        bytes = offset;
        total = expected;
        record("尝试来源 " + ordinal + "/" + count + "：" + sourceName + "；文件 " + fileName
                + "；续传位置 " + offset + " B");
    }

    public static synchronized void connected(String sourceName, int httpCode) {
        source = sourceName;
        phase = "已连接 HTTP " + httpCode + " · 正在传输";
        record("连接成功：" + sourceName + "，HTTP " + httpCode);
    }

    public static synchronized void segmentProgress(int completed, int totalBlocks, String sourceName) {
        source = sourceName + "（并发分块）";
        phase = "4 MiB 并发下载 · 已缓存 " + completed + "/" + totalBlocks + " 块";
        if (completed == 1 || completed == totalBlocks || completed % 8 == 0) {
            record("缓存完成 " + completed + "/" + totalBlocks + " 分块；最新成功来源 " + sourceName);
        }
    }

    public static synchronized void transferred(long current, long expected) {
        bytes = current;
        if (expected > 0) total = expected;
    }

    public static synchronized void failed(String sourceName, String explanation) {
        phase = "来源失败 · 正在切换或等待重试";
        record("下载来源失败：" + sourceName + "；原因：" + explanation);
    }

    public static synchronized void state(String explanation) {
        phase = explanation;
        record(explanation);
    }

    public static synchronized String status() {
        String sizes = total > 0
                ? String.format(Locale.getDefault(), "%.1f / %.1f MiB", bytes / 1048576.0, total / 1048576.0)
                : (bytes > 0 ? bytes + " B" : "0 B");
        return "来源：" + source + (asset.isEmpty() ? "" : "\n文件：" + asset)
                + "\n状态：" + phase + "\n实际传输：" + sizes;
    }

    public static synchronized String recentLines() {
        StringBuilder builder = new StringBuilder();
        for (String line : LINES) builder.append(line).append('\n');
        return builder.length() == 0 ? "尚无下载日志" : builder.toString();
    }
}

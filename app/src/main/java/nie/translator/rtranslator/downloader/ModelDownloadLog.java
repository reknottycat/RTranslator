package nie.translator.rtranslator.downloader;
/** Compatibility facade; all screens and transports now use one diagnostic history. */
public final class ModelDownloadLog {
    private ModelDownloadLog() {}
    public static void record(String event, String sourceUrl) { DownloadTrace.state(event); }
    public static String summary() { return DownloadTrace.status(); }
    public static String history() { return DownloadTrace.recentLines(); }
}

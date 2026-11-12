package nie.translator.rtranslator.tools;

import java.util.Locale;

/** Pure matching rules shared by offline voice discovery and selection. */
public final class OfflineVoicePolicy {
    private OfflineVoicePolicy() {}

    public static int score(Locale requested, Locale available, boolean networkRequired,
                            boolean notInstalled, int quality) {
        if (requested == null || available == null || networkRequired || notInstalled
                || !requested.getLanguage().equals(available.getLanguage())) return -1;
        // Prefer the requested regional pronunciation, then voice quality.
        return quality + (requested.equals(available) ? 10000 : 0);
    }
}

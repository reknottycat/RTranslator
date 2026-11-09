package nie.translator.rtranslator.voice_translation.live;

/** Deterministic policies shared by the live UI and host-side tests. No Android dependencies. */
public final class CaptionPolicy {
    public static final int AUTO = 0, FIRST = 1, SECOND = 2;
    public static final int MAX_PENDING_AUDIO = 3;
    public static final int MAX_HISTORY = 200;
    public static final int SEGMENT_MS = 6000;
    public static final int SILENCE_MS = 700;
    private CaptionPolicy() {}

    public static boolean usable(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty() || "undefined".equalsIgnoreCase(t) || "[UNDEFINED]".equals(t)) return false;
        if (t.startsWith("[") || t.startsWith("(") || t.startsWith("<|")) return false;
        return t.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    /** Scores are decoder scores, not calibrated probabilities or speaker identities. */
    public static int choose(String a, double scoreA, String b, double scoreB,
                             boolean languageAMatches, boolean languageBMatches) {
        boolean va = usable(a), vb = usable(b);
        if (!va && !vb) return -1;
        if (!va) return SECOND;
        if (!vb) return FIRST;
        if (languageAMatches != languageBMatches) return languageAMatches ? FIRST : SECOND;
        double sa = Double.isFinite(scoreA) ? scoreA : -Double.MAX_VALUE;
        double sb = Double.isFinite(scoreB) ? scoreB : -Double.MAX_VALUE;
        return sa >= sb ? FIRST : SECOND;
    }
}

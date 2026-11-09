package nie.translator.rtranslator.voice_translation.live;

/** Validates the existing text + language + one-digit-length protocol without changing the wire format. */
public final class ConversationPacket {
    public final String text;
    public final String language;
    private ConversationPacket(String text, String language) { this.text = text; this.language = language; }
    public static ConversationPacket parse(String raw) {
        if (raw == null || raw.length() < 3 || raw.length() > 65536) return null;
        int n = raw.charAt(raw.length() - 1) - '0';
        if (n < 2 || n > 9 || raw.length() < n + 1) return null;
        int split = raw.length() - n - 1;
        String lang = raw.substring(split, raw.length() - 1);
        if (!lang.matches("[a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,4})?")) return null;
        return new ConversationPacket(raw.substring(0, split), lang);
    }
}

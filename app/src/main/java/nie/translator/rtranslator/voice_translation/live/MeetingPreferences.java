package nie.translator.rtranslator.voice_translation.live;

import android.content.Context;
import android.content.SharedPreferences;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

public final class MeetingPreferences {
    public static final String ENABLED = "meeting.enabled";
    public static final String SEND_MIC = "meeting.sendMic";
    public static final String INPUT = "conversation.inputLanguage";
    public static final String BOOST = "meeting.inputBoost";
    public static volatile String runtimeStatus = "";
    private MeetingPreferences() {}
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("default", Context.MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean(ENABLED, false); }
    public static boolean sendsMic(Context c) { return prefs(c).getBoolean(SEND_MIC, false); }
    public static CustomLocale input(Global g) {
        String code = prefs(g).getString(INPUT, "");
        if (code == null || code.isEmpty()) {
            CustomLocale initial = g.getLanguage(true);
            prefs(g).edit().putString(INPUT, initial.getCode()).apply();
            return initial;
        }
        return CustomLocale.getInstance(code);
    }
    public static void setInput(Global g, CustomLocale language) {
        prefs(g).edit().putString(INPUT, language.getCode()).apply();
        if (g.getBluetoothCommunicator() != null) g.getBluetoothCommunicator().sendLanguage(language);
    }
}

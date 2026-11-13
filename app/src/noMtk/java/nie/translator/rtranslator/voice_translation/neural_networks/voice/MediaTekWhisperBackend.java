package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;

import java.io.File;
import java.io.IOException;

final class MediaTekWhisperBackend {
    private MediaTekWhisperBackend() { }

    static AcceleratedWhisperBackend load(Context context, File model) throws IOException {
        throw new IOException("MediaTek Whisper is unavailable in this build");
    }
}

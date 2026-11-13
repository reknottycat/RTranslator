package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;

import java.io.File;
import java.io.IOException;

final class QnnWhisperBackend implements AcceleratedWhisperBackend {
    private final long handle;

    private QnnWhisperBackend(long handle) {
        this.handle = handle;
    }

    static QnnWhisperBackend load(Context context, File pack) throws IOException {
        System.loadLibrary("rtranslator_qnn_whisper");
        File libraries = new File(context.getApplicationInfo().nativeLibraryDir);
        long handle = open(new File(pack, "encoder.onnx").getAbsolutePath(),
                new File(pack, "decoder.onnx").getAbsolutePath(),
                new File(libraries, "libQnnHtp.so").getAbsolutePath(),
                new File(libraries, "libonnxruntime_providers_qnn.so").getAbsolutePath(),
                new File(context.getCacheDir(), "rtranslator-qnn").getAbsolutePath());
        if (handle == 0) {
            throw new IOException("Unable to open Qualcomm Whisper sessions");
        }
        return new QnnWhisperBackend(handle);
    }

    @Override
    public int[] recognize(float[] features, int languageToken, double[] score) throws IOException {
        long[] tokens = transcribe(handle, features, languageToken, score);
        if (tokens == null || !Double.isFinite(score[0])) {
            throw new IOException("Invalid Qualcomm Whisper result");
        }
        int[] result = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            result[i] = Math.toIntExact(tokens[i]);
        }
        return result;
    }

    @Override
    public void close() {
        close(handle);
    }

    private static native long open(String encoder, String decoder, String htpLibrary,
                                    String providerLibrary, String profilePrefix) throws IOException;

    private static native long[] transcribe(long handle, float[] features, int languageToken,
                                            double[] score) throws IOException;

    private static native void close(long handle);
}

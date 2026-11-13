package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import java.io.IOException;

interface AcceleratedWhisperBackend extends AutoCloseable {
    int[] recognize(float[] features, int languageToken, double[] score) throws IOException;

    @Override
    void close();
}

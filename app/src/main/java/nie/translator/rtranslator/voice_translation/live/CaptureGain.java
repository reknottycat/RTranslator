package nie.translator.rtranslator.voice_translation.live;

/** Bounded software input gain, not denoising or beamforming. Owned by the audio thread. */
public final class CaptureGain {
    private float gain = 1f;
    public void reset() { gain = 1f; }
    public float getGain() { return gain; }

    public void process(float[] samples, int offset, int length) {
        if (samples == null || offset < 0 || length < 0 || offset > samples.length - length)
            throw new IllegalArgumentException("Invalid PCM range");
        if (length == 0) return;
        double energy = 0;
        float peak = 0;
        for (int i = offset; i < offset + length; i++) {
            float v = Float.isFinite(samples[i]) ? samples[i] : 0f;
            samples[i] = v;
            energy += (double) v * v;
            peak = Math.max(peak, Math.abs(v));
        }
        float rms = (float) Math.sqrt(energy / length);
        // Do not turn near-silence into a loud noise floor. Existing VAD is still required.
        float target = rms < 0.002f ? 1f : Math.min(4f, 0.085f / rms);
        if (peak > 0) target = Math.min(target, 0.95f / peak);
        target = Math.max(0.05f, target);
        float endGain = target < gain ? target : gain + 0.12f * (target - gain);
        float initial = gain;
        for (int i = offset; i < offset + length; i++) {
            float g = initial + (endGain - initial) * (i - offset + 1f) / length;
            samples[i] = Math.max(-0.95f, Math.min(0.95f, samples[i] * g));
        }
        gain = endGain;
    }
}

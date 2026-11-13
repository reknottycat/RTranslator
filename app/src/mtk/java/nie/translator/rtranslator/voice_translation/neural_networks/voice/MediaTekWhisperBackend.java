package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.LiteRtException;
import com.google.ai.edge.litert.TensorBuffer;
import com.google.ai.edge.litert.TensorType;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Runs the verified MT6991 Whisper Tiny graph through LiteRT's MediaTek dispatch. */
final class MediaTekWhisperBackend implements AcceleratedWhisperBackend {
    private static final String ENCODE = "encode";
    private static final String DECODE = "decode";
    private static final int FEATURES = 80 * 3000;
    private static final int TOKEN_SLOTS = 128;
    private static final int VOCABULARY = 51865;
    private static final int END = 50257;
    private static final int START = 50258;
    private static final int FIRST_LANGUAGE = 50259;
    private static final int LAST_LANGUAGE = 50357;
    private static final int TRANSCRIBE = 50359;
    private static final int NO_TIMESTAMPS = 50363;
    private static final int BLANK = 220;
    private static final int PROMPT_LENGTH = 4;
    private static final float MASKED_OUT = -0.7f * Float.MAX_VALUE;
    private static final float[] CAUSAL_MASK = createCausalMask();

    // openai/whisper-tiny generation_config.json; special tokens are excluded separately.
    private static final int[] SUPPRESSED_TOKENS = {
        1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60, 61, 62, 63,
        90, 91, 92, 93, 359, 503, 522, 542, 873, 893, 902, 918, 922, 931, 1350, 1853,
        1982, 2460, 2627, 3246, 3253, 3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647,
        7273, 9061, 9383, 10428, 10929, 11938, 12033, 12331, 12562, 13793, 14157,
        14635, 15265, 15618, 16553, 16604, 18362, 18956, 20075, 21675, 22520, 26130,
        26161, 26435, 28279, 29464, 31650, 32302, 32470, 36865, 42863, 47425, 49870, 50254
    };

    private final Environment environment;
    private final CompiledModel model;
    private boolean closed;

    private MediaTekWhisperBackend(Environment environment, CompiledModel model) {
        this.environment = environment;
        this.model = model;
    }

    static MediaTekWhisperBackend load(Context context, File modelFile) throws IOException {
        if (!MediaTekModelPack.isSupportedDevice() || modelFile == null || !modelFile.isFile()) {
            throw new IOException("MediaTek Whisper requires MT6991 and the verified model file");
        }
        Environment environment = null;
        CompiledModel model = null;
        boolean opened = false;
        try {
            // The AAR declares optional vendor libraries; constructing this environment has no download path.
            environment = Environment.create(context,
                    new BuiltinNpuAcceleratorProvider(context, MediaTekModelPack::isSupportedDevice));
            if (!environment.getAvailableAccelerators().contains(Accelerator.NPU)) {
                throw new IOException("MediaTek NPU dispatch is unavailable");
            }
            model = CompiledModel.create(modelFile.getAbsolutePath(),
                    new CompiledModel.Options(Accelerator.NPU, Accelerator.CPU), environment);
            verifyTensor(model.getInputTensorType("args_0", ENCODE), TensorType.ElementType.FLOAT, 1, 80, 3000);
            verifyTensor(model.getOutputTensorType("output_0", ENCODE), TensorType.ElementType.FLOAT, 1, 1500, 384);
            verifyTensor(model.getInputTensorType("args_0", DECODE), TensorType.ElementType.FLOAT, 1, 1500, 384);
            verifyTensor(model.getInputTensorType("args_1", DECODE), TensorType.ElementType.INT, 1, TOKEN_SLOTS);
            verifyTensor(model.getInputTensorType("args_2", DECODE), TensorType.ElementType.FLOAT,
                    1, 1, TOKEN_SLOTS, TOKEN_SLOTS);
            verifyTensor(model.getOutputTensorType("output_0", DECODE), TensorType.ElementType.FLOAT,
                    1, TOKEN_SLOTS, VOCABULARY);
            MediaTekWhisperBackend backend = new MediaTekWhisperBackend(environment, model);
            opened = true;
            return backend;
        } catch (LiteRtException | RuntimeException | LinkageError failure) {
            throw new IOException("Unable to initialize MediaTek Whisper", failure);
        } finally {
            if (!opened) closeModelAndEnvironment(model, environment);
        }
    }

    @Override
    public synchronized int[] recognize(float[] features, int languageToken, double[] score) throws IOException {
        if (closed || features == null || features.length != FEATURES || score == null || score.length != 1
                || languageToken < FIRST_LANGUAGE || languageToken > LAST_LANGUAGE) {
            throw new IOException("Invalid MediaTek Whisper inference input");
        }
        checkCancellation();
        try (TensorBuffer input = model.createInputBuffer("args_0", ENCODE);
             TensorBuffer states = model.createOutputBuffer("output_0", ENCODE);
             TensorBuffer tokenIds = model.createInputBuffer("args_1", DECODE);
             TensorBuffer mask = model.createInputBuffer("args_2", DECODE);
             TensorBuffer logits = model.createOutputBuffer("output_0", DECODE)) {
            input.writeFloat(features);
            // The verified encode graph consists solely of one MediaTek DISPATCH_OP.
            // LiteRT propagates its execution failure; it has no CPU implementation for that op.
            model.run(Collections.singletonList(input), Collections.singletonList(states), ENCODE);
            mask.writeFloat(CAUSAL_MASK);
            return decode(languageToken, score, Arrays.asList(states, tokenIds, mask), tokenIds, logits);
        } catch (LiteRtException | RuntimeException | LinkageError failure) {
            throw new IOException("MediaTek Whisper execution failed", failure);
        }
    }

    private int[] decode(int languageToken, double[] score, List<TensorBuffer> inputs,
                         TensorBuffer tokenIds, TensorBuffer logits) throws LiteRtException, IOException {
        int[] ids = new int[TOKEN_SLOTS];
        ids[0] = START;
        ids[1] = languageToken;
        ids[2] = TRANSCRIBE;
        ids[3] = NO_TIMESTAMPS;
        List<TensorBuffer> outputs = Collections.singletonList(logits);
        double totalLogProbability = 0;
        for (int next = PROMPT_LENGTH; next < TOKEN_SLOTS; next++) {
            checkCancellation();
            tokenIds.writeInt(ids);
            model.run(inputs, outputs, DECODE);
            float[] values = logits.readFloat();
            if (values.length != TOKEN_SLOTS * VOCABULARY) {
                throw new IOException("Unexpected MediaTek Whisper decoder output size");
            }
            int offset = (next - 1) * VOCABULARY;
            int token = bestToken(values, offset, next == PROMPT_LENGTH);
            totalLogProbability += logProbability(values, offset, token);
            if (token == END) {
                score[0] = totalLogProbability / (next - PROMPT_LENGTH + 1);
                return Arrays.copyOfRange(ids, PROMPT_LENGTH, next);
            }
            ids[next] = token;
        }
        throw new IOException("MediaTek Whisper exhausted the decoder window without a stop token");
    }

    private static int bestToken(float[] values, int offset, boolean first) throws IOException {
        int best = -1;
        int suppression = 0;
        for (int token = 0; token <= END; token++) {
            if (suppression < SUPPRESSED_TOKENS.length && token == SUPPRESSED_TOKENS[suppression]) {
                suppression++;
                continue;
            }
            if (first && (token == BLANK || token == END)) continue;
            float value = values[offset + token];
            if (Float.isFinite(value) && (best < 0 || value > values[offset + best])) best = token;
        }
        if (best < 0) throw new IOException("MediaTek Whisper returned invalid logits");
        return best;
    }

    private static double logProbability(float[] values, int offset, int token) throws IOException {
        float max = Float.NEGATIVE_INFINITY;
        int end = offset + VOCABULARY;
        for (int i = offset; i < end; i++) max = Math.max(max, values[i]);
        if (!Float.isFinite(max)) throw new IOException("MediaTek Whisper returned non-finite logits");
        double denominator = 0;
        for (int i = offset; i < end; i++) denominator += Math.exp(values[i] - max);
        return values[offset + token] - max - Math.log(denominator);
    }

    private static void verifyTensor(TensorType type, TensorType.ElementType element, Integer... dimensions)
            throws IOException {
        if (type.getElementType() != element || !type.getLayout().getDimensions().equals(Arrays.asList(dimensions))) {
            throw new IOException("Unexpected MediaTek Whisper tensor signature");
        }
    }

    private static float[] createCausalMask() {
        float[] result = new float[TOKEN_SLOTS * TOKEN_SLOTS];
        Arrays.fill(result, MASKED_OUT);
        for (int row = 0; row < TOKEN_SLOTS; row++) {
            int start = row * TOKEN_SLOTS;
            Arrays.fill(result, start, start + row + 1, 0f);
        }
        return result;
    }

    private static void checkCancellation() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("MediaTek Whisper was cancelled");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeModelAndEnvironment(model, environment);
    }

    private static void closeModelAndEnvironment(CompiledModel model, Environment environment) {
        try {
            if (model != null) model.close();
        } finally {
            if (environment != null) environment.close();
        }
    }
}

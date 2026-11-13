package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Imports the pinned LiteRT Whisper Tiny model for the MediaTek MT6991. */
public final class MediaTekModelPack {
    public static final int BACKEND_WAITING = 0;
    public static final int BACKEND_NPU = 1;
    public static final int BACKEND_CPU_FALLBACK = 2;

    private static final String TAG = "MediaTekModelPack";
    private static final String MODEL_NAME = "whisper_tiny_30s_f32_MediaTek_MT6991.tflite";
    public static final String MODEL_DOWNLOAD = "https://huggingface.co/litert-community/whisper-tiny/resolve/"
            + "d92f0ee1d4316ab0099add38d67ac5f2cc31c8fd/"
            + MODEL_NAME;
    private static final long MODEL_BYTES = 155717804L;
    private static final String MODEL_SHA256 = "539386c03dcac03f1409d672a9805973f5afc93a6f1d0e0d808570d16d111aa9";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "mtk-model-import"));
    private static volatile int progress = -1;
    private static volatile boolean importFailed;
    private static volatile int backendStatus = BACKEND_WAITING;

    private MediaTekModelPack() { }

    public static boolean isSupportedDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        return isMt6991(Build.SOC_MODEL) || isMt6991(Build.HARDWARE) || isMt6991(Build.BOARD);
    }

    private static boolean isMt6991(String value) { return "mt6991".equalsIgnoreCase(value); }

    public static File activeFile(Context context) {
        File model = modelFile(context);
        return model.isFile() && model.length() == MODEL_BYTES ? model : null;
    }

    public static int importProgress() { return progress; }

    public static boolean importFailed() { return importFailed; }

    public static int backendStatus() { return backendStatus; }

    public static void reportBackend(Context context, File usedFile, int status) {
        if (usedFile != null && usedFile.equals(activeFile(context))) backendStatus = status;
    }

    public static synchronized void startImport(Context context, Uri uri) {
        if (!isSupportedDevice() || progress >= 0) return;
        progress = 0;
        importFailed = false;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                importSelectedModel(app, uri);
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "MediaTek model import failed", e);
                importFailed = true;
            } finally {
                progress = -1;
            }
        });
    }

    private static void importSelectedModel(Context context, Uri uri) throws IOException {
        File model = modelFile(context);
        File directory = model.getParentFile();
        if (directory == null || (!directory.mkdirs() && !directory.isDirectory())) {
            throw new IOException("Model storage unavailable");
        }
        File temporary = new File(directory, "incoming.tflite");
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot open model file");
            copyAndVerify(input, temporary);
            Files.move(temporary.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            backendStatus = BACKEND_WAITING;
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static void copyAndVerify(InputStream input, File target) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[128 * 1024];
        long written = 0;
        try (FileOutputStream file = new FileOutputStream(target);
             BufferedOutputStream output = new BufferedOutputStream(file, buffer.length)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                written += count;
                if (written > MODEL_BYTES) throw new IOException("Model file exceeds verified size");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                progress = (int) Math.min(99, written * 100 / MODEL_BYTES);
            }
            if (written != MODEL_BYTES || !MODEL_SHA256.equals(hex(digest.digest()))) {
                throw new IOException("MediaTek model checksum mismatch");
            }
            output.flush();
            file.getFD().sync();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value & 0xf0) >> 4, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    private static File modelFile(Context context) {
        return new File(new File(context.getFilesDir(), "mediatek-whisper-d92f0ee"), MODEL_NAME);
    }
}

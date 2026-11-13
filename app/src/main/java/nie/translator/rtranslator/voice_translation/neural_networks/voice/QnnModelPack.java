package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports the pinned Qualcomm Whisper Small packs into the test application's own storage. */
public final class QnnModelPack {
    public static final int BACKEND_WAITING = 0;
    public static final int BACKEND_QNN = 1;
    public static final int BACKEND_CPU_FALLBACK = 2;

    private static final String TAG = "QnnModelPack";
    private static final String SOURCE = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/"
            + "qai-hub-models/models/whisper_small_quantized/releases/v0.66.0/";
    private static final String ARCHIVE_PREFIX = "whisper_small_quantized-precompiled_qnn_onnx-w8a16-";
    private static final String[] FILENAMES = {"encoder.onnx", "decoder.onnx", "encoder.bin", "decoder.bin", "metadata.json"};
    private static final String ENCODER_ONNX = "fa4ba753f87f60dd59ab822558b6eb2e1813b146db89856a9e7dff4db3b71aeb";
    private static final String DECODER_ONNX = "f7efbd6e3006ba9800449c92666f5d0a699478cfc4b1a1803173f8d7a38e111d";
    private static final Pack ELITE = new Pack("elite", "qualcomm_snapdragon_8_elite_for_galaxy",
            new long[]{12676, 39434, 113091008, 225151280, 43727}, new String[]{ENCODER_ONNX, DECODER_ONNX,
                    "e4b24b7b6b5ba333926660f213836fe199160eb0e6d4e9a0d0153c6d0f0c9abf",
                    "076cd7b4a5dc0c3b9958dd839d104d02e247e1ae36b7aaa7f53cf1adae891b10",
                    "90ef472b3f24ebd695a82d66f8c851b924e89f50c5dbbd827c84d31775a795af"});
    private static final Pack ELITE_GEN5 = new Pack("elite-gen5", "qualcomm_snapdragon_8_elite_gen5_for_galaxy",
            new long[]{12676, 39434, 113770944, 225290544, 43742}, new String[]{ENCODER_ONNX, DECODER_ONNX,
                    "8ee815ece1b4a3a72b67c6bb8753efe7076568e364ef2cb4bafd6764f5267757",
                    "c117a5cf414986b7bb3b725c676020203430757437dd885b749958be4146fa6c",
                    "114de9c21a5ef0eacc7cd1de317a29b6ed1a9924e96f7c41e41d7ba0cab5d5e5"});
    public static final String ELITE_DOWNLOAD = SOURCE + ELITE.archiveRoot + ".zip";
    public static final String ELITE_GEN5_DOWNLOAD = SOURCE + ELITE_GEN5.archiveRoot + ".zip";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "qnn-model-import"));
    private static int importProgress = -1;
    private static boolean importFailed;
    private static int backendStatus = BACKEND_WAITING;

    private QnnModelPack() { }

    public static boolean isQualcommDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        String manufacturer = Build.SOC_MANUFACTURER;
        return "qualcomm".equalsIgnoreCase(manufacturer) || "qti".equalsIgnoreCase(manufacturer)
                || ((manufacturer == null || manufacturer.isEmpty() || "unknown".equalsIgnoreCase(manufacturer))
                && "qcom".equalsIgnoreCase(Build.HARDWARE));
    }

    public static synchronized int importProgress() { return importProgress; }

    public static synchronized boolean importFailed() { return importFailed; }

    public static synchronized int backendStatus() { return backendStatus; }

    public static synchronized File activeDirectory(Context context) {
        try {
            String name = new String(marker(context).readFully(), StandardCharsets.US_ASCII);
            if (ELITE.directoryName.equals(name) || ELITE_GEN5.directoryName.equals(name)) {
                File directory = new File(root(context), name);
                if (directory.isDirectory()) return directory;
            }
        } catch (IOException ignored) {
            // A fresh installation has no active pack.
        }
        return null;
    }

    public static synchronized void reportBackend(Context context, int status) {
        reportBackend(context, activeDirectory(context), status);
    }

    public static synchronized void reportBackend(Context context, File usedDirectory, int status) {
        File current = activeDirectory(context);
        if (current != null && current.equals(usedDirectory)) backendStatus = status;
    }

    public static synchronized void startImport(Context context, Uri uri) {
        if (!isQualcommDevice() || importProgress >= 0) return;
        importProgress = 0;
        importFailed = false;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                importSelectedZip(app, uri);
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Qualcomm model import failed", e);
                synchronized (QnnModelPack.class) { importFailed = true; }
            } finally {
                synchronized (QnnModelPack.class) { importProgress = -1; }
            }
        });
    }

    private static void importSelectedZip(Context context, Uri uri) throws IOException {
        File base = root(context);
        if (!base.mkdirs() && !base.isDirectory()) throw new IOException("Model storage unavailable");
        File temporary = new File(base, "incoming");
        removeTemporaryFiles(temporary);
        if (!temporary.mkdir()) throw new IOException("Cannot create model staging directory");
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot open model ZIP");
            Pack pack = extractVerifiedPack(input, temporary);
            File destination = new File(base, pack.directoryName);
            if (!destination.isDirectory()) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            activate(context, pack.directoryName);
        } finally {
            removeTemporaryFiles(temporary);
        }
    }

    private static Pack extractVerifiedPack(InputStream input, File temporary) throws IOException {
        Pack pack = null;
        Set<String> extracted = new HashSet<>();
        boolean sawDirectory = false;
        long completed = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (pack == null) pack = identifyPack(name);
                String prefix = pack.archiveRoot + "/";
                if (!name.startsWith(prefix)) throw new IOException("Unexpected model ZIP path");
                String filename = name.substring(prefix.length());
                if (entry.isDirectory() && filename.isEmpty() && !sawDirectory) {
                    sawDirectory = true;
                    if (zip.read() != -1) throw new IOException("Unexpected data in ZIP directory");
                } else {
                    int index = fileIndex(filename);
                    if (!extracted.add(filename)) throw new IOException("Duplicate model file");
                    copyAndVerify(zip, new File(temporary, filename), pack, index, completed);
                    completed += pack.sizes[index];
                }
                zip.closeEntry();
            }
        }
        if (pack == null || extracted.size() != FILENAMES.length) throw new IOException("Incomplete Qualcomm model ZIP");
        return pack;
    }

    private static Pack identifyPack(String firstEntry) throws IOException {
        if (firstEntry.startsWith(ELITE.archiveRoot + "/")) return ELITE;
        if (firstEntry.startsWith(ELITE_GEN5.archiveRoot + "/")) return ELITE_GEN5;
        throw new IOException("Choose a supported Qualcomm Whisper Small v0.66.0 ZIP");
    }

    private static int fileIndex(String filename) throws IOException {
        for (int i = 0; i < FILENAMES.length; i++) {
            if (FILENAMES[i].equals(filename)) return i;
        }
        throw new IOException("Unexpected file in Qualcomm model ZIP");
    }

    private static void copyAndVerify(ZipInputStream zip, File destination, Pack pack, int index, long completed)
            throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[128 * 1024];
        long written = 0;
        try (FileOutputStream file = new FileOutputStream(destination);
             BufferedOutputStream output = new BufferedOutputStream(file, buffer.length)) {
            int count;
            while ((count = zip.read(buffer)) != -1) {
                written += count;
                if (written > pack.sizes[index]) throw new IOException("Model file exceeds verified size");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                synchronized (QnnModelPack.class) {
                    importProgress = (int) Math.min(99, (completed + written) * 100 / pack.totalSize);
                }
            }
            if (written != pack.sizes[index] || !hex(digest.digest()).equals(pack.hashes[index])) {
                throw new IOException("Model checksum mismatch for " + destination.getName());
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

    private static synchronized void activate(Context context, String name) throws IOException {
        AtomicFile marker = marker(context);
        FileOutputStream output = marker.startWrite();
        try {
            output.write(name.getBytes(StandardCharsets.US_ASCII));
            marker.finishWrite(output);
            backendStatus = BACKEND_WAITING;
        } catch (IOException e) {
            marker.failWrite(output);
            throw e;
        }
    }

    private static void removeTemporaryFiles(File directory) throws IOException {
        for (String filename : FILENAMES) Files.deleteIfExists(new File(directory, filename).toPath());
        Files.deleteIfExists(directory.toPath());
    }

    private static AtomicFile marker(Context context) { return new AtomicFile(new File(root(context), "active")); }

    private static File root(Context context) { return new File(context.getFilesDir(), "qnn-whisper-v0.66.0"); }

    private static final class Pack {
        private final String directoryName;
        private final String archiveRoot;
        private final long[] sizes;
        private final String[] hashes;
        private final long totalSize;

        private Pack(String directoryName, String chipset, long[] sizes, String[] hashes) {
            this.directoryName = directoryName;
            this.archiveRoot = ARCHIVE_PREFIX + chipset;
            this.sizes = sizes;
            this.hashes = hashes;
            long total = 0;
            for (long size : sizes) total += size;
            totalSize = total;
        }
    }
}

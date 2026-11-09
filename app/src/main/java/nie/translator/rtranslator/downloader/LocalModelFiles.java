package nie.translator.rtranslator.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Reuses an official model copied into the app's external files directory. */
public final class LocalModelFiles {
    private static final int BUFFER_SIZE = 64 * 1024;

    private LocalModelFiles() {}

    /**
     * Run on a worker thread. The caller supplies an official manifest entry and a source
     * directly beneath Context.getExternalFilesDir(null), using the official filename.
     * Returns false for a missing or invalid source, so the normal download can proceed.
     * An I/O failure is different: it is propagated, rather than triggering another large
     * download when copying failed because the destination has no space or is unavailable.
     *
     * A null hash is allowed for older release assets without a published SHA-256. Those
     * assets still require the app's ONNX integrity check before being marked complete.
     * Neither a rejected source nor a failed copy modifies the existing destination.
     * The source is retained, including after a successful import.
     */
    public static boolean importIfValid(File source, File destination, long expectedSize,
                                        String sha256) throws IOException {
        if (source == null || destination == null || expectedSize <= 0) {
            throw new IllegalArgumentException("Model paths and exact positive size are required");
        }
        if (sha256 != null && !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected a SHA-256 hex digest");
        }
        checkPath(source);
        checkPath(destination);
        if (!source.getName().equals(destination.getName())) {
            throw new IllegalArgumentException("Sideloaded model must retain its official filename");
        }
        checkInterrupted();

        // Reuse a complete internal copy when only the saved download state was lost.
        if (matches(destination, expectedSize, sha256)) {
            return true;
        }
        if (source.getCanonicalFile().equals(destination.getCanonicalFile())) {
            return false;
        }
        if (!source.isFile() || source.length() != expectedSize) {
            return false;
        }

        File parent = destination.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create model destination directory");
        }
        // Staging beside the destination keeps publication on the same filesystem.
        File temporary = File.createTempFile(".sideload-", ".tmp", parent);
        try {
            MessageDigest digest = sha256 == null ? null : newDigest();
            long copied = 0;
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkInterrupted();
                    if (count > expectedSize - copied) {
                        return false;
                    }
                    output.write(buffer, 0, count);
                    if (digest != null) digest.update(buffer, 0, count);
                    copied += count;
                }
                if (copied != expectedSize || !matchesDigest(digest, sha256)) {
                    return false;
                }
                checkInterrupted();
                output.getFD().sync();
            }
            checkInterrupted();
            // Do not fall back to deleting/replacing the old file non-atomically.
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    /**
     * Copy a user-selected Android document to the app external import folder, checking
     * official size and (when published) SHA-256 before atomically making it visible.
     * The original document is never modified. Caller owns and closes the input stream.
     */
    public static void stagePickedModel(InputStream input, File destination, long expectedSize,
                                        String sha256) throws IOException {
        if (input == null || destination == null || expectedSize <= 0) {
            throw new IllegalArgumentException("Missing model document or expected size");
        }
        if (sha256 != null && !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid expected SHA-256");
        }
        checkPath(destination);
        checkInterrupted();
        if (matches(destination, expectedSize, sha256)) return;
        File parent = destination.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create model import directory");
        }
        File temporary = File.createTempFile(".picked-model-", ".tmp", parent);
        try {
            MessageDigest digest = sha256 == null ? null : newDigest();
            long copied = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkInterrupted();
                    if (count > expectedSize - copied) {
                        throw new IOException("Selected file is larger than the official model");
                    }
                    output.write(buffer, 0, count);
                    if (digest != null) digest.update(buffer, 0, count);
                    copied += count;
                }
                if (copied != expectedSize || !matchesDigest(digest, sha256)) {
                    throw new IOException("Model size or SHA-256 differs from official release");
                }
                output.getFD().sync();
            }
            checkInterrupted();
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static boolean matches(File file, long expectedSize, String sha256) throws IOException {
        checkInterrupted();
        if (!file.isFile() || file.length() != expectedSize) return false;
        if (sha256 == null) return true;
        MessageDigest digest = newDigest();
        long read = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkInterrupted();
                if (count > expectedSize - read) return false;
                digest.update(buffer, 0, count);
                read += count;
            }
        }
        return read == expectedSize && matchesDigest(digest, sha256);
    }

    private static boolean matchesDigest(MessageDigest digest, String sha256) {
        if (sha256 == null) return true;
        byte[] actual = digest.digest();
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(sha256.substring(i * 2, i * 2 + 2), 16);
        }
        return MessageDigest.isEqual(actual, expected);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void checkPath(File file) {
        for (java.nio.file.Path component : file.toPath()) {
            if ("..".equals(component.toString())) {
                throw new IllegalArgumentException("Model paths cannot contain parent traversal");
            }
        }
        if (file.getName().isEmpty() || ".".equals(file.getName())) {
            throw new IllegalArgumentException("Expected a model filename");
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model import interrupted");
        }
    }
}

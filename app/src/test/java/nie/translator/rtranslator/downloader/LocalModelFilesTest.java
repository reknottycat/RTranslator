package nie.translator.rtranslator.downloader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.*;

public class LocalModelFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void verifiedImportCreatesDestinationAndRetainsSource() throws Exception {
        byte[] content = "official model bytes".getBytes(StandardCharsets.UTF_8);
        File source = modelIn("external", content);
        File destination = new File(temporary.getRoot(), "internal/Translation/model.bin");

        assertTrue(LocalModelFiles.importIfValid(source, destination, content.length, sha256(content)));

        assertArrayEquals(content, Files.readAllBytes(source.toPath()));
        assertArrayEquals(content, Files.readAllBytes(destination.toPath()));
        assertEquals(1, destination.getParentFile().list().length);
    }

    @Test public void wrongHashDoesNotReplaceExistingDestination() throws Exception {
        byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
        byte[] corrupt = "corrupt!".getBytes(StandardCharsets.UTF_8);
        byte[] original = "old file".getBytes(StandardCharsets.UTF_8);
        File source = modelIn("external", corrupt);
        File destination = modelIn("internal", original);

        assertFalse(LocalModelFiles.importIfValid(source, destination, expected.length, sha256(expected)));

        assertArrayEquals(original, Files.readAllBytes(destination.toPath()));
        assertArrayEquals(corrupt, Files.readAllBytes(source.toPath()));
        assertEquals(1, destination.getParentFile().list().length);
    }

    @Test public void truncatedSourceFallsBackWithoutCreatingDestination() throws Exception {
        File source = modelIn("external", new byte[] {1, 2});
        File destination = new File(temporary.getRoot(), "internal/model.bin");

        assertFalse(LocalModelFiles.importIfValid(source, destination, 3, null));
        assertFalse(destination.exists());
        assertTrue(source.exists());
    }

    @Test public void validInternalFileNeedsNoExternalCopy() throws Exception {
        byte[] content = new byte[] {1, 2, 3, 4};
        File destination = modelIn("internal", content);
        File absentSource = new File(temporary.getRoot(), "external/model.bin");
        long modified = destination.lastModified();

        assertTrue(LocalModelFiles.importIfValid(absentSource, destination, content.length, sha256(content)));
        assertEquals(modified, destination.lastModified());
        assertFalse(absentSource.exists());
    }

    @Test public void sameFileIsValidatedWithoutSelfOverwrite() throws Exception {
        byte[] content = new byte[] {1, 2, 3, 4};
        File source = modelIn("external", content);

        assertTrue(LocalModelFiles.importIfValid(source, source, content.length, sha256(content)));
        assertFalse(LocalModelFiles.importIfValid(source, source, content.length, sha256(new byte[4])));
        assertArrayEquals(content, Files.readAllBytes(source.toPath()));
    }

    @Test public void oldAssetWithoutPublishedHashStillRequiresExactSize() throws Exception {
        byte[] content = new byte[] {4, 5, 6};
        File source = modelIn("external", content);
        File destination = new File(temporary.getRoot(), "internal/model.bin");

        assertTrue(LocalModelFiles.importIfValid(source, destination, content.length, null));
        assertArrayEquals(content, Files.readAllBytes(destination.toPath()));
    }

    @Test public void cancellationPreservesFilesAndInterruptFlag() throws Exception {
        byte[] original = new byte[] {9, 8, 7};
        File source = modelIn("external", new byte[] {1, 2, 3});
        File destination = modelIn("internal", original);
        Thread.currentThread().interrupt();
        try {
            LocalModelFiles.importIfValid(source, destination, 3, null);
            fail("Expected cancellation");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertArrayEquals(original, Files.readAllBytes(destination.toPath()));
        assertEquals(1, destination.getParentFile().list().length);
        assertTrue(source.exists());
    }

    @Test public void copyFailureIsReportedInsteadOfPretendingSourceWasInvalid() throws Exception {
        File source = modelIn("external", new byte[] {1, 2, 3});
        File parentThatIsAFile = temporary.newFile("not-a-directory");
        File destination = new File(parentThatIsAFile, "model.bin");

        try {
            LocalModelFiles.importIfValid(source, destination, 3, null);
            fail("Expected destination failure");
        } catch (IOException expected) {
            assertTrue(source.exists());
            assertTrue(parentThatIsAFile.isFile());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void differentFilenameCannotBeImportedAsAnotherModel() throws Exception {
        File source = modelIn("external", new byte[] {1});
        LocalModelFiles.importIfValid(source, new File(temporary.getRoot(), "other.bin"), 1, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parentTraversalIsRejected() throws Exception {
        File source = modelIn("external", new byte[] {1});
        LocalModelFiles.importIfValid(source,
                new File(temporary.getRoot(), "internal/../model.bin"), 1, null);
    }

    private File modelIn(String directory, byte[] content) throws IOException {
        File folder = temporary.newFolder(directory);
        File file = new File(folder, "model.bin");
        Files.write(file.toPath(), content);
        return file;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }
}

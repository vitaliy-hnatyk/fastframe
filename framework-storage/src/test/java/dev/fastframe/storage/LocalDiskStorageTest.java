package dev.fastframe.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

final class LocalDiskStorageTest {
    @TempDir Path temp;

    @Test void writeReadDeleteNested() throws Exception {
        LocalDiskStorage storage = new LocalDiskStorage(temp, 1024);
        storage.put("images/photo.bin", new byte[]{0, -1, 42});
        assertArrayEquals(new byte[]{0, -1, 42}, storage.get("images/photo.bin").orElseThrow());
        assertTrue(storage.exists("images/photo.bin"));
        assertTrue(storage.delete("images/photo.bin"));
        assertFalse(storage.delete("images/photo.bin"));
        assertTrue(storage.get("images/photo.bin").isEmpty());
    }

    @Test void rejectsTraversalAndOversize() throws Exception {
        LocalDiskStorage storage = new LocalDiskStorage(temp, 2);
        for (String key : new String[]{"../secret", "/outside", "a/../b", "a\\b", "C:drive", "a//b", ".", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> storage.put(key, new byte[]{1}));
        }
        assertThrows(IOException.class, () -> storage.put("okay", new byte[]{1,2,3}));
    }

    @Test void rejectsSymlinkWhereSupported() throws Exception {
        Path outside = Files.createTempDirectory("outside-store");
        Files.writeString(outside.resolve("secret"), "private", StandardCharsets.UTF_8);
        Path link = temp.resolve("symlink");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | IOException | SecurityException notSupported) { return; }
        LocalDiskStorage storage = new LocalDiskStorage(temp, 1024);
        assertThrows(IOException.class, () -> storage.get("symlink/secret"));
    }
}

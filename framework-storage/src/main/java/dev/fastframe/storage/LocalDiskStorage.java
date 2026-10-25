package dev.fastframe.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** Small-file local storage, isolated below one fixed directory. Not a race-proof sandbox. */
public final class LocalDiskStorage implements Storage {
    private final Path root;
    private final long maxFileBytes;

    public LocalDiskStorage(Path directory, long maxFileBytes) throws IOException {
        if (maxFileBytes < 1 || maxFileBytes > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("File limit must be 1..100 MiB");
        }
        Objects.requireNonNull(directory, "directory");
        Path normalized = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        this.root = normalized.toRealPath();
        this.maxFileBytes = maxFileBytes;
    }

    private Path safePath(String key, boolean createParent) throws IOException {
        Path relative = Path.of(StorageKeys.validate(key));
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IllegalArgumentException("Invalid path");
        Path parent = root;
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            parent = parent.resolve(relative.getName(i));
            if (Files.isSymbolicLink(parent)) throw new IOException("Symbolic-link directories are forbidden");
            if (createParent && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(parent); } catch (java.nio.file.FileAlreadyExistsException ignored) { }
            }
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid parent directory");
            }
        }
        if (Files.isSymbolicLink(path)) throw new IOException("Symbolic-link files are forbidden");
        return path;
    }

    @Override public void put(String key, byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        if (content.length > maxFileBytes) throw new IOException("File exceeds size limit");
        Path target = safePath(key, true);
        Path temp = Files.createTempFile(target.getParent(), ".fastframe-", ".tmp");
        try {
            Files.write(temp, content, StandardOpenOption.TRUNCATE_EXISTING);
            if (Files.isSymbolicLink(target)) throw new IOException("Symbolic-link files are forbidden");
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    @Override public Optional<byte[]> get(String key) throws IOException {
        Path file = safePath(key, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maxFileBytes) {
            throw new IOException("Not a regular file or size limit exceeded");
        }
        try (var input = Files.newInputStream(file)) {
            byte[] data = input.readNBytes(Math.toIntExact(maxFileBytes + 1));
            if (data.length > maxFileBytes) throw new IOException("File exceeds size limit");
            return Optional.of(data);
        }
    }

    @Override public boolean exists(String key) throws IOException {
        return Files.isRegularFile(safePath(key, false), LinkOption.NOFOLLOW_LINKS);
    }

    @Override public boolean delete(String key) throws IOException {
        Path file = safePath(key, false);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.deleteIfExists(file);
    }
}

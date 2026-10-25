package dev.fastframe.storage;

import java.io.IOException;
import java.util.Optional;

/** Bounded whole-file storage operations. I/O is blocking: use a worker thread. */
public interface Storage {
    void put(String key, byte[] content) throws IOException;
    Optional<byte[]> get(String key) throws IOException;
    boolean exists(String key) throws IOException;
    boolean delete(String key) throws IOException;
}

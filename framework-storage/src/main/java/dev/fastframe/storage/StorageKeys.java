package dev.fastframe.storage;

/** Rejects absolute paths, traversal, backslashes, controls and platform-specific path syntax. */
public final class StorageKeys {
    private StorageKeys() {}

    public static String validate(String key) {
        if (key == null || key.isEmpty() || key.length() > 512 || key.startsWith("/") || key.endsWith("/")) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
                    !segment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("Invalid storage key segment");
            }
        }
        return key;
    }
}

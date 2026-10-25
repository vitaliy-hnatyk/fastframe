import dev.fastframe.storage.LocalDiskStorage;
import dev.fastframe.storage.Storage;
import dev.fastframe.httpclient.AsyncHttpClient;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;

public class StorageSmoke {
  public static void main(String[] args) throws Exception {
    var path = Files.createTempDirectory("fastframe-storage-smoke-");
    try {
      Storage store = new LocalDiskStorage(path, 4096);
      byte[] input = new byte[]{0, 1, -1, 33};
      store.put("nested/file.bin", input);
      if (!java.util.Arrays.equals(input, store.get("nested/file.bin").orElseThrow())) throw new AssertionError("binary payload mismatch");
      if (!store.delete("nested/file.bin") || store.exists("nested/file.bin")) throw new AssertionError("delete failed");
      try { store.put("../traversal", input); throw new AssertionError("accepted traversal"); }
      catch (IllegalArgumentException expected) { }
      try (var http = new AsyncHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 1024)) {
        try { http.get(URI.create("file:///etc/passwd")); throw new AssertionError("accepted file URL"); }
        catch (IllegalArgumentException expected) { }
      }
      System.out.println("FastFrame v0.14 Storage + HTTP client smoke PASS");
    } finally {
      try (var files = Files.walk(path)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception e) { throw new RuntimeException(e); } }); }
    }
  }
}

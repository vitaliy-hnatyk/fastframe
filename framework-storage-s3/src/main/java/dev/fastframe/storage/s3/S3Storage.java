package dev.fastframe.storage.s3;

import dev.fastframe.storage.Storage;
import dev.fastframe.storage.StorageKeys;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Optional blocking AWS SDK v2 adapter. The caller owns the S3Client lifecycle. */
public final class S3Storage implements Storage {
    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final long maxFileBytes;

    public S3Storage(S3Client client, String bucket, String prefix, long maxFileBytes) {
        this.client = Objects.requireNonNull(client, "client");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("Bucket is required");
        if (maxFileBytes < 1 || maxFileBytes > 100L * 1024 * 1024) throw new IllegalArgumentException("Invalid limit");
        this.bucket = bucket;
        this.prefix = prefix == null || prefix.isEmpty() ? "" : StorageKeys.validate(prefix) + "/";
        this.maxFileBytes = maxFileBytes;
    }

    private String objectKey(String key) { return prefix + StorageKeys.validate(key); }
    private HeadObjectRequest head(String key) {
        return HeadObjectRequest.builder().bucket(bucket).key(objectKey(key)).build();
    }
    private static boolean notFound(S3Exception ex) { return ex.statusCode() == 404; }

    @Override public void put(String key, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        if (data.length > maxFileBytes) throw new IOException("File exceeds size limit");
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey(key)).build(), RequestBody.fromBytes(data));
    }

    @Override public Optional<byte[]> get(String key) throws IOException {
        String object = objectKey(key);
        try {
            long size = client.headObject(head(key)).contentLength();
            if (size > maxFileBytes) throw new IOException("File exceeds size limit");
            try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(object).build())) {
                byte[] data = input.readNBytes(Math.toIntExact(maxFileBytes + 1));
                if (data.length > maxFileBytes) throw new IOException("File exceeds size limit");
                return Optional.of(data);
            }
        } catch (S3Exception ex) { if (notFound(ex)) return Optional.empty(); throw ex; }
    }

    @Override public boolean exists(String key) {
        try { client.headObject(head(key)); return true; }
        catch (S3Exception ex) { if (notFound(ex)) return false; throw ex; }
    }

    @Override public boolean delete(String key) {
        boolean existed = exists(key);
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
        return existed;
    }
}

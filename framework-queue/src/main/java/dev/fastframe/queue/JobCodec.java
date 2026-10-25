package dev.fastframe.queue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Portable versioned encoding; no unsafe Java deserialization. */
public final class JobCodec {
    private JobCodec() {}
    public static String encode(JobMessage message) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeUTF(message.id().toString());
            out.writeUTF(message.type());
            // writeUTF is capped at 65k bytes; payload uses explicit UTF-8 bytes.
            byte[] payload = message.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (payload.length > 1024 * 1024) throw new IllegalArgumentException("Payload exceeds 1 MiB");
            out.writeInt(payload.length);
            out.write(payload);
            out.writeInt(message.maxAttempts());
            out.writeLong(message.createdAtMillis());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException unexpected) { throw new IllegalStateException(unexpected); }
    }
    public static JobMessage decode(String encoded) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (raw.length > 1024 * 1024 + 256) throw new IllegalArgumentException("Message too large");
            var in = new DataInputStream(new ByteArrayInputStream(raw));
            if (in.readUnsignedByte() != 1) throw new IllegalArgumentException("Unknown job encoding");
            UUID id = UUID.fromString(in.readUTF());
            String type = in.readUTF();
            int length = in.readInt();
            if (length < 0 || length > 1024 * 1024) throw new IllegalArgumentException("Invalid payload length");
            byte[] bytes = in.readNBytes(length);
            if (bytes.length != length) throw new IllegalArgumentException("Truncated job payload");
            String payload = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            int maxAttempts = in.readInt();
            long createdAt = in.readLong();
            if (in.read() != -1) throw new IllegalArgumentException("Unexpected trailing data");
            return new JobMessage(id, type, payload, maxAttempts, createdAt);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid job payload", invalid);
        }
    }
    public static String encodeFailure(FailedJob failure) {
        return encode(failure.message()) + ":" + failure.attempts() + ":" +
                failure.errorType() + ":" + failure.failedAt().toEpochMilli();
    }
    public static FailedJob decodeFailure(String line) {
        String[] parts = line.split(":", 4);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid failure record");
        return new FailedJob(decode(parts[0]), Integer.parseInt(parts[1]), parts[2],
                Instant.ofEpochMilli(Long.parseLong(parts[3])));
    }
}

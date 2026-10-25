package dev.fastframe.queue.redis;

import dev.fastframe.queue.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable-ish Redis queue: atomic Lua leases, delayed jobs, fencing and crash redelivery.
 * At-least-once, not exactly-once; Redis durability depends on Redis persistence settings.
 * Synchronous Lettuce commands BLOCK, so use from worker threads, never Netty event loops.
 */
public final class RedisQueueStore implements QueueStore {
    private static final String CLAIM = """
        local expired=redis.call('ZRANGEBYSCORE',KEYS[2],'-inf',ARGV[1],'LIMIT',0,100)
        for _,id in ipairs(expired) do
          redis.call('ZREM',KEYS[2],id)
          redis.call('HDEL',KEYS[4],id)
          redis.call('ZADD',KEYS[1],ARGV[1],id)
        end
        local due=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',ARGV[1],'LIMIT',0,1)
        if #due == 0 then return nil end
        local id=due[1]
        redis.call('ZREM',KEYS[1],id)
        redis.call('ZADD',KEYS[2],ARGV[2],id)
        redis.call('HSET',KEYS[4],id,ARGV[3])
        local count=redis.call('HINCRBY',KEYS[5],id,1)
        local payload=redis.call('HGET',KEYS[3],id)
        return {id,payload,tostring(count)}
        """;
    private static final String ENQUEUE = """
        if redis.call('HEXISTS',KEYS[2],ARGV[1]) == 1 then return 0 end
        redis.call('HSET',KEYS[2],ARGV[1],ARGV[2])
        redis.call('ZADD',KEYS[1],ARGV[3],ARGV[1])
        return 1
        """;
    private static final String ACK = """
        if redis.call('HGET',KEYS[3],ARGV[1]) ~= ARGV[2] then return 0 end
        redis.call('ZREM',KEYS[1],ARGV[1])
        redis.call('HDEL',KEYS[2],ARGV[1])
        redis.call('HDEL',KEYS[3],ARGV[1])
        redis.call('HDEL',KEYS[4],ARGV[1])
        return 1
        """;
    private static final String RETRY = """
        if redis.call('HGET',KEYS[3],ARGV[1]) ~= ARGV[2] then return 0 end
        redis.call('ZREM',KEYS[1],ARGV[1])
        redis.call('HDEL',KEYS[3],ARGV[1])
        redis.call('ZADD',KEYS[2],ARGV[3],ARGV[1])
        return 1
        """;
    private static final String FAIL = """
        if redis.call('HGET',KEYS[3],ARGV[1]) ~= ARGV[2] then return 0 end
        redis.call('ZREM',KEYS[1],ARGV[1])
        redis.call('HDEL',KEYS[2],ARGV[1])
        redis.call('HDEL',KEYS[3],ARGV[1])
        redis.call('HDEL',KEYS[4],ARGV[1])
        redis.call('HSET',KEYS[5],ARGV[1],ARGV[3])
        return 1
        """;

    private final RedisClient client;
    private final StatefulRedisConnection<String,String> connection;
    private final RedisCommands<String,String> redis;
    private final String ready, inFlight, payloads, tokens, attempts, failed;
    public RedisQueueStore(String uri, String prefix) {
        if (!(Objects.requireNonNull(uri).startsWith("redis://") || uri.startsWith("rediss://")))
            throw new IllegalArgumentException("redis:// or rediss:// URI required");
        if (prefix == null || !prefix.matches("[A-Za-z0-9:_-]{1,100}"))
            throw new IllegalArgumentException("Invalid Redis key prefix");
        ready=prefix+":ready"; inFlight=prefix+":working"; payloads=prefix+":payloads";
        tokens=prefix+":tokens"; attempts=prefix+":attempts"; failed=prefix+":failed";
        client=RedisClient.create(uri);
        try { connection=client.connect(); }
        catch(RuntimeException e) { client.shutdown(); throw e; }
        connection.setTimeout(Duration.ofSeconds(5));
        redis=connection.sync();
    }
    private static String id(ReservedJob receipt) { return receipt.message().id().toString(); }
    private boolean success(Long value) { return Long.valueOf(1).equals(value); }
    @Override public void enqueue(JobMessage job, Instant availableAt) {
        Long created=redis.eval(ENQUEUE,ScriptOutputType.INTEGER,
                new String[]{ready,payloads},job.id().toString(),JobCodec.encode(job),Long.toString(availableAt.toEpochMilli()));
        if (!success(created)) throw new IllegalArgumentException("Duplicate job id");
    }
    @Override public Optional<ReservedJob> reserve(Instant now, Duration lease) {
        if (lease.isNegative() || lease.isZero() || lease.toMillis()<1) throw new IllegalArgumentException("Invalid lease");
        String token=UUID.randomUUID().toString();
        List<String> reply=redis.eval(CLAIM,ScriptOutputType.MULTI,
                new String[]{ready,inFlight,payloads,tokens,attempts},
                Long.toString(now.toEpochMilli()),Long.toString(now.plus(lease).toEpochMilli()),token);
        if (reply == null || reply.isEmpty()) return Optional.empty();
        JobMessage job=JobCodec.decode(reply.get(1));
        if (!job.id().toString().equals(reply.get(0))) throw new IllegalStateException("Corrupt Redis queue entry");
        return Optional.of(new ReservedJob(job,Integer.parseInt(reply.get(2)),token));
    }
    @Override public boolean ack(ReservedJob receipt) {
        Long result=redis.eval(ACK,ScriptOutputType.INTEGER,
                new String[]{inFlight,payloads,tokens,attempts},id(receipt),receipt.token());
        return success(result);
    }
    @Override public boolean retry(ReservedJob receipt, Instant availableAt) {
        Long result=redis.eval(RETRY,ScriptOutputType.INTEGER,
                new String[]{inFlight,ready,tokens},id(receipt),receipt.token(),Long.toString(availableAt.toEpochMilli()));
        return success(result);
    }
    @Override public boolean fail(ReservedJob receipt, String errorType, Instant at) {
        FailedJob failure=new FailedJob(receipt.message(),receipt.attempt(),errorType,at);
        Long result=redis.eval(FAIL,ScriptOutputType.INTEGER,
                new String[]{inFlight,payloads,tokens,attempts,failed},id(receipt),receipt.token(),JobCodec.encodeFailure(failure));
        return success(result);
    }
    @Override public List<FailedJob> failedJobs() {
        List<FailedJob> result=new ArrayList<>();
        for (String value: redis.hvals(failed)) result.add(JobCodec.decodeFailure(value));
        return List.copyOf(result);
    }
    @Override public void close() { try { connection.close(); } finally { client.shutdown(); } }
}

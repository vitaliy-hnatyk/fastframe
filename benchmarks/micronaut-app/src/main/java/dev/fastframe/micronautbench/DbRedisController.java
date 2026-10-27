package dev.fastframe.micronautbench;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.Map;

/** Offload JDBC/Redis blocking calls; do not block a Micronaut Netty event loop. */
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/bench")
public final class DbRedisController {
    @Get(uri="/db/select",produces=MediaType.APPLICATION_JSON)
    public Map<String,String> select() { return DbRedisWorkload.get().select(); }
    @Post(uri="/db/insert",produces=MediaType.APPLICATION_JSON)
    public Map<String,Boolean> insert() { return DbRedisWorkload.get().insert(); }
    @Post(uri="/db/update",produces=MediaType.APPLICATION_JSON)
    public Map<String,Boolean> update() { return DbRedisWorkload.get().update(); }
    @Post(uri="/db/transaction",produces=MediaType.APPLICATION_JSON)
    public Map<String,Boolean> transaction() { return DbRedisWorkload.get().transaction(); }
    @Get(uri="/cache/get",produces=MediaType.APPLICATION_JSON)
    public Map<String,String> cacheGet() { return DbRedisWorkload.get().cacheGet(); }
    @Post(uri="/cache/set",produces=MediaType.APPLICATION_JSON)
    public Map<String,Boolean> cacheSet() { return DbRedisWorkload.get().cacheSet(); }
}

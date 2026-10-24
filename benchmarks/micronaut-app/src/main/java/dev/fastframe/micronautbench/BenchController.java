package dev.fastframe.micronautbench;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;

import java.util.Map;

@Controller("/bench")
public final class BenchController {
    @Get(uri="/plain", produces=MediaType.TEXT_PLAIN)
    public String plain() { return "ok"; }

    @Get(uri="/json", produces=MediaType.APPLICATION_JSON)
    public Map<String, Object> json() { return Map.of("message", "ok", "value", 42); }

    @Get(uri="/route/{id}", produces=MediaType.TEXT_PLAIN)
    public String route(@PathVariable("id") String id) { return id; }

    @Post(uri="/echo", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public Map<String, String> echo(@Body EchoRequest request) {
        return Map.of("message", request.message());
    }
}

package dev.fastframe.benchmark;

import dev.fastframe.core.annotation.Body;
import dev.fastframe.core.annotation.Controller;
import dev.fastframe.core.annotation.Get;
import dev.fastframe.core.annotation.PathParam;
import dev.fastframe.core.annotation.Post;
import dev.fastframe.core.http.Response;

import java.util.Map;

@Controller("/bench")
public final class BenchController {
    @Get("/plain")
    public Response plain() { return Response.text("ok"); }

    @Get("/json")
    public Response json() { return Response.jsonValue(Map.of("message", "ok", "value", 42)); }

    @Get("/route/{id}")
    public Response route(@PathParam("id") String id) { return Response.text(id); }

    @Get("/json-size/{size}")
    public Response jsonSize(@PathParam("size") String size) {
        Map<String, String> data = JsonSizeData.get(size);
        return data == null ? Response.text("Not Found").status(404) : Response.jsonValue(data);
    }

    @Post("/echo")
    public Response echo(@Body EchoRequest request) {
        return Response.jsonValue(Map.of("message", request.message()));
    }
}

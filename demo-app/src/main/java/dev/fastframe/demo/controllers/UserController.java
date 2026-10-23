package dev.fastframe.demo.controllers;

import dev.fastframe.core.http.Json;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;

public final class UserController {
    public Response show(Request request) {
        return Response.json("{\"id\":" + Json.quote(request.routeParam("id")) + "}");
    }

    public Response store(Request request) {
        // This is a transport example, not a database-backed implementation.
        return Response.json("{\"message\":\"created (demo only)\"}").status(201);
    }
}

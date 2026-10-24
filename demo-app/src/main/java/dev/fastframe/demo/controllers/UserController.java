package dev.fastframe.demo.controllers;

import dev.fastframe.core.annotation.Body;
import dev.fastframe.core.annotation.Controller;
import dev.fastframe.core.annotation.Get;
import dev.fastframe.core.annotation.Post;
import dev.fastframe.core.annotation.Inject;
import dev.fastframe.core.annotation.PathParam;
import dev.fastframe.core.annotation.QueryParam;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.demo.dto.CreateUserRequest;
import dev.fastframe.demo.services.UserService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Controller("/api/users")
public final class UserController {
    private final UserService users;

    @Inject
    public UserController(UserService users) { this.users = users; }

    @Get("/{id}")
    public Response show(@PathParam("id") long id) {
        return Response.jsonValue(Map.of("id", id, "name", users.nameFor(id)));
    }

    @Post
    public Response store(@Body CreateUserRequest dto) {
        return Response.jsonValue(Map.of("name", dto.name(), "email", dto.email(),
                "age", dto.age(), "created", true)).status(201);
    }

    @Get("/page")
    public Response page(@QueryParam("number") int number) {
        return Response.jsonValue(Map.of("page", number));
    }

    @Get("/async/{id}")
    public CompletionStage<Response> asyncShow(@PathParam("id") long id) {
        return CompletableFuture.completedFuture(show(id));
    }
}

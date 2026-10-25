package dev.fastframe.demo.controllers;
import dev.fastframe.core.annotation.*;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.auth.Authorization;
import java.util.Map;
/** Protected routes are rejected by generated guards unless identity is authenticated. */
@Controller("/api/secure")
@Authenticated
public final class SecureController {
    @Get("/me")
    public Response me(Request request) {
        String subject = Authorization.identity(request).orElseThrow().subject();
        return Response.jsonValue(Map.of("subject", subject)).header("Cache-Control", "no-store");
    }
    @Get("/users")
    @Authorize("users.read")
    public Response users() { return Response.jsonValue(Map.of("message", "read permission granted")); }
    @Get("/admin")
    @Authorize("role:admin")
    public Response admin() { return Response.jsonValue(Map.of("message", "admin role granted")); }
}

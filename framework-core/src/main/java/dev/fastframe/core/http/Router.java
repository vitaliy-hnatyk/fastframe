package dev.fastframe.core.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Register routes and middleware before starting the server, then treat as read-only. */
public final class Router {
    private final Map<String, Node> methodRoots = new LinkedHashMap<>();
    private final List<Middleware> globalMiddleware = new ArrayList<>();
    private boolean routesRegistered;

    /** Global middleware must be declared before any routes. */
    public Router use(Middleware middleware) {
        if (routesRegistered) throw new IllegalStateException("Register global middleware before routes");
        globalMiddleware.add(Objects.requireNonNull(middleware));
        return this;
    }

    public Router get(String path, Handler handler) { return add("GET", path, handler); }
    public Router post(String path, Handler handler) { return add("POST", path, handler); }
    public Router put(String path, Handler handler) { return add("PUT", path, handler); }
    public Router delete(String path, Handler handler) { return add("DELETE", path, handler); }
    public Router getAsync(String path, AsyncHandler handler) { return addAsync("GET", path, handler); }
    public Router postAsync(String path, AsyncHandler handler) { return addAsync("POST", path, handler); }
    public Router putAsync(String path, AsyncHandler handler) { return addAsync("PUT", path, handler); }
    public Router deleteAsync(String path, AsyncHandler handler) { return addAsync("DELETE", path, handler); }

    public Router add(String method, String path, Handler handler) {
        Objects.requireNonNull(handler, "handler");
        return addAsync(method, path, request -> CompletableFuture.completedFuture(handler.handle(request)));
    }

    public Router addAsync(String method, String path, AsyncHandler handler) {
        return register(method, path, handler, List.of());
    }

    private Router register(String method, String path, AsyncHandler handler, List<Middleware> groupMiddleware) {
        Objects.requireNonNull(handler, "handler");
        String normalizedMethod = Objects.requireNonNull(method).toUpperCase(Locale.ROOT);
        if (!normalizedMethod.matches("[A-Z]+")) throw new IllegalArgumentException("Invalid HTTP method");
        // Validate path before modifying the trie.
        String[] segments = routeSegments(path);
        Node node = methodRoots.computeIfAbsent(normalizedMethod, k -> new Node());
        for (String segment : segments) {
            if (segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2) {
                String paramName = segment.substring(1, segment.length() - 1);
                if (node.parameterChild == null) {
                    node.parameterChild = new Node();
                    node.parameterName = paramName;
                } else if (!node.parameterName.equals(paramName)) {
                    throw new IllegalArgumentException("Ambiguous route parameter: " + path);
                }
                node = node.parameterChild;
            } else {
                node = node.children.computeIfAbsent(segment, k -> new Node());
            }
        }
        if (node.handler != null) throw new IllegalArgumentException("Duplicate route: " + method + " " + path);
        // Middleware chain is compiled once at registration, not per request.
        List<Middleware> chain = new ArrayList<>(globalMiddleware);
        chain.addAll(groupMiddleware);
        AsyncHandler composed = handler;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware middleware = chain.get(i);
            Middleware.Next next = composed::handle;
            composed = request -> middleware.handle(request, next);
        }
        node.handler = composed;
        routesRegistered = true;
        return this;
    }

    public Router group(String prefix, Consumer<Group> routes) {
        Objects.requireNonNull(routes).accept(new Group(this, normalize(prefix)));
        return this;
    }

    /** Never blocks; safe to invoke from a Netty event loop. */
    public CompletionStage<Response> dispatchAsync(Request request) {
        Objects.requireNonNull(request, "request");
        String method = request.method().toUpperCase(Locale.ROOT);
        Match result = match(methodRoots.get(method), request.path());
        if (result == null && method.equals("HEAD")) {
            result = match(methodRoots.get("GET"), request.path());
        }
        if (result != null) {
            request.setRouteParameters(result.parameters());
            try {
                return Objects.requireNonNull(result.handler().handle(request), "Controller returned a null stage");
            } catch (Throwable ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (Map.Entry<String, Node> entry : methodRoots.entrySet()) {
            if (match(entry.getValue(), request.path()) != null) allowed.add(entry.getKey());
        }
        if (!allowed.isEmpty()) {
            if (allowed.contains("GET")) allowed.add("HEAD");
            allowed.add("OPTIONS");
            String allow = String.join(", ", allowed);
            if (method.equals("OPTIONS")) {
                return CompletableFuture.completedFuture(Response.text("").status(204).header("Allow", allow));
            }
            return CompletableFuture.completedFuture(Response.text("Method Not Allowed")
                    .status(405).header("Allow", allow));
        }
        return CompletableFuture.completedFuture(Response.text("Not Found").status(404));
    }

    /** Blocking compatibility/testing convenience. NEVER call on a Netty event loop. */
    @Deprecated
    public Response dispatch(Request request) throws Exception {
        try {
            return dispatchAsync(request).toCompletableFuture().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) throw e;
            if (cause instanceof Error e) throw e;
            throw ex;
        }
    }

    private static Match match(Node root, String rawPath) {
        if (root == null || rawPath == null || !rawPath.startsWith("/")) return null;
        return traverse(root, normalize(rawPath), 1, null);
    }

    private static Match traverse(Node node, String path, int position, Map<String, String> parameters) {
        if (position >= path.length()) {
            if (node.handler == null) return null;
            return new Match(node.handler, parameters == null ? Map.of() : Map.copyOf(parameters));
        }
        int slash = path.indexOf('/', position);
        if (slash == -1) slash = path.length();
        String segment = path.substring(position, slash);
        int nextPosition = slash + 1;
        Node staticChild = node.children.get(segment);
        if (staticChild != null) {
            Match found = traverse(staticChild, path, nextPosition, parameters);
            if (found != null) return found;
        }
        if (node.parameterChild != null && !segment.isEmpty()) {
            Map<String, String> nextParameters = parameters == null ? new HashMap<>() : new HashMap<>(parameters);
            nextParameters.put(node.parameterName, segment);
            return traverse(node.parameterChild, path, nextPosition, nextParameters);
        }
        return null;
    }

    private static String[] routeSegments(String path) {
        String value = normalize(path);
        if (value.equals("/")) return new String[0];
        String[] segments = value.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.contains("{") != segment.contains("}")) {
                throw new IllegalArgumentException("Invalid route pattern: " + path);
            }
        }
        return segments;
    }

    private static String normalize(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) throw new IllegalArgumentException("Route must start with /");
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }

    private static final class Node {
        private final Map<String, Node> children = new HashMap<>();
        private Node parameterChild;
        private String parameterName;
        private AsyncHandler handler;
    }

    private record Match(AsyncHandler handler, Map<String, String> parameters) { }

    /** Laravel-style grouped routes and middleware. */
    public static final class Group {
        private final Router router;
        private final String prefix;
        private final List<Middleware> middleware = new ArrayList<>();
        private boolean routesRegistered;

        private Group(Router router, String prefix) { this.router = router; this.prefix = prefix; }

        public Group middleware(Middleware value) {
            if (routesRegistered) throw new IllegalStateException("Register group middleware before group routes");
            middleware.add(Objects.requireNonNull(value));
            return this;
        }

        public Group get(String path, Handler handler) { return add("GET", path, handler); }
        public Group post(String path, Handler handler) { return add("POST", path, handler); }
        public Group put(String path, Handler handler) { return add("PUT", path, handler); }
        public Group delete(String path, Handler handler) { return add("DELETE", path, handler); }
        public Group getAsync(String path, AsyncHandler handler) { return addAsync("GET", path, handler); }
        public Group postAsync(String path, AsyncHandler handler) { return addAsync("POST", path, handler); }
        public Group putAsync(String path, AsyncHandler handler) { return addAsync("PUT", path, handler); }
        public Group deleteAsync(String path, AsyncHandler handler) { return addAsync("DELETE", path, handler); }
        public Group add(String method, String path, Handler handler) {
            Objects.requireNonNull(handler);
            return addAsync(method, path, request -> CompletableFuture.completedFuture(handler.handle(request)));
        }
        public Group addAsync(String method, String path, AsyncHandler handler) {
            router.register(method, fullPath(path), handler, middleware);
            routesRegistered = true;
            return this;
        }
        private String fullPath(String path) {
            return prefix.equals("/") ? normalize(path) : prefix + (path.equals("/") ? "" : normalize(path));
        }
    }
}

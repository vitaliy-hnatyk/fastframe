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
    // Exact, parameter-free routes are pre-resolved at registration time.
    private final Map<String, Map<String, Match>> exactRoutes = new HashMap<>();
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
        return register(method, path, Objects.requireNonNull(handler, "handler"), null, List.of());
    }

    public Router addAsync(String method, String path, AsyncHandler handler) {
        return register(method, path, null, Objects.requireNonNull(handler, "handler"), List.of());
    }

    private Router register(String method, String path, Handler directHandler,
                            AsyncHandler asyncHandler, List<Middleware> groupMiddleware) {
        String normalizedMethod = Objects.requireNonNull(method).toUpperCase(Locale.ROOT);
        if (!normalizedMethod.matches("[A-Z]+")) throw new IllegalArgumentException("Invalid HTTP method");
        // Validate path before modifying the trie.
        String[] segments = routeSegments(path);
        Node node = methodRoots.computeIfAbsent(normalizedMethod, k -> new Node());
        boolean exact = true;
        for (String segment : segments) {
            if (segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2) {
                exact = false;
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
        // No middleware: retain the synchronous handler, avoiding a completed future
        // allocation and callback registration for each request on Netty's hot path.
        boolean hasMiddleware = !globalMiddleware.isEmpty() || !groupMiddleware.isEmpty();
        if (!hasMiddleware && directHandler != null) node.directHandler = directHandler;
        AsyncHandler composed = asyncHandler != null ? asyncHandler :
                request -> CompletableFuture.completedFuture(directHandler.handle(request));
        if (hasMiddleware) {
            List<Middleware> chain = new ArrayList<>(globalMiddleware);
            chain.addAll(groupMiddleware);
            for (int i = chain.size() - 1; i >= 0; i--) {
                Middleware middleware = chain.get(i);
                Middleware.Next next = composed::handle;
                composed = request -> middleware.handle(request, next);
            }
        }
        node.handler = composed;
        node.cachedMatch = new Match(node.directHandler, node.handler, Map.of());
        if (exact) {
            exactRoutes.computeIfAbsent(normalizedMethod, k -> new HashMap<>())
                    .put(normalize(path), node.cachedMatch);
        }
        routesRegistered = true;
        return this;
    }

    public Router group(String prefix, Consumer<Group> routes) {
        Objects.requireNonNull(routes).accept(new Group(this, normalize(prefix)));
        return this;
    }

    /**
     * Non-blocking dispatch for transport adapters. Returns either a Response (when
     * the route is synchronous with no middleware) or a CompletionStage<Response>.
     * The caller must handle both forms. NEVER block on a returned stage.
     * Routes must be registered before concurrent dispatch begins.
     */
    public Object dispatchDirect(Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        String method = request.method();
        if (!isUppercase(method)) method = method.toUpperCase(Locale.ROOT);
        Match result = match(method, request.path());
        if (result == null && method.equals("HEAD")) {
            result = match("GET", request.path());
        }
        if (result != null) {
            request.setRouteParameters(result.parameters());
            if (result.directHandler() != null) {
                return Objects.requireNonNull(result.directHandler().handle(request),
                        "Controller returned null response");
            }
            return Objects.requireNonNull(result.handler().handle(request),
                    "Controller returned a null stage");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String verb : methodRoots.keySet()) {
            if (match(verb, request.path()) != null) allowed.add(verb);
        }
        if (!allowed.isEmpty()) {
            if (allowed.contains("GET")) allowed.add("HEAD");
            allowed.add("OPTIONS");
            String allow = String.join(", ", allowed);
            if (method.equals("OPTIONS")) {
                return Response.text("").status(204).header("Allow", allow);
            }
            return Response.text("Method Not Allowed").status(405).header("Allow", allow);
        }
        return Response.text("Not Found").status(404);
    }

    /** Compatibility API for async callers and middleware. Never blocks. */
    @SuppressWarnings("unchecked")
    public CompletionStage<Response> dispatchAsync(Request request) {
        try {
            Object result = dispatchDirect(request);
            if (result instanceof Response response) return CompletableFuture.completedFuture(response);
            return (CompletionStage<Response>) result;
        } catch (Throwable ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private Match match(String method, String path) {
        if (path == null || !path.startsWith("/")) return null;
        String normalized = normalize(path);
        Map<String, Match> direct = exactRoutes.get(method);
        Match found = direct == null ? null : direct.get(normalized);
        return found != null ? found : match(methodRoots.get(method), normalized);
    }

    private static boolean isUppercase(String method) {
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c >= 'a' && c <= 'z') return false;
        }
        return true;
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
        return traverse(root, rawPath, 1, null);
    }

    private static Match traverse(Node node, String path, int position, Map<String, String> parameters) {
        if (position >= path.length()) {
            if (node.handler == null) return null;
            return parameters == null ? node.cachedMatch :
                    new Match(node.directHandler, node.handler, Map.copyOf(parameters));
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
        private Handler directHandler;
        private Match cachedMatch;
    }

    private record Match(Handler directHandler, AsyncHandler handler, Map<String, String> parameters) { }

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
            router.register(method, fullPath(path), Objects.requireNonNull(handler), null, middleware);
            routesRegistered = true;
            return this;
        }
        public Group addAsync(String method, String path, AsyncHandler handler) {
            router.register(method, fullPath(path), null, Objects.requireNonNull(handler), middleware);
            routesRegistered = true;
            return this;
        }
        private String fullPath(String path) {
            return prefix.equals("/") ? normalize(path) : prefix + (path.equals("/") ? "" : normalize(path));
        }
    }
}

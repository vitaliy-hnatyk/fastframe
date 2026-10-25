import dev.fastframe.cache.*;
import dev.fastframe.core.http.*;
import dev.fastframe.demo.routes.CacheRoutes;

public class CacheHttpSmoke {
    public static void main(String[] args) {
        Router router = new Router();
        CacheRoutes.register(router, new Cache(new InMemoryCacheStore(16)));
        Request req = new Request("GET", "/api/cache/demo", java.util.Map.of(), java.util.Map.of(), "");
        Response r;
        try { r = (Response) router.dispatchDirect(req); }
        catch (Exception ex) { throw new RuntimeException(ex); }
        if (r.status() != 200 || !new String(r.body(), java.nio.charset.StandardCharsets.UTF_8).contains("Hello from FastFrame Cache"))
            throw new AssertionError("Cache demo route failed");
        System.out.println("Cache demo routing PASS");
    }
}

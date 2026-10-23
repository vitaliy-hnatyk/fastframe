import dev.fastframe.core.http.*;
import dev.fastframe.demo.routes.ApiRoutes;
import java.util.Map;
import java.util.concurrent.*;
public class FFSmoke {
  static Request request(String m, String p, Map<String,String> h) { return new Request(m,p,Map.of(),h,""); }
  static void ensure(boolean v, String s) { if (!v) throw new AssertionError(s); }
  public static void main(String[] args) throws Exception {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(5));
    try {
      Router router=new Router(); ApiRoutes.register(router,pool);
      Response hello=router.dispatchAsync(request("GET","/api/users/42",Map.of())).toCompletableFuture().join();
      ensure(hello.status()==200,"get route");
      ensure(hello.headers().get("X-Framework").equals("FastFrame"),"middleware header");
      Response blocked=router.dispatchAsync(request("GET","/api/users/42",Map.of("x-demo-block","true"))).toCompletableFuture().join();
      ensure(blocked.status()==403,"group middleware");
      Response async=router.dispatchAsync(request("GET","/api/async-demo",Map.of())).toCompletableFuture().join();
      ensure(async.status()==200,"async handler");
      Response head=router.dispatchAsync(request("HEAD","/api/health",Map.of())).toCompletableFuture().join();
      ensure(head.status()==200,"head");
      Response options=router.dispatchAsync(request("OPTIONS","/api/health",Map.of())).toCompletableFuture().join();
      ensure(options.status()==204,"options");
      Response notFound=router.dispatchAsync(request("GET","/none",Map.of())).toCompletableFuture().join();
      ensure(notFound.status()==404,"notFound");
      CompletableFuture<Response> pending = new CompletableFuture<>();
      Router asyncRouter = new Router().getAsync("/wait",req -> pending);
      var result = asyncRouter.dispatchAsync(request("GET","/wait",Map.of()));
      ensure(!result.toCompletableFuture().isDone(),"nonblocking");
      pending.complete(Response.text("done"));
      ensure(result.toCompletableFuture().join().status()==200,"resolve");
      System.out.println("SMOKE PASS: route, middleware, async, HEAD, OPTIONS, 404");
    } finally {pool.shutdownNow();}
  }
}

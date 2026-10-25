import dev.fastframe.observability.*;
import dev.fastframe.core.http.*;
import java.util.Map;

public class ObservabilitySmoke {
  public static void main(String[] a) throws Exception {
    var m = new HttpMetrics();
    m.started(); m.completed(200, 250_000L);
    m.started(); m.aborted();
    if (m.activeRequests()!=0 || m.completedRequests()!=1 || m.abortedRequests()!=1)
       throw new AssertionError("counts");
    if (!m.prometheus().contains("fastframe_http_handler_duration_seconds_count 1"))
       throw new AssertionError("export");
    var r = new Router(); var ready = new Readiness();
    OperationsRoutes.register(r,ready,m,true);
    var req = new Request("GET","/_health/ready", Map.of(),Map.of(),"");
    if (((Response)r.dispatchDirect(req)).status()!=503) throw new AssertionError("not ready");
    ready.ready();
    if (((Response)r.dispatchDirect(req)).status()!=200) throw new AssertionError("ready");
    if (((Response)r.dispatchDirect(new Request("GET","/_metrics", Map.of(),Map.of(),""))).status()!=200)
       throw new AssertionError("metrics");
    System.out.println("Observability smoke PASS");
  }
}

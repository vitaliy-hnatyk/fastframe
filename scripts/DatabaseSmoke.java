import dev.fastframe.database.*;
import java.util.List;
import java.util.Map;

public class DatabaseSmoke {
    public static void main(String[] args) {
        // SQL is generated without database access; no external dependencies required.
        var db = new Database(new DriverManagerDataSource("jdbc:unused", null, null));
        var query = db.table("public.users").select("id", "name")
                .where("active", true).whereIn("id", List.of(7,8))
                .orderBy("id", Query.Direction.DESC).limit(10).offset(5);
        if (!query.toSql().equals("SELECT \"id\", \"name\" FROM \"public\".\"users\" WHERE \"active\" = ? AND \"id\" IN (?, ?) ORDER BY \"id\" DESC LIMIT ? OFFSET ?"))
            throw new AssertionError(query.toSql());
        if (!query.bindings().equals(List.of(true, 7, 8, 10, 5))) throw new AssertionError(query.bindings());
        try { db.table("users DROP TABLE"); throw new AssertionError("bad identifiers accepted"); }
        catch (IllegalArgumentException expected) { }
        try { db.table("users").delete(); throw new AssertionError("unfiltered deletes accepted"); }
        catch (IllegalStateException expected) { }
        System.out.println("DATABASE SQL SMOKE PASS");
    }
}

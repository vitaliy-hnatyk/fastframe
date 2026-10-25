import java.util.Map;
public class OrmSmokeMain {
    public static void main(String[] args) {
        var mapper = new OrmSmokeUserFastFrameMapper();
        var user = new OrmSmokeUser(2L,"Ada",30);
        if (!mapper.table().equals("smoke_users") || !mapper.idColumn().equals("id")) throw new AssertionError();
        if (!mapper.fromRow(Map.of("id", 2, "user_name", "Ada", "age", 30)).equals(user)) throw new AssertionError();
        if (!mapper.toColumns(user).get("user_name").equals("Ada")) throw new AssertionError();
        System.out.println("ORM GENERATION SMOKE PASS");
    }
}

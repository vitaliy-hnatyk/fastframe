import dev.fastframe.orm.annotation.*;
@Table("smoke_users")
public record OrmSmokeUser(@Id Long id, @Column("user_name") String name, int age) {}

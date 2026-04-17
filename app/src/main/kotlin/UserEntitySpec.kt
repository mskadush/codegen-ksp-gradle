import com.example.annotations.EntityField
import com.example.annotations.EntitySpec
import com.example.annotations.NullableOverride

@EntitySpec(for_ = User::class, table = "users", schema = "public")
@EntityField(property = "name", column = "user_name")            // → @Column(name = "user_name")
@EntityField(property = "email", exclude = true)                 // → field absent from UserEntity
@EntityField(property = "id", nullable = NullableOverride.YES)   // → Long?
object UserEntitySpec

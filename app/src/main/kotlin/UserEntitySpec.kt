import com.example.annotations.EntityField
import com.example.annotations.EntitySpec
import com.example.annotations.NullableOverride
import com.example.app.UpperCaseTransformer

@EntitySpec(for_ = User::class, table = "users", schema = "public")
@EntityField(property = "name", column = "user_name", transformer = UpperCaseTransformer::class)  // → uppercased via transformer
@EntityField(property = "email", exclude = true)                                                   // → field absent from UserEntity
@EntityField(property = "id", nullable = NullableOverride.YES)                                    // → Long?
object UserEntitySpec

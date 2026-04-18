import com.example.annotations.CreateField
import com.example.annotations.CreateSpec
import com.example.annotations.RequestSpec
import com.example.annotations.Rule

@RequestSpec(for_ = User::class)
@CreateSpec(
    fields = [
        CreateField(property = "name", rules = [Rule.NotBlank::class], minLength = 2),
        CreateField(property = "email", rules = [Rule.Email::class, Rule.NotBlank::class]),
    ]
)
object UserRequestSpec

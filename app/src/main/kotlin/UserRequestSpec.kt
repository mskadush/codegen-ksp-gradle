import com.example.annotations.AnnotationMember
import com.example.annotations.CreateField
import com.example.annotations.CreateSpec
import com.example.annotations.DbAnnotation
import com.example.annotations.RequestSpec
import com.example.annotations.Rule
import com.example.annotations.UpdateField
import com.example.annotations.UpdateSpec

@RequestSpec(for_ = User::class)
@CreateSpec(
    fields = [
        CreateField(
            property = "name",
            rules = [Rule.NotBlank::class],
            minLength = 2,
            annotations = [DbAnnotation(annotation = jakarta.validation.constraints.NotBlank::class)]
        ),
        CreateField(property = "email", rules = [Rule.Email::class, Rule.NotBlank::class]),
    ]
)
@UpdateSpec(
    partial = true,
    fields = [
        UpdateField(property = "name", rules = [Rule.NotBlank::class], minLength = 2),
        UpdateField(property = "email", rules = [Rule.Email::class]),
    ]
)
object UserRequestSpec

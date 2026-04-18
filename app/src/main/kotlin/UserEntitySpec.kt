import com.example.annotations.AnnotationMember
import com.example.annotations.DbAnnotation
import com.example.annotations.EntityField
import com.example.annotations.EntitySpec
import com.example.annotations.NullableOverride
import com.example.app.UpperCaseTransformer

@EntitySpec(
    for_ = User::class,
    table = "users",
    schema = "public",
    annotations = [
        DbAnnotation(
            annotation = jakarta.persistence.Table::class,
            members = [
                AnnotationMember(name = "name", value = "\"users\""),
                AnnotationMember(name = "schema", value = "\"public\"")
            ]
        )
    ]
)
@EntityField(
    property = "name",
    column = "user_name2",
    transformer = UpperCaseTransformer::class,
    annotations = [
        DbAnnotation(
            annotation = jakarta.persistence.Column::class,
            members = [AnnotationMember(name = "name", value = "\"user_name\"")]
        ),
    ]
)
@EntityField(property = "email", exclude = true)
@EntityField(property = "id", nullable = NullableOverride.YES)
object UserEntitySpec

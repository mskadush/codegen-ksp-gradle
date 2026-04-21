import com.example.annotations.CustomAnnotation
import com.example.annotations.FieldBundle
import com.example.annotations.FieldSpec
import com.example.annotations.NullableOverride

@FieldBundle("timestamps")
// Entity: snake_case column names + Jakarta persistence annotations
@FieldSpec(
    for_ = ["Entity"], property = "createdAt", column = "created_at",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"created_at\"", "nullable=false", "updatable=false"]
    )]
)
@FieldSpec(
    for_ = ["Entity"], property = "updatedAt", column = "updated_at",
    nullable = NullableOverride.YES,
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"updated_at\""]
    )]
)
// Requests: exclude audit fields (not user-supplied)
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "updatedAt", exclude = true)
object TimestampsBundle

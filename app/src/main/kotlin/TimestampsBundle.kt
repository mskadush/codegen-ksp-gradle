import za.skadush.codegen.gradle.annotations.CustomAnnotation
import za.skadush.codegen.gradle.annotations.FieldBundle
import za.skadush.codegen.gradle.annotations.FieldOverride
import za.skadush.codegen.gradle.annotations.NullableOverride

@FieldBundle
// Entity: Jakarta persistence annotations
@FieldOverride(
    for_ = ["Entity"], property = "createdAt",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"created_at\"", "nullable=false", "updatable=false"]
    )]
)
@FieldOverride(
    for_ = ["Entity"], property = "updatedAt",
    nullable = NullableOverride.YES,
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"updated_at\""]
    )]
)
// Requests: exclude audit fields (not user-supplied)
@FieldOverride(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
@FieldOverride(for_ = ["CreateRequest", "UpdateRequest"], property = "updatedAt", exclude = true)
object TimestampsBundle

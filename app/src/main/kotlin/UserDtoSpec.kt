import com.example.annotations.AnnotationMember
import com.example.annotations.DbAnnotation
import com.example.annotations.DtoField
import com.example.annotations.DtoSpec

@DtoSpec(
    for_ = User::class,
    suffix = "Response",
    annotations = [
        DbAnnotation(
            annotation = com.fasterxml.jackson.annotation.JsonInclude::class,
            members = [AnnotationMember(name = "value", value = "com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL")]
        )
    ]
)
@DtoField(
    property = "email",
    rename = "emailAddress",
    annotations = [
        DbAnnotation(
            annotation = com.fasterxml.jackson.annotation.JsonProperty::class,
            members = [AnnotationMember(name = "value", value = "\"emailAddress\"")]
        )
    ]
)
@DtoField(property = "id", exclude = true)               // → id absent from UserResponse
@DtoField(property = "name", transformerRef = "upperCase") // → resolved from AppTransformerRegistry
object UserDtoSpec

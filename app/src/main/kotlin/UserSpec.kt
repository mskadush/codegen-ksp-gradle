import com.example.annotations.BundleMergeStrategy
import com.example.annotations.ClassField
import com.example.annotations.ClassSpec
import com.example.annotations.CustomAnnotation
import com.example.annotations.AddField
import com.example.annotations.FieldSpec
import com.example.annotations.NullableOverride
import com.example.app.UpperCaseTransformer

// ---------------------------------------------------------------------------
// UserSpec — drives generation of all User-related output classes
// ---------------------------------------------------------------------------

@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    bundles = [TimestampsBundle::class, UserEntityBundle::class],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE,
    annotations = [
        CustomAnnotation(
            annotation = jakarta.persistence.Table::class,
            members = ["name=\"users\"", "schema=\"public\""]
        )
    ]
)
@ClassSpec(
    for_ = User::class,
    suffix = "Response",
    annotations = [
        CustomAnnotation(
            annotation = com.fasterxml.jackson.annotation.JsonInclude::class,
            members = ["value=com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL"]
        )
    ]
)
@ClassSpec(for_ = User::class, suffix = "CreateRequest", bundles = [TimestampsBundle::class])
@ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true, bundles = [TimestampsBundle::class])

// ---- id: nullable in Entity, excluded everywhere else ----
@FieldSpec(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
@FieldSpec(for_ = ["Response", "CreateRequest", "UpdateRequest"], property = "id", exclude = true)

// ---- email: excluded from Entity; renamed + annotated in Response; validated in requests ----
@FieldSpec(for_ = ["Entity"], property = "email", exclude = true)
@FieldSpec(
    for_ = ["Response"],
    property = "email",
    rename = "emailAddress",
    annotations = [
        CustomAnnotation(
            annotation = com.fasterxml.jackson.annotation.JsonProperty::class,
            members = ["value=\"emailAddress\""]
        )
    ]
)
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)
@FieldSpec(
    for_ = ["UpdateRequest"],
    property = "email",
    validators = [EmailValidator::class]
)

// ---- name: transformer in Entity; transformer in Response; validated in requests ----
@FieldSpec(
    for_ = ["Entity"],
    property = "name",
    transformer = UpperCaseTransformer::class,
    annotations = [
        CustomAnnotation(
            annotation = jakarta.persistence.Column::class,
            members = ["name=\"user_name\""]
        )
    ]
)
@FieldSpec(for_ = ["Response"], property = "name", transformerRef = "upperCase")
@FieldSpec(
    for_ = ["CreateRequest", "UpdateRequest"],
    property = "name",
    validators = [NotBlankValidator::class]
)

// ---- extra fields: version only on Entity (JPA optimistic locking) ----
@AddField(
    for_ = ["Entity"],
    name = "version",
    type = Long::class,
    defaultValue = "0L",
    annotations = [CustomAnnotation(annotation = jakarta.persistence.Version::class)]
)

object UserSpec

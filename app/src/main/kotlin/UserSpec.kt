import com.example.annotations.BundleMergeStrategy
import com.example.annotations.ClassField
import com.example.annotations.ClassSpec
import com.example.annotations.CustomAnnotation
import com.example.annotations.FieldSpec
import com.example.annotations.NullableOverride
import com.example.annotations.Rule
import com.example.app.UpperCaseTransformer

// ---------------------------------------------------------------------------
// UserSpec — drives generation of all User-related output classes
// ---------------------------------------------------------------------------

@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    bundles = ["timestamps", "userEntity"],
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
@ClassSpec(for_ = User::class, suffix = "CreateRequest", bundles = ["timestamps"])
@ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true, bundles = ["timestamps"])

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
    rules = [Rule.Email::class, Rule.NotBlank::class]
)
@FieldSpec(
    for_ = ["UpdateRequest"],
    property = "email",
    rules = [Rule.Email::class]
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
    rules = [Rule.NotBlank::class]
)

// ---- consumer-defined rule example ----
// @RuleExpression("{field}.startsWith(\"ACM-\")")
// annotation class AcmPrefix

object UserSpec

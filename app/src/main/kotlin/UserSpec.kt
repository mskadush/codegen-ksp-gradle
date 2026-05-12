import za.skadush.codegen.gradle.annotations.BundleMergeStrategy
import za.skadush.codegen.gradle.annotations.ClassSpec
import za.skadush.codegen.gradle.annotations.CustomAnnotation
import za.skadush.codegen.gradle.annotations.AddField
import za.skadush.codegen.gradle.annotations.Default
import za.skadush.codegen.gradle.annotations.FieldOverride
import za.skadush.codegen.gradle.annotations.NullableOverride
import za.skadush.codegen.gradle.app.UpperCaseTransformer

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
@ClassSpec(
    for_ = User::class,
    suffix = "CreateRequest",
    bundles = [TimestampsBundle::class],
    validators = [EmailMatchesNameValidator::class],
)
@ClassSpec(
    for_ = User::class,
    suffix = "UpdateRequest",
    partial = true,
    bundles = [TimestampsBundle::class],
    exclude = ["updatedAt"],
)

// ---- id: nullable in Entity, excluded everywhere else ----
@FieldOverride(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
@FieldOverride(for_ = ["Response", "CreateRequest", "UpdateRequest"], property = "id", exclude = true)

// ---- email: excluded from Entity; renamed + annotated in Response; validated in requests ----
@FieldOverride(for_ = ["Entity"], property = "email", exclude = true)
@FieldOverride(
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
@FieldOverride(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)
@FieldOverride(
    for_ = ["UpdateRequest"],
    property = "email",
    validators = [EmailValidator::class]
)

// ---- name: transformer in Entity; transformer in Response; validated in requests ----
@FieldOverride(
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
@FieldOverride(for_ = ["Response"], property = "name", transformer = UpperCaseTransformer::class)
@FieldOverride(
    for_ = ["CreateRequest", "UpdateRequest"],
    property = "name",
    validators = [NotBlankValidator::class]
)
// Explicit-value default: anonymous user on CreateRequest.
@FieldOverride(for_ = ["CreateRequest"], property = "name", default = Default(value = "\"anon\""))

// ---- createdAt: inherit the source default (java.time.Instant.now()) on Response ----
@FieldOverride(for_ = ["Response"], property = "createdAt", default = Default(inherit = true))

// ---- extra fields: version only on Entity (JPA optimistic locking) ----
@AddField(
    for_ = ["Entity"],
    name = "version",
    type = Long::class,
    default = Default(value = "0L"),
    annotations = [CustomAnnotation(annotation = jakarta.persistence.Version::class)]
)

object UserSpec

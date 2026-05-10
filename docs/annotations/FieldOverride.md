# `@FieldOverride`

A **per-output field override**, scoped to one or more [`@ClassSpec`](ClassSpec.md) instances via `for_`. It lets you configure a field differently for each output kind — and share the same validators across multiple outputs by listing several suffixes in `for_`.

`@FieldOverride` wins over [`@FieldSpec`](FieldSpec.md) for the same `property` + suffix.

> **Target**: `CLASS` · **Retention**: `SOURCE` · **Repeatable**: yes

---

## Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `for_` | `Array<String>` | _(required)_ | One or more `@ClassSpec.suffix` values this override applies to. |
| `property` | `String` | _(required)_ | Name of the domain property to configure. |
| `exclude` | `Boolean` | `false` | Omit this field from the named output(s) only. |
| `nullable` | `NullableOverride` | `UNSET` | Nullability override for the named output(s). See [`NullableOverride`](SupportingTypes.md#nullableoverride). |
| `transformer` | `KClass<out FieldTransformer<*, *>>` | `NoOpTransformer::class` | Transformer class for value conversion. |
| `annotations` | `Array<CustomAnnotation>` | `[]` | Annotations forwarded to the generated field in the named output(s). |
| `rename` | `String` | `""` | Alternative field name in the generated class. |
| `validators` | `Array<KClass<out FieldValidator<*>>>` | `[]` | Runtime validators applied to this field. Each must be a singleton `object` implementing [`FieldValidator`](FieldValidator.md). Presence of any validator causes the processor to emit `validate()` and `validateOrThrow()` on the generated class. |

> The processor ignores inapplicable params — `rename` is silently skipped for outputs where it isn't relevant, etc.

---

## Examples

### Scoping to a single output

```kotlin
// Make id nullable only in the entity
@FieldOverride(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)

// Rename email to emailAddress in the response
@FieldOverride(for_ = ["Response"], property = "email", rename = "emailAddress")
```

### Sharing validators across multiple outputs

```kotlin
// Same validators apply to both request types
@FieldOverride(
    for_ = ["CreateRequest", "UpdateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)
```

### Custom annotation on a field

```kotlin
@FieldOverride(
    for_ = ["Entity"],
    property = "createdAt",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"created_at\"", "nullable=false", "updatable=false"]
    )]
)
```

### Excluding from some outputs, validating in others

```kotlin
// id is not user-supplied — exclude from all request types
@FieldOverride(for_ = ["CreateRequest", "UpdateRequest"], property = "id", exclude = true)

// id is always present in entity (auto-generated)
@FieldOverride(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
```

### Transformer on a specific output

```kotlin
// Apply UpperCaseTransformer only on the response, not the entity
@FieldOverride(for_ = ["Response"], property = "name", transformer = UpperCaseTransformer::class)
```

### Full example from `UserSpec`

```kotlin
@ClassSpec(for_ = User::class, suffix = "Entity",
           bundles = [TimestampsBundle::class, UserEntityBundle::class],
           bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE)
@ClassSpec(for_ = User::class, suffix = "Response")
@ClassSpec(for_ = User::class, suffix = "CreateRequest", bundles = [TimestampsBundle::class])
@ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true)

@FieldOverride(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
@FieldOverride(for_ = ["Response", "CreateRequest", "UpdateRequest"], property = "id", exclude = true)

@FieldOverride(for_ = ["Entity"], property = "email", exclude = true)
@FieldOverride(
    for_ = ["Response"],
    property = "email",
    rename = "emailAddress",
    annotations = [CustomAnnotation(
        annotation = com.fasterxml.jackson.annotation.JsonProperty::class,
        members = ["value=\"emailAddress\""]
    )]
)
@FieldOverride(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)
@FieldOverride(for_ = ["UpdateRequest"], property = "email", validators = [EmailValidator::class])

@FieldOverride(
    for_ = ["Entity"],
    property = "name",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"user_name\""]
    )]
)
@FieldOverride(for_ = ["Response"], property = "name", transformer = UpperCaseTransformer::class)
@FieldOverride(
    for_ = ["CreateRequest", "UpdateRequest"],
    property = "name",
    validators = [NotBlankValidator::class]
)
object UserSpec
```

---

## See also

- [`@ClassSpec`](ClassSpec.md) — defines the output classes that `for_` refers to
- [`@FieldSpec`](FieldSpec.md) — default overrides that apply to all outputs (lower priority)
- [`FieldValidator`](FieldValidator.md) — how to define runtime validators
- [`@FieldBundle`](FieldBundle.md) — package reusable `@FieldOverride` sets into bundles

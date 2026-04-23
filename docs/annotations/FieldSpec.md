# `@FieldSpec`

A **per-output field override**, scoped to one or more [`@ClassSpec`](ClassSpec.md) instances via `for_`. It lets you configure a field differently for each output kind — and share the same rules across multiple outputs by listing several suffixes in `for_`.

`@FieldSpec` wins over [`@ClassField`](ClassField.md) for the same `property` + suffix.

> **Target**: `CLASS` · **Retention**: `SOURCE` · **Repeatable**: yes

---

## Properties

### Common (all output kinds)

| Property | Type | Default | Description |
|---|---|---|---|
| `for_` | `Array<String>` | _(required)_ | One or more `@ClassSpec.suffix` values this override applies to. |
| `property` | `String` | _(required)_ | Name of the domain property to configure. |
| `exclude` | `Boolean` | `false` | Omit this field from the named output(s) only. |
| `nullable` | `NullableOverride` | `UNSET` | Nullability override for the named output(s). See [`NullableOverride`](SupportingTypes.md#nullableoverride). |
| `transformer` | `KClass<out FieldTransformer<*, *>>` | `NoOpTransformer::class` | Transformer class for value conversion. |
| `transformerRef` | `String` | `""` | Named transformer from a [`@TransformerRegistry`](TransformerRegistry.md). Wins over `transformer`. |
| `annotations` | `Array<CustomAnnotation>` | `[]` | Annotations forwarded to the generated field in the named output(s). |

### Entity-specific

| Property | Type | Default | Description |
|---|---|---|---|
| `column` | `String` | `""` | Database column name override. |
| `inline` | `Boolean` | `false` | Flatten a nested domain object's fields directly into this entity. |
| `inlinePrefix` | `String` | `""` | Prefix prepended to inlined field names (only meaningful when `inline = true`). |

### DTO-specific

| Property | Type | Default | Description |
|---|---|---|---|
| `rename` | `String` | `""` | Alternative field name in the generated DTO class. |

### Request-specific

| Property | Type | Default | Description |
|---|---|---|---|
| `rules` | `Array<KClass<out Annotation>>` | `[]` | Validation rules to apply. Each rule annotation **must** be annotated with [`@RuleExpression`](RuleExpression.md). Presence of any rule makes the processor emit a request class. |

> The processor ignores inapplicable params — `column` is silently skipped for DTO outputs, `rename` is skipped for entity outputs, etc.

---

## Examples

### Scoping to a single output

```kotlin
// Make id nullable only in the entity
@FieldSpec(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)

// Rename email to emailAddress in the response DTO
@FieldSpec(for_ = ["Response"], property = "email", rename = "emailAddress")
```

### Sharing rules across multiple outputs

```kotlin
// Same email rules apply to both request types
@FieldSpec(
    for_ = ["CreateRequest", "UpdateRequest"],
    property = "email",
    rules = [Rule.Email::class, Rule.NotBlank::class]
)
```

### Entity column mapping + custom annotation

```kotlin
@FieldSpec(
    for_ = ["Entity"],
    property = "createdAt",
    column = "created_at",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"created_at\"", "nullable=false", "updatable=false"]
    )]
)
```

### Excluding from some outputs, validating in others

```kotlin
// id is not user-supplied — exclude from all request types
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "id", exclude = true)

// id is always present in entity (auto-generated)
@FieldSpec(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
```

### Inline nested object (entity)

```kotlin
// Flatten Address fields into UserEntity with an "address_" column prefix
@FieldSpec(
    for_ = ["Entity"],
    property = "address",
    inline = true,
    inlinePrefix = "address_"
)
```

### Named transformer on a specific output

```kotlin
// Apply UpperCaseTransformer only on the response, not the entity
@FieldSpec(for_ = ["Response"], property = "name", transformerRef = "upperCase")
```

### Full example from `UserSpec`

```kotlin
@ClassSpec(for_ = User::class, suffix = "Entity", bundles = ["timestamps"])
@ClassSpec(for_ = User::class, suffix = "Response")
@ClassSpec(for_ = User::class, suffix = "CreateRequest")
@ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true)

@FieldSpec(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
@FieldSpec(for_ = ["Response", "CreateRequest", "UpdateRequest"], property = "id", exclude = true)

@FieldSpec(for_ = ["Entity"], property = "email", exclude = true)
@FieldSpec(
    for_ = ["Response"],
    property = "email",
    rename = "emailAddress",
    annotations = [CustomAnnotation(
        annotation = com.fasterxml.jackson.annotation.JsonProperty::class,
        members = ["value=\"emailAddress\""]
    )]
)
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "email",
    rules = [Rule.Email::class, Rule.NotBlank::class]
)
@FieldSpec(for_ = ["UpdateRequest"], property = "email", rules = [Rule.Email::class])

@FieldSpec(
    for_ = ["Entity"],
    property = "name",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"user_name\""]
    )]
)
@FieldSpec(for_ = ["Response"], property = "name", transformerRef = "upperCase")
@FieldSpec(
    for_ = ["CreateRequest", "UpdateRequest"],
    property = "name",
    rules = [Rule.NotBlank::class]
)
object UserSpec
```

---

## See also

- [`@ClassSpec`](ClassSpec.md) — defines the output classes that `for_` refers to
- [`@ClassField`](ClassField.md) — shared overrides that apply to all outputs (lower priority)
- [`@RuleExpression`](RuleExpression.md) — how to define custom validation rules
- [`@FieldBundle`](FieldBundle.md) — package reusable `@FieldSpec` sets into bundles

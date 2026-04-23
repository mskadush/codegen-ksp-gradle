# `@TransformerRegistry` and `@RegisterTransformer`

## Overview

These two annotations work together to build a **named lookup table of `FieldTransformer` instances** that can be referenced by name from [`@ClassField`](ClassField.md) and [`@FieldSpec`](FieldSpec.md).

Using `transformerRef` (a string name) instead of `transformer` (a `KClass`) decouples spec classes from transformer implementations — useful when transformers live in a different module or when you want one canonical registry for the whole project.

---

## `@TransformerRegistry`

Marks an `object` (or class) as a registry. The processor scans all classes with this annotation to build the transformer lookup table.

> **Target**: `CLASS` · **Retention**: `SOURCE`

No properties.

---

## `@RegisterTransformer`

Registers a **property** within a `@TransformerRegistry` class as a named transformer. The property's type must implement `FieldTransformer<Domain, Target>`.

> **Target**: `PROPERTY` · **Retention**: `SOURCE`

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `name` | `String` | _(required)_ | Unique identifier for this transformer within the registry. Referenced via `transformerRef` in `@ClassField` / `@FieldSpec`. |

---

## `FieldTransformer<Domain, Target>` interface

```kotlin
interface FieldTransformer<Domain, Target> {
    fun toTarget(value: Domain): Target   // domain → output class
    fun toDomain(value: Target): Domain   // output class → domain (used by mappers)
}
```

The processor uses `toTarget` when generating the output class's mapper (`toDomain()`) and `toDomain` when generating the reverse mapper (`to<Suffix>()`).

`NoOpTransformer` is the built-in default — it passes values unchanged (`toTarget(v) = v`, `toDomain(v) = v`).

---

## Example

### Implementing a transformer

```kotlin
class UpperCaseTransformer : FieldTransformer<String, String> {
    override fun toTarget(value: String): String = value.uppercase()
    override fun toDomain(value: String): String = value  // round-trip not guaranteed
}
```

### Registering it

```kotlin
@TransformerRegistry
object AppTransformerRegistry {
    @RegisterTransformer("upperCase")
    val upperCase = UpperCaseTransformer()
}
```

### Referencing by name in a spec

```kotlin
// Apply only on the Response output
@FieldSpec(for_ = ["Response"], property = "name", transformerRef = "upperCase")
object UserSpec
```

### Referencing by class in a spec (alternative)

```kotlin
// Use the transformer class directly — no registry needed
@FieldSpec(for_ = ["Entity"], property = "name", transformer = UpperCaseTransformer::class)
object UserSpec
```

> When both `transformer` and `transformerRef` are set on the same annotation, **`transformerRef` wins**.

---

## Multiple registries

You can have more than one `@TransformerRegistry` in a project. The processor merges all registries into one lookup table. Names must be globally unique across all registries.

```kotlin
@TransformerRegistry
object StringTransformers {
    @RegisterTransformer("upperCase")
    val upperCase = UpperCaseTransformer()

    @RegisterTransformer("lowerCase")
    val lowerCase = LowerCaseTransformer()
}

@TransformerRegistry
object DateTransformers {
    @RegisterTransformer("epochMillis")
    val epochMillis = EpochMillisTransformer()
}
```

---

## Generated mapper

Given `transformerRef = "upperCase"` on the `name` field of `UserResponse`, the processor generates:

```kotlin
// UserResponseMappers.kt
fun User.toResponse(): UserResponse = UserResponse(
    name = AppTransformerRegistry.upperCase.toTarget(name),
    emailAddress = email,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun UserResponse.toDomain(): User = User(
    id = TODO("excluded — supply manually"),
    name = AppTransformerRegistry.upperCase.toDomain(name),
    email = emailAddress,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
```

---

## See also

- [`@ClassField.transformerRef`](ClassField.md) — apply a transformer across all outputs
- [`@FieldSpec.transformerRef`](FieldSpec.md) — apply a transformer to specific outputs

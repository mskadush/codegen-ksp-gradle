# `@AddField`

Injects a **synthetic field** into one or more generated output classes. Unlike [`@FieldSpec`](FieldSpec.md), which configures fields that originate from the domain class, `@AddField` creates a brand-new field with no domain counterpart — useful for persistence metadata (`@Version`, `@CreationTimestamp`) or computed display fields that belong only to a specific output shape.

> **Target**: `CLASS` · **Retention**: `SOURCE` · **Repeatable**: yes

---

## Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `for_` | `Array<String>` | _(required)_ | One or more `@ClassSpec.suffix` values this field is added to. |
| `name` | `String` | _(required)_ | Field name in the generated class. Must not be blank. |
| `type` | `KClass<*>` | _(required)_ | Field type. Must be a simple, non-parameterised class reference (e.g. `Long::class`, `java.time.Instant::class`). Parameterised types such as `List<String>` are not supported. |
| `nullable` | `Boolean` | `false` | When `true`, the generated field type is nullable. |
| `defaultValue` | `String` | `""` | Kotlin expression used as the constructor parameter default (e.g. `"0L"`, `"\"\""`, `"java.time.Instant.now()"`). Required for non-partial, non-nullable fields. |
| `annotations` | `Array<CustomAnnotation>` | `[]` | Annotations forwarded verbatim to the generated field. |

---

## Mapper behaviour

Added fields are emitted as constructor parameters **with a default value**, so the generated `to<Suffix>()` mappers omit them entirely and Kotlin fills the default automatically. No changes to the mapper are required when you add a field via `@AddField`.

---

## Constraints

- `name` must not be blank.
- A non-partial, non-nullable field must have a non-blank `defaultValue` — without one the processor cannot synthesise a value and will report an error.
- `type` supports only simple, non-parameterised `KClass` references.

---

## Examples

### Persistence version field

```kotlin
@AddField(
    for_ = ["Entity"],
    name = "version",
    type = Long::class,
    defaultValue = "0L",
    annotations = [CustomAnnotation(annotation = jakarta.persistence.Version::class)]
)
object UserSpec
```

Generated field in `UserEntity`:

```kotlin
@Version
val version: Long = 0L
```

### Audit timestamps on the entity only

```kotlin
@AddField(
    for_ = ["Entity"],
    name = "createdAt",
    type = java.time.Instant::class,
    defaultValue = "java.time.Instant.now()"
)
@AddField(
    for_ = ["Entity"],
    name = "updatedAt",
    type = java.time.Instant::class,
    nullable = true,
    defaultValue = "null"
)
object OrderSpec
```

### Nullable computed field for a response shape

```kotlin
@AddField(
    for_ = ["Response"],
    name = "displayLabel",
    type = String::class,
    nullable = true,
    defaultValue = "null"
)
object ProductSpec
```

---

## See also

- [`@ClassSpec`](ClassSpec.md) — defines the output classes that `for_` refers to
- [`@FieldSpec`](FieldSpec.md) — overrides fields that originate from the domain class
- [`SupportingTypes`](SupportingTypes.md) — `CustomAnnotation` reference

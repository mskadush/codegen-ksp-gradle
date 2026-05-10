# Supporting Types

Reference for all enums and helper annotation types used throughout the annotation DSL.

---

## `NullableOverride`

Controls whether a generated field's nullability is overridden relative to the domain model.

Used by: [`@FieldSpec.nullable`](FieldSpec.md), [`@FieldOverride.nullable`](FieldOverride.md)

| Value | Behaviour |
|---|---|
| `UNSET` _(default)_ | Inherit nullability from the source domain class. |
| `YES` | Force the generated field to be nullable (`T?`). |
| `NO` | Force the generated field to be non-nullable (`T`). |

### Example

```kotlin
// Domain: val updatedAt: Instant?   (nullable)
// Force non-nullable in a specific output:
@FieldOverride(for_ = ["CreateRequest"], property = "updatedAt", nullable = NullableOverride.NO)

// Domain: val id: Long   (non-nullable)
// Force nullable for the entity (auto-generated PK):
@FieldOverride(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
```

---

## `BundleMergeStrategy`

Determines which definition wins when a spec and an included [`@FieldBundle`](FieldBundle.md) both configure the same field.

Used by: [`@ClassSpec.bundleMergeStrategy`](ClassSpec.md)

| Value | Behaviour |
|---|---|
| `SPEC_WINS` _(default)_ | The spec's own field configuration takes precedence; the bundle fills in any gaps. |
| `BUNDLE_WINS` | The bundle's field configuration takes precedence; the spec fills in gaps. |
| `MERGE_ADDITIVE` | Both configurations are combined; each side fills in what the other left unset. |

### When to use each

- **`SPEC_WINS`** — The default and most common. Bundle provides sensible defaults; the spec can selectively override them.
- **`BUNDLE_WINS`** — The bundle enforces a policy (e.g. mandatory column naming scheme); individual specs cannot deviate.
- **`MERGE_ADDITIVE`** — The spec and bundle each configure non-overlapping aspects (e.g. bundle sets annotations, spec sets transformers). Additive merge combines both without either overriding the other.

### Example

```kotlin
// Bundle defines JPA annotations; spec adds a transformer.
// MERGE_ADDITIVE ensures both sets of configs are applied.
@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    bundles = [TimestampsBundle::class, UserEntityBundle::class],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE
)
object UserSpec
```

---

## `UnmappedNestedStrategy`

Defines what the processor does when it encounters a **nested domain type** that has no explicit mapping via a `@ClassSpec`.

Used by: [`@ClassSpec.unmappedNestedStrategy`](ClassSpec.md)

| Value | Behaviour |
|---|---|
| `FAIL` _(default)_ | Abort generation with a compile-time error. Ensures no field is silently dropped. |
| `INLINE` | Flatten the nested object's fields directly into the parent class. |
| `EXCLUDE` | Silently omit the nested field from the generated class. |

### Example

```kotlin
data class Order(
    val id: Long,
    val customer: Customer,   // nested type with no @ClassSpec
)

// FAIL (default): compile error if Customer has no mapping
@ClassSpec(for_ = Order::class, suffix = "Entity")
object OrderSpec

// INLINE: Customer fields are flattened into OrderEntity
@ClassSpec(
    for_ = Order::class,
    suffix = "Entity",
    unmappedNestedStrategy = UnmappedNestedStrategy.INLINE
)
object OrderSpec

// EXCLUDE: customer field is silently dropped
@ClassSpec(
    for_ = Order::class,
    suffix = "Dto",
    unmappedNestedStrategy = UnmappedNestedStrategy.EXCLUDE
)
object OrderSpec
```

---

## `@CustomAnnotation` {#customannotation}

Represents an **arbitrary annotation** to be emitted on a generated class or field. Use this when you need to attach framework-specific annotations (JPA, Jackson, etc.) that the DSL does not natively model.

> This is not a meta-annotation — it is a normal annotation used as an **array element** inside `@ClassSpec.annotations` and `@FieldOverride.annotations`.

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `annotation` | `KClass<out Annotation>` | _(required)_ | The annotation class to emit. |
| `members` | `Array<String>` | `[]` | Key-value pairs as `"name=value"` strings. Enum values may be short names (e.g. `"fetch=LAZY"`); the processor resolves the FQN and adds the required import. |

### Examples

#### On a class

```kotlin
@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    annotations = [
        CustomAnnotation(
            annotation = jakarta.persistence.Table::class,
            members = ["name=\"users\"", "schema=\"public\""]
        ),
        CustomAnnotation(annotation = jakarta.persistence.Entity::class)
    ]
)
object UserSpec
```

Generated:

```kotlin
@Table(name = "users", schema = "public")
@Entity
data class UserEntity(...)
```

#### On a field

```kotlin
@FieldOverride(
    for_ = ["Entity"],
    property = "id",
    annotations = [
        CustomAnnotation(annotation = jakarta.persistence.Id::class),
        CustomAnnotation(
            annotation = jakarta.persistence.GeneratedValue::class,
            members = ["strategy=jakarta.persistence.GenerationType.IDENTITY"]
        )
    ]
)
object UserSpec
```

Generated field:

```kotlin
@Id
@GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
val id: Long?,
```

#### Jackson annotation with enum value

```kotlin
CustomAnnotation(
    annotation = com.fasterxml.jackson.annotation.JsonInclude::class,
    members = ["value=com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL"]
)
```

---

## See also

- [`@ClassSpec`](ClassSpec.md) — where these enums appear as parameters
- [`@FieldSpec`](FieldSpec.md) — `NullableOverride` usage
- [`@FieldOverride`](FieldOverride.md) — `NullableOverride` and `validators` usage
- [`@FieldBundle`](FieldBundle.md) — `BundleMergeStrategy` usage

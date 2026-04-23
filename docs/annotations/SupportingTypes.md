# Supporting Types

Reference for all enums and helper annotation types used throughout the annotation DSL.

---

## `NullableOverride`

Controls whether a generated field's nullability is overridden relative to the domain model.

Used by: [`@ClassField.nullable`](ClassField.md), [`@FieldSpec.nullable`](FieldSpec.md)

| Value | Behaviour |
|---|---|
| `UNSET` _(default)_ | Inherit nullability from the source domain class. |
| `YES` | Force the generated field to be nullable (`T?`). |
| `NO` | Force the generated field to be non-nullable (`T`). |

### Example

```kotlin
// Domain: val updatedAt: Instant?   (nullable)
// Force non-nullable in a specific output:
@FieldSpec(for_ = ["CreateRequest"], property = "updatedAt", nullable = NullableOverride.NO)

// Domain: val id: Long   (non-nullable)
// Force nullable for the entity (auto-generated PK):
@FieldSpec(for_ = ["Entity"], property = "id", nullable = NullableOverride.YES)
```

---

## `BundleMergeStrategy`

Determines which definition wins when a spec and an included bundle both configure the same field.

Used by: [`@ClassSpec.bundleMergeStrategy`](ClassSpec.md)

| Value | Behaviour |
|---|---|
| `SPEC_WINS` _(default)_ | The spec's own field configuration takes precedence; the bundle fills in any gaps. |
| `BUNDLE_WINS` | The bundle's field configuration takes precedence; the spec fills in gaps. |
| `MERGE_ADDITIVE` | Both configurations are combined; each side fills in what the other left unset. |

### When to use each

- **`SPEC_WINS`** — The default and most common. Bundle provides sensible defaults; the spec can selectively override them.
- **`BUNDLE_WINS`** — The bundle enforces a policy (e.g. mandatory column naming scheme); individual specs cannot deviate.
- **`MERGE_ADDITIVE`** — The spec and bundle each configure non-overlapping aspects (e.g. bundle sets column names, spec sets transformers). Additive merge combines both without either overriding the other.

### Example

```kotlin
// Bundle defines column names; spec adds transformers.
// MERGE_ADDITIVE ensures both sets of configs are applied.
@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    bundles = ["timestamps", "userEntity"],
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
| `INLINE` | Flatten the nested object's fields directly into the parent class (with an optional prefix). |
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

## `ExcludedFieldStrategy`

Defines what happens to fields that are excluded (via `@ClassField.exclude = true` or `@FieldSpec.exclude = true`) in a generated output class.

Used by: [`@ClassSpec.excludedFieldStrategy`](ClassSpec.md)

| Value | Behaviour |
|---|---|
| `USE_DEFAULT` _(default)_ | Use the field's default value if one exists; otherwise omit it from the generated class. |
| `REQUIRE_MANUAL` | Require the caller to supply the field value manually in the mapping function. |
| `NULLABLE_OVERRIDE` | Override the field to nullable so it can be set to `null`. |

### Example

```kotlin
// id is excluded from CreateRequest but the mapper still needs a value.
// REQUIRE_MANUAL forces the caller to supply it.
@ClassSpec(
    for_ = User::class,
    suffix = "CreateRequest",
    excludedFieldStrategy = ExcludedFieldStrategy.REQUIRE_MANUAL
)
@FieldSpec(for_ = ["CreateRequest"], property = "id", exclude = true)
object UserSpec
```

---

## `@Index`

Declares a database index to be generated on the enclosing entity's table. Currently referenced through `@CustomAnnotation` wrapping the target framework's own index annotation.

| Property | Type | Default | Description |
|---|---|---|---|
| `columns` | `Array<String>` | _(required)_ | Column names that form the index key. |
| `unique` | `Boolean` | `false` | When `true`, the generated index enforces uniqueness. |
| `name` | `String` | `""` | Optional explicit index name; the generator derives a name when blank. |

### Example

```kotlin
@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    annotations = [
        CustomAnnotation(
            annotation = jakarta.persistence.Table::class,
            members = [
                "name=\"users\"",
                "indexes={@Index(name=\"idx_users_email\", columnList=\"email\", unique=true)}"
            ]
        )
    ]
)
object UserSpec
```

---

## See also

- [`@ClassSpec`](ClassSpec.md) — where these enums appear as parameters
- [`@ClassField`](ClassField.md) — `NullableOverride` usage
- [`@FieldSpec`](FieldSpec.md) — `NullableOverride` usage
- [`@CustomAnnotation`](RuleExpression.md#customannotation) — forwarding `@Index` to generated classes

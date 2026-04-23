# `@ClassSpec`

Drives generation of a **single output class** from a domain type. Apply it multiple times on the same spec class to generate several output classes from one domain — each instance is uniquely identified by its `suffix`.

> **Target**: `CLASS` · **Retention**: `SOURCE` · **Repeatable**: yes

---

## Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `for_` | `KClass<*>` | _(required)_ | Domain class to generate from. |
| `suffix` | `String` | `""` | Appended to the domain class name → output class name. Also used as the discriminator in [`@FieldSpec.for_`](FieldSpec.md). |
| `prefix` | `String` | `""` | Prepended to the domain class name. |
| `partial` | `Boolean` | `false` | When `true`, every generated field is nullable with `= null` defaults (update-request style). |
| `bundles` | `Array<String>` | `[]` | Names of [`@FieldBundle`](FieldBundle.md) classes whose field configs are merged into this spec. |
| `bundleMergeStrategy` | `BundleMergeStrategy` | `SPEC_WINS` | How to resolve conflicts when spec and bundle configure the same field. See [`BundleMergeStrategy`](SupportingTypes.md#bundlemergestrategy). |
| `unmappedNestedStrategy` | `UnmappedNestedStrategy` | `FAIL` | What to do when a nested domain type has no explicit mapping. See [`UnmappedNestedStrategy`](SupportingTypes.md#unmappednestedstrategy). |
| `excludedFieldStrategy` | `ExcludedFieldStrategy` | `USE_DEFAULT` | How to treat excluded fields in the output class. See [`ExcludedFieldStrategy`](SupportingTypes.md#excludedfieldstrategy). |
| `annotations` | `Array<CustomAnnotation>` | `[]` | Annotations forwarded verbatim to the generated class (e.g. `@Entity`, `@Table`). See [`@CustomAnnotation`](RuleExpression.md#customannotation). |
| `validateOnConstruct` | `Boolean` | `false` | When `true`, emits `init { validateOrThrow() }` so validation runs on construction. Useful when deserialisation frameworks call the constructor directly. |

---

## Output-Kind Inference

The processor automatically decides what kind of class to emit:

| Condition | Output kind |
|---|---|
| Any `@FieldSpec` scoped to this suffix has non-empty `rules` | **Request class** — emits `validate()` and `validateOrThrow()` |
| `partial = true` (without rules) | **Partial request class** — all fields nullable, same validation methods |
| Otherwise | **Data class** with bidirectional mapper functions (`to<Suffix>()` / `toDomain()`) |

---

## Example

### Domain class

```kotlin
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant?,
)
```

### Spec class

```kotlin
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
@ClassSpec(for_ = User::class, suffix = "CreateRequest")
@ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true)
object UserSpec
```

### Generated `UserEntity`

```kotlin
@Table(name = "users", schema = "public")
data class UserEntity(
    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    val id: Long?,
    @Column(name = "user_name")
    val name: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at")
    val updatedAt: Instant?,
)
```

### Generated `UserCreateRequest`

```kotlin
data class UserCreateRequest(
    val name: String,
    val email: String,
) {
    fun validate(): ValidationResult { ... }
    fun validateOrThrow() { ... }
}
```

### Generated `UserUpdateRequest` (partial)

```kotlin
data class UserUpdateRequest(
    val name: String? = null,
    val email: String? = null,
) {
    fun validate(): ValidationResult { ... }
    fun validateOrThrow() { ... }
}
```

---

## Name formation

`<prefix><DomainClassName><suffix>`

| `prefix` | `suffix` | Domain class | Output class |
|---|---|---|---|
| `""` | `"Entity"` | `User` | `UserEntity` |
| `""` | `"Response"` | `User` | `UserResponse` |
| `"Api"` | `"Dto"` | `User` | `ApiUserDto` |
| `""` | `""` | `User` | `User` _(same name — unusual)_ |

---

## See also

- [`@ClassField`](ClassField.md) — shared field overrides across all outputs
- [`@FieldSpec`](FieldSpec.md) — per-output field overrides
- [`@FieldBundle`](FieldBundle.md) — reusable field configuration bundles

# `Default`

Default-value configuration nested annotation used by [`@FieldSpec`](FieldSpec.md), [`@FieldOverride`](FieldOverride.md), and [`@AddField`](AddField.md) as the value of their `default` parameter. The no-arg sentinel `Default()` means "no default configured" — the generated constructor parameter has no `= ...` initializer.

> **Not applied directly**: `Default` is only used as an annotation-argument value. You never write `@Default` on a property.

---

## Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `value` | `String` | `""` | Kotlin expression spliced **verbatim** into the generated constructor as `= <value>`. String literals must include their own quotes (`value = "\"hi\""`). Mutually exclusive with `inherit` and `clearInherited`. |
| `inherit` | `Boolean` | `false` | When `true`, copies the default expression from the source property by reading the source file via KSP `Location` offsets. Mutually exclusive with `value` and `clearInherited`. Not valid on `@AddField`. |
| `clearInherited` | `Boolean` | `false` | When `true`, removes a default merged from the class-level `@FieldSpec` for the named output(s). Only valid on `@FieldOverride`. Mutually exclusive with `value` and `inherit`. |

---

## Mutual-exclusion rules

The processor reports a KSP error at the annotation site when any of these violations occur:

- `value` non-empty **and** `inherit = true` on the same `Default`.
- `clearInherited = true` combined with `value` non-empty or `inherit = true`.
- `clearInherited = true` on `@FieldSpec` or `@AddField` (only `@FieldOverride` may clear).
- `inherit = true` on a source property whose declaration lives in a compiled dependency JAR (no readable source location).
- `value` fails the light syntactic check: unbalanced `"`, `'`, `()`, `[]`, `{}` pairs or a trailing `;`.

---

## Layering

`@FieldOverride.default` replaces `@FieldSpec.default` for the named outputs **as a whole** when it is non-sentinel — there is no field-by-field merge between two `Default` instances. To strip an inherited default for one output while leaving spec-level config intact, use `Default(clearInherited = true)`.

---

## Examples

### Explicit value

```kotlin
@FieldSpec(property = "role", default = Default(value = "Role.USER"))
@FieldSpec(property = "tags", default = Default(value = "emptyList()"))
@FieldSpec(property = "name", default = Default(value = "\"anon\""))   // String literal — quotes included
object UserSpec
```

Generated:

```kotlin
data class UserCreateRequest(
    val name: String = "anon",
    val role: Role = Role.USER,
    val tags: List<String> = emptyList(),
)
```

### Inherit the source default

```kotlin
// User.kt
data class User(
    val id: Long,
    val createdAt: java.time.Instant = java.time.Instant.now(),
)

// UserSpec.kt
@FieldOverride(for_ = ["Response"], property = "createdAt", default = Default(inherit = true))
object UserSpec
```

Generated:

```kotlin
data class UserResponse(
    val id: Long,
    val createdAt: Instant = java.time.Instant.now(),
)
```

### Clear an inherited default for one output

```kotlin
@FieldSpec(property = "createdAt", default = Default(inherit = true))

// Entity stores the timestamp via the database; no default needed in the data class.
@FieldOverride(for_ = ["Entity"], property = "createdAt", default = Default(clearInherited = true))
object UserSpec
```

---

## Constraints worth remembering

- **`value` is verbatim** — the processor does not auto-quote string literals or escape anything. The expression you pass is the expression that gets generated.
- **`inherit` reads the file** — the recovered expression text is exactly what appears between `=` and the next top-level `,` or `)` in the source. Comments inside the expression are preserved verbatim.
- **No implicit nullable default** — `nullable = NullableOverride.YES` does not inject `= null`. Write `default = Default(value = "null")` explicitly if you want that.

---

## See also

- [`@FieldSpec`](FieldSpec.md) — class-wide field defaults
- [`@FieldOverride`](FieldOverride.md) — per-output overrides
- [`@AddField`](AddField.md) — synthetic fields with their own defaults

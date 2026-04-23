# `@RuleExpression`, `@Rule.*`, and `@CustomAnnotation`

---

## `@RuleExpression`

Marks an annotation class as a **validation rule**. The processor reads the `expression` string from this annotation and emits a corresponding `ctx.ensure(...)` call in the generated request class's `validate()` method.

> **Target**: `ANNOTATION_CLASS` · **Retention**: `BINARY`

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `expression` | `String` | _(required)_ | Kotlin expression template evaluated per field. |

### Placeholders

| Placeholder | Replaced with |
|---|---|
| `{field}` | The actual field name (e.g. `email`, `name`). |
| `{paramName}` | The value of the rule annotation's own parameter named `paramName` (e.g. `{value}` for `@Rule.MinLength(value = 3)`). |

### Defining a custom rule

```kotlin
@RuleExpression("{field}.startsWith(\"ACM-\")")
annotation class AcmPrefix
```

Reference it in a `@FieldSpec`:

```kotlin
@FieldSpec(for_ = ["CreateRequest"], property = "code", rules = [AcmPrefix::class])
object OrderSpec
```

Processor emits:

```kotlin
ctx.ensure(code.startsWith("ACM-"), FieldRef("code"), "code failed rule AcmPrefix")
```

### Custom rule with a parameter

```kotlin
@RuleExpression("{field}.startsWith({prefix})")
annotation class StartsWith(val prefix: String)

// Usage
@FieldSpec(for_ = ["CreateRequest"], property = "code", rules = [StartsWith::class])
// Note: rule annotation parameter values come from the annotation instance on the field,
// not from the FieldSpec — use built-in Rule.Pattern for parameterised rules instead.
```

---

## Built-in `@Rule.*` rules

All built-in rules live as inner annotations on the `Rule` class. Each is pre-annotated with `@RuleExpression`.

| Rule | Expression emitted | Parameters |
|---|---|---|
| `Rule.Required` | `{field} != null` | — |
| `Rule.Email` | `{field}.contains("@")` | — |
| `Rule.NotBlank` | `{field}.isNotBlank()` | — |
| `Rule.Positive` | `{field} > 0` | — |
| `Rule.Past` | `{field}.isBefore(java.time.LocalDate.now())` | — |
| `Rule.Future` | `{field}.isAfter(java.time.LocalDate.now())` | — |
| `Rule.MinLength` | `{field}.length >= {value}` | `value: Int` |
| `Rule.MaxLength` | `{field}.length <= {value}` | `value: Int` |
| `Rule.Min` | `{field} >= {value}` | `value: Double` |
| `Rule.Max` | `{field} <= {value}` | `value: Double` |
| `Rule.Pattern` | `Regex("{regex}").matches({field})` | `regex: String`, `message: String = ""` |
| `Rule.Custom` | _(calls FQN function)_ | `fn: String` |

### Usage examples

```kotlin
// Single rule
@FieldSpec(for_ = ["CreateRequest"], property = "email", rules = [Rule.Email::class])

// Multiple rules on one field
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "email",
    rules = [Rule.Email::class, Rule.NotBlank::class]
)

// Parameterised rules
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "username",
    rules = [Rule.MinLength::class, Rule.MaxLength::class]
)

// Pattern match
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "postalCode",
    rules = [Rule.Pattern::class]   // regex comes from annotation instance
)

// Numeric range
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "age",
    rules = [Rule.Min::class, Rule.Max::class]
)
```

### Generated `validate()` method

Given:

```kotlin
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "email",
    rules = [Rule.Email::class, Rule.NotBlank::class]
)
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "name",
    rules = [Rule.NotBlank::class]
)
object UserSpec
```

The processor generates:

```kotlin
data class UserCreateRequest(
    val name: String,
    val email: String,
) {
    fun validate(): ValidationResult {
        val ctx = ValidationContext()
        ctx.ensure(name.isNotBlank(), FieldRef("name"), "name failed rule NotBlank")
        ctx.ensure(email.contains("@"), FieldRef("email"), "email failed rule Email")
        ctx.ensure(email.isNotBlank(), FieldRef("email"), "email failed rule NotBlank")
        return ctx.build()
    }

    fun validateOrThrow() {
        val result = validate()
        if (result is ValidationResult.Invalid) {
            throw ValidationException(result.errors)
        }
    }
}
```

---

## `@CustomAnnotation` {#customannotation}

Represents an **arbitrary annotation** to be emitted on a generated class or field. Use this when you need to attach framework-specific annotations (JPA, Jackson, etc.) that the DSL does not natively model.

> This is not a meta-annotation — it is a normal annotation class used as an **array element** inside `@ClassSpec.annotations` and `@FieldSpec.annotations`.

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `annotation` | `KClass<out Annotation>` | _(required)_ | The annotation class to emit. |
| `members` | `Array<String>` | `[]` | Key-value pairs for the annotation's parameters, each as `"name=value"`. Enum values may be short names (e.g. `"fetch=LAZY"`); the processor resolves the FQN and adds the required import. |

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
        CustomAnnotation(
            annotation = jakarta.persistence.Entity::class
        )
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
@FieldSpec(
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

- [`@FieldSpec.rules`](FieldSpec.md#request-specific) — where rule classes are referenced
- [`@ClassSpec.annotations`](ClassSpec.md) — forwarding annotations to generated classes
- Runtime types: `ValidationResult`, `ValidationContext`, `ValidationException` — in the `:runtime` module

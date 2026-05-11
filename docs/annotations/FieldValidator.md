# `FieldValidator`

`FieldValidator<in T>` is the runtime interface for single-field validation rules. Implement it as a singleton `object` and reference it by `KClass` in [`@FieldOverride.validators`](FieldOverride.md).

The processor emits a delegation call for each validator in the generated `validate()` method:

```kotlin
EmailValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
```

---

## Interface

```kotlin
interface FieldValidator<in T> {
    val message: String
    fun validate(value: T): Boolean
}
```

| Member | Description |
|---|---|
| `message` | Human-readable failure description recorded in `ValidationError.message` when `validate` returns `false`. |
| `validate(value)` | Returns `true` if the value passes the rule, `false` otherwise. Must be pure and side-effect-free. |

---

## Defining validators

Each validator is a singleton `object`:

```kotlin
object EmailValidator : FieldValidator<String> {
    override val message = "must be a valid email address"
    override fun validate(value: String) = value.contains("@")
}

object NotBlankValidator : FieldValidator<String> {
    override val message = "must not be blank"
    override fun validate(value: String) = value.isNotBlank()
}

object PositiveValidator : FieldValidator<Int> {
    override val message = "must be greater than zero"
    override fun validate(value: Int) = value > 0
}
```

---

## Referencing validators

Pass validator classes to `@FieldOverride.validators`:

```kotlin
@FieldOverride(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)

// Share the same set across multiple outputs
@FieldOverride(
    for_ = ["CreateRequest", "UpdateRequest"],
    property = "name",
    validators = [NotBlankValidator::class]
)
```

---

## Composition

Validators are regular Kotlin objects and can delegate to each other:

```kotlin
object NonEmptyEmailValidator : FieldValidator<String> {
    override val message = "must be a non-empty valid email"
    override fun validate(value: String) =
        NotBlankValidator.validate(value) && EmailValidator.validate(value)
}
```

---

## Generated output

Given:

```kotlin
@FieldOverride(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)
@FieldOverride(
    for_ = ["CreateRequest"],
    property = "name",
    validators = [NotBlankValidator::class]
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
        NotBlankValidator.let { v -> ctx.ensure(v.validate(name), FieldRef("name"), v.message) }
        EmailValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
        NotBlankValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
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

### Nullable fields

When the field is nullable (e.g. `partial = true`), the validator calls are wrapped in a null guard:

```kotlin
if (email != null) {
    EmailValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
}
```

---

## Using `validate()` directly

```kotlin
when (val result = request.validate()) {
    is ValidationResult.Valid   -> println("ok")
    is ValidationResult.Invalid -> result.errors.forEach { println("${it.field.name}: ${it.message}") }
}
```

## Using `validateOrThrow()`

```kotlin
request.validateOrThrow()   // throws ValidationException if any validator fails
```

`ValidationException.message` summarises all failures:
`"Validation failed: email: must be a valid email address; name: must not be blank"`

---

## See also

- [`@FieldOverride.validators`](FieldOverride.md) — where validator classes are referenced
- [`ObjectValidator`](ObjectValidator.md) — sibling interface for cross-field rules that span multiple properties
- Runtime types: `ValidationResult`, `ValidationContext`, `ValidationException`, `ValidationError` — in the `:runtime` module

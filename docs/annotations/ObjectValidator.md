# `ObjectValidator`

`ObjectValidator<in T>` is the runtime interface for cross-field validation rules — rules that need access to more than one property on the generated class. Implement it as a singleton `object` and reference it by `KClass` in [`@ClassSpec.validators`](ClassSpec.md).

Unlike [`FieldValidator`](FieldValidator.md), an `ObjectValidator` receives the whole generated object plus a [`ValidationContext`] and writes errors directly into the context — there is no boolean return and no fixed `message`. The validator decides which field(s) to attribute the failure to and what message each one gets.

---

## Interface

```kotlin
interface ObjectValidator<in T> {
    fun validate(value: T, ctx: ValidationContext)
}
```

| Member | Description |
|---|---|
| `validate(value, ctx)` | Inspect [value] and call `ctx.error(...)` / `ctx.ensure(...)` for any failures. Pure and side-effect-free aside from writes to [ctx]. |

---

## Defining a validator

A singleton `object` implementing `ObjectValidator<GeneratedClass>`:

```kotlin
object NameRequiredValidator : ObjectValidator<UserCreateRequest> {
    override fun validate(value: UserCreateRequest, ctx: ValidationContext) {
        if (!value.fullName.isNullOrBlank()) return
        if (!value.firstName.isNullOrBlank() && !value.lastName.isNullOrBlank()) return

        if (value.firstName.isNullOrBlank())
            ctx.error("firstName", "required when fullName is absent")
        if (value.lastName.isNullOrBlank())
            ctx.error("lastName",  "required when fullName is absent")
    }
}
```

The validator's type argument **must equal the generated output class** for the `@ClassSpec` it's registered on (i.e. `prefix + domainName + suffix`). The processor verifies this at compile time and fails with a KSP error on mismatch.

---

## Referencing a validator

Pass validator classes to `@ClassSpec.validators`:

```kotlin
@ClassSpec(
    for_ = User::class,
    suffix = "CreateRequest",
    validators = [NameRequiredValidator::class],
)
object UserSpec
```

Multiple object validators run in declaration order. The same validator class can be listed on multiple `@ClassSpec` instances (e.g. `CreateRequest` and `UpdateRequest`) — list it once per spec instance that needs it.

---

## `ValidationContext` helpers

For unconditional error attribution inside an object validator, use:

```kotlin
ctx.error("firstName", "required when fullName is absent")
ctx.error(FieldRef("dateRange"), "endDate must be after startDate")
```

The existing `ctx.ensure(condition, field, message)` also works inside an `ObjectValidator` if you prefer the boolean shape.

---

## Generated output

Given:

```kotlin
@ClassSpec(
    for_ = User::class,
    suffix = "CreateRequest",
    validators = [EmailMatchesNameValidator::class],
)
@FieldOverride(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class],
)
object UserSpec
```

The processor emits:

```kotlin
public fun validate(): ValidationResult {
    val ctx = ValidationContext()
    EmailValidator.let    { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
    NotBlankValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
    EmailMatchesNameValidator.validate(this, ctx)
    return ctx.build()
}
```

### Emit order

**Field validators run first, in property order; then object validators run in `@ClassSpec.validators` declaration order.** This is part of the public contract — don't write rules that depend on the opposite order.

### Nullable fields

`ObjectValidator` receives the whole object and is **not** wrapped in a null guard. Validators are responsible for checking nullability themselves where appropriate.

### `partial = true` outputs

When a `@ClassSpec` has `partial = true`, every field on the generated class is nullable. A cross-field rule written for the non-partial output (e.g. `CreateRequest`) will usually misbehave on the partial output (e.g. `UpdateRequest`) because every field looks "missing".

Either omit the validator from the partial spec, or make the validator partial-aware (return early when no relevant fields are set).

---

## Composition

Object validators are regular Kotlin objects and can delegate freely:

```kotlin
object CombinedRules : ObjectValidator<UserCreateRequest> {
    override fun validate(value: UserCreateRequest, ctx: ValidationContext) {
        NameRequiredValidator.validate(value, ctx)
        DateRangeValidator.validate(value, ctx)
    }
}
```

---

## See also

- [`@ClassSpec.validators`](ClassSpec.md) — where object validator classes are referenced
- [`FieldValidator`](FieldValidator.md) — sibling interface for single-field rules
- Runtime types: `ValidationResult`, `ValidationContext`, `ValidationException`, `ValidationError` — in the `:runtime` module

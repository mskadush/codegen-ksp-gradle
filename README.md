# codegen-ksp-gradle

A Kotlin Symbol Processing (KSP) code-generation framework that derives data classes, DTOs, and request objects directly from annotated domain classes — eliminating boilerplate and keeping generated types in sync with their source.

## How it works

1. Annotate a plain **spec class** with `@ClassSpec` to declare what output you want.
2. At compile time the **KSP processor** reads the annotations, inspects the referenced domain class, and writes a new Kotlin source file into the build's generated-sources directory.
3. The generated class is available immediately for use in the same compilation.

```
Domain class  ──(@ClassSpec)──▶  processor  ──▶  Generated data class
```

## Modules

| Module | Description |
|---|---|
| `:annotations` | Public annotation API — zero dependencies, only declarations. |
| `:processor` | KSP processor that reads the annotations and generates code. |
| `:runtime` | Runtime utilities shared by generated code (`ValidationContext`, `FieldValidator`, etc.). |
| `:app` | Example application demonstrating usage. |

---

## Quick start

### 1. Apply KSP and add dependencies

```kotlin
// app/build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":annotations"))
    implementation(project(":runtime"))
    ksp(project(":processor"))
}
```

### 2. Write a domain class

```kotlin
data class User(
    val id: Long,
    val name: String,
    val email: String,
)
```

### 3. Create a spec class

```kotlin
@ClassSpec(for_ = User::class, suffix = "CreateRequest")
@FieldSpec(for_ = ["CreateRequest"], property = "id", exclude = true)
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)
object UserSpec
```

The processor generates:

```kotlin
data class UserCreateRequest(
    val name: String,
    val email: String,
) {
    fun validate(): ValidationResult { ... }
    fun validateOrThrow() { ... }
}
```

---

## Annotations reference

### `@ClassSpec`

Drives generation of a single output class from a domain type. Repeatable — apply it multiple times to generate several outputs from one domain class.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `for_` | `KClass<*>` | _(required)_ | Domain class to generate from. |
| `suffix` | `String` | `""` | Appended to the domain class name. |
| `prefix` | `String` | `""` | Prepended to the domain class name. |
| `partial` | `Boolean` | `false` | Every field becomes nullable with `= null` (update-request style). |
| `bundles` | `Array<KClass<*>>` | `[]` | `@FieldBundle` classes whose field configs are merged into this spec. |
| `bundleMergeStrategy` | `BundleMergeStrategy` | `SPEC_WINS` | Conflict resolution when spec and bundle configure the same field. |
| `unmappedNestedStrategy` | `UnmappedNestedStrategy` | `FAIL` | What to do with nested domain types that have no explicit mapping. |
| `annotations` | `Array<CustomAnnotation>` | `[]` | Annotations forwarded verbatim to the generated class (e.g. `@Entity`, `@Table`). |
| `validateOnConstruct` | `Boolean` | `false` | Emits `init { validateOrThrow() }` so validation runs on construction. |

### `@ClassField` _(repeatable)_

Shared field override applied to **every** output class on the spec.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `property` | `String` | _(required)_ | Domain property name to configure. |
| `exclude` | `Boolean` | `false` | Omit this field from every generated output. |
| `nullable` | `NullableOverride` | `UNSET` | Override field nullability. |
| `transformer` | `KClass<out FieldTransformer<*,*>>` | `NoOpTransformer::class` | Value converter class. |
| `transformerRef` | `String` | `""` | Named transformer from a `@TransformerRegistry`. Wins over `transformer`. |
| `annotations` | `Array<CustomAnnotation>` | `[]` | Annotations forwarded to this field in every output. |

### `@FieldSpec` _(repeatable)_

Per-output field override, scoped to one or more `@ClassSpec` instances by `for_`.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `for_` | `Array<String>` | _(required)_ | `@ClassSpec.suffix` values this override applies to. |
| `property` | `String` | _(required)_ | Domain property name to configure. |
| `exclude` | `Boolean` | `false` | Omit this field from the named output(s). |
| `nullable` | `NullableOverride` | `UNSET` | Override field nullability for the named output(s). |
| `transformer` | `KClass<out FieldTransformer<*,*>>` | `NoOpTransformer::class` | Value converter class. |
| `transformerRef` | `String` | `""` | Named transformer from a `@TransformerRegistry`. |
| `annotations` | `Array<CustomAnnotation>` | `[]` | Annotations forwarded to this field in the named output(s). |
| `rename` | `String` | `""` | Alternative field name in the generated class. |
| `validators` | `Array<KClass<out FieldValidator<*>>>` | `[]` | Runtime validators; each must be a singleton `object` implementing `FieldValidator`. |

---

## Bundles

Bundles let you share field configurations across multiple spec classes without duplication.

```kotlin
@FieldBundle
@FieldSpec(for_ = ["Entity"], property = "createdAt",
    annotations = [CustomAnnotation(jakarta.persistence.Column::class, ["name=\"created_at\""])])
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
object TimestampsBundle

@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    bundles = [TimestampsBundle::class],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE
)
object UserSpec
```

Bundles can include other bundles transitively via `@IncludeBundles`:

```kotlin
@FieldBundle
@IncludeBundles([OrderIdBundle::class])
object OrderBaseBundle
```

---

## Nested type strategies

When a domain class has a field whose type is another domain class, `unmappedNestedStrategy` controls what happens:

| Value | Behaviour |
|---|---|
| `FAIL` _(default)_ | Compile-time error if a nested type has no `@ClassSpec` for the same suffix. |
| `INLINE` | Flatten the nested object's fields into the parent class, prefixed by the field name. |
| `EXCLUDE` | Silently omit the nested field from the generated class. |
| `AUTO_GENERATE` | Automatically generate a passthrough output class for the nested type, using the same suffix, prefix, and output package. Recurses transitively through the nested type's own fields. |

`AUTO_GENERATE` eliminates boilerplate spec files for domain types that need no customisation:

```kotlin
// Order.kt
data class Order(val id: Long, val address: Address, val status: OrderStatus)

// AddressSpec.kt — no longer needed
// @ClassSpec(for_ = Address::class, suffix = "Entity")
// object AddressSpec

// OrderSpec.kt — Address → AddressEntity is auto-generated
@ClassSpec(
    for_ = Order::class,
    suffix = "Entity",
    unmappedNestedStrategy = UnmappedNestedStrategy.AUTO_GENERATE,
)
object OrderSpec
```

Explicit `@ClassSpec` declarations always win — if `Address` has its own spec, that spec is used as-is. Auto-generated types are never `partial`; that flag must be declared explicitly.

---

## Validation

Validation logic lives in `FieldValidator` implementations you define — no baked-in expression templates.

### 1. Define validators

```kotlin
object EmailValidator : FieldValidator<String> {
    override val message = "must be a valid email address"
    override fun validate(value: String) = value.contains("@")
}

object NotBlankValidator : FieldValidator<String> {
    override val message = "must not be blank"
    override fun validate(value: String) = value.isNotBlank()
}
```

### 2. Reference by `KClass` in `@FieldSpec`

```kotlin
@FieldSpec(
    for_ = ["CreateRequest"],
    property = "email",
    validators = [EmailValidator::class, NotBlankValidator::class]
)
object UserSpec
```

### 3. Use the generated methods

```kotlin
when (val result = request.validate()) {
    is ValidationResult.Valid   -> println("ok")
    is ValidationResult.Invalid -> result.errors.forEach { println(it) }
}

// Or throw on failure:
request.validateOrThrow()
```

Validators are composable — one can delegate to others:

```kotlin
object NonEmptyEmailValidator : FieldValidator<String> {
    override val message = "must be a non-empty email"
    override fun validate(value: String) =
        NotBlankValidator.validate(value) && EmailValidator.validate(value)
}
```

---

## Field transformers

Implement `FieldTransformer<Domain, Target>` to convert field values between the domain and generated type.

```kotlin
class UpperCaseTransformer : FieldTransformer<String, String> {
    override fun toTarget(value: String) = value.uppercase()
    override fun toDomain(value: String) = value
}

// Reference by class:
@FieldSpec(for_ = ["Entity"], property = "name", transformer = UpperCaseTransformer::class)

// Or register by name and reference as a string:
@TransformerRegistry
object MyTransformers {
    @RegisterTransformer("upperCase")
    val upperCase = UpperCaseTransformer()
}

@FieldSpec(for_ = ["Response"], property = "name", transformerRef = "upperCase")
```

---

## Building

```bash
./gradlew build
```

## Requirements

- Kotlin 2.x
- JDK 21
- KSP `2.3.5`

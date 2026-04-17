# codegen-ksp-gradle

A Kotlin Symbol Processing (KSP) code-generation framework that derives entity classes, DTOs, and request objects directly from annotated domain classes — eliminating the boilerplate of writing these by hand.

## How it works

1. You annotate a plain **spec class** with one of the provided annotations (e.g. `@EntitySpec`).
2. At compile time the **KSP processor** reads the annotation, inspects the referenced domain class, and writes a new Kotlin source file into the build's generated-sources directory.
3. The generated class is available immediately for use in the same compilation.

```
Domain class  ──(@EntitySpec)──▶  processor  ──▶  Generated entity class
```

## Modules

| Module | Description |
|---|---|
| `:annotations` | Public annotation API — zero dependencies, only declarations. |
| `:processor` | KSP processor that reads the annotations and generates code. |
| `:runtime` | Runtime utilities shared by generated code. |
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
    ksp(project(":processor"))
}
```

### 2. Write a domain class

```kotlin
data class User(
    val id: Int,
    val email: String,
    val passwordHash: String,
)
```

### 3. Create a spec class

```kotlin
@EntitySpec(for_ = User::class, table = "users", schema = "public")
@EntityField(property = "passwordHash", column = "password_hash")
class UserEntitySpec
```

The processor generates:

```kotlin
// build/generated/ksp/.../UserEntity.kt
data class UserEntity(
    val id: Int,
    val email: String,
    val passwordHash: String,
)
```

---

## Annotations reference

### Entity generation

#### `@EntitySpec`

Marks a spec class to generate a database entity from a domain class.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `for_` | `KClass<*>` | — | Domain class to generate from (**required**). |
| `table` | `String` | `""` | Database table name; derived from the class name when blank. |
| `schema` | `String` | `""` | Database schema; uses the datasource default when blank. |
| `bundles` | `Array<String>` | `[]` | Names of `@EntityBundle` classes to merge. |
| `bundleMergeStrategy` | `BundleMergeStrategy` | `SPEC_WINS` | Conflict resolution when a spec and bundle both configure a field. |
| `unmappedNestedStrategy` | `UnmappedNestedStrategy` | `FAIL` | What to do with nested types that have no explicit mapping. |
| `missingRelationStrategy` | `MissingRelationStrategy` | `FAIL` | What to do when a field looks like a relation but has no `@Relation`. |
| `annotations` | `Array<DbAnnotation>` | `[]` | Extra annotations emitted on the generated class (e.g. `@Table`). |
| `indexes` | `Array<Index>` | `[]` | Database indexes to declare on the generated entity. |

#### `@EntityField` _(repeatable)_

Overrides the mapping of a single field on the same spec class as `@EntitySpec`.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `property` | `String` | — | Domain property name to configure (**required**). |
| `column` | `String` | `""` | Database column name; derived from the property name when blank. |
| `exclude` | `Boolean` | `false` | Omit this field from the generated entity. |
| `nullable` | `NullableOverride` | `UNSET` | Override field nullability. |
| `transformer` | `KClass<FieldTransformer>` | `NoOpTransformer` | Value converter class. |
| `transformerRef` | `String` | `""` | Named transformer from a `@TransformerRegistry`. |
| `relation` | `Relation` | `Relation(NONE)` | ORM relationship configuration. |
| `annotations` | `Array<DbAnnotation>` | `[]` | Extra annotations emitted on the generated field. |
| `inline` | `Boolean` | `false` | Flatten a nested object's fields into this entity. |
| `inlinePrefix` | `String` | `""` | Prefix for inlined field names. |

---

### DTO generation _(planned)_

#### `@DtoSpec`

| Parameter | Type | Default | Description |
|---|---|---|---|
| `for_` | `KClass<*>` | — | Domain class to generate from (**required**). |
| `suffix` | `String` | `"Dto"` | Appended to the domain class name. |
| `prefix` | `String` | `""` | Prepended to the domain class name. |
| `bundles` | `Array<String>` | `[]` | `@DtoBundle` names to merge. |
| `bundleMergeStrategy` | `BundleMergeStrategy` | `SPEC_WINS` | Conflict resolution strategy. |
| `unmappedNestedStrategy` | `UnmappedNestedStrategy` | `FAIL` | Unmapped nested type handling. |
| `excludedFieldStrategy` | `ExcludedFieldStrategy` | `USE_DEFAULT` | Excluded field handling. |

#### `@DtoField` _(repeatable)_

| Parameter | Type | Default | Description |
|---|---|---|---|
| `property` | `String` | — | Domain property name (**required**). |
| `rename` | `String` | `""` | Alternative name in the generated DTO. |
| `exclude` | `Boolean` | `false` | Omit this field from the generated DTO. |
| `nullable` | `NullableOverride` | `UNSET` | Override field nullability. |
| `transformer` | `KClass<FieldTransformer>` | `NoOpTransformer` | Value converter class. |
| `transformerRef` | `String` | `""` | Named transformer from a `@TransformerRegistry`. |

---

### Request generation _(planned)_

Use `@RequestSpec` + `@CreateSpec` / `@UpdateSpec` together on the same spec class.

```kotlin
@RequestSpec(for_ = User::class)
@CreateSpec(
    fields = [CreateField(property = "email", rules = [Rule.Email::class, Rule.Required::class])]
)
@UpdateSpec(partial = true)
class UserRequestSpec
```

| Annotation | Key parameters | Description |
|---|---|---|
| `@RequestSpec` | `for_` | Binds the spec to a domain class. |
| `@CreateSpec` | `suffix`, `validator`, `fields` | Generates a create-request class. |
| `@UpdateSpec` | `suffix`, `partial`, `validator`, `fields` | Generates an update-request class. All fields become nullable when `partial = true`. |
| `@CreateField` | `property`, `rules`, `exclude` | Per-field rules for the create request. |
| `@UpdateField` | `property`, `rules`, `exclude` | Per-field rules for the update request. |

---

### Bundles

Bundles let you share field configurations across multiple spec classes.

```kotlin
@EntityBundle("auditable")
@EntityField(property = "createdAt", column = "created_at")
@EntityField(property = "updatedAt", column = "updated_at")
class AuditableBundle

@EntitySpec(for_ = User::class, bundles = ["auditable"])
class UserEntitySpec
```

`@DtoBundle` and `@RequestBundle` work the same way for their respective spec types.

---

### Field transformers

Implement `FieldTransformer<Domain, Target>` to convert field values between the domain and generated type.

```kotlin
class UuidStringTransformer : FieldTransformer<UUID, String> {
    override fun toTarget(value: UUID) = value.toString()
    override fun toDomain(value: String) = UUID.fromString(value)
}

// Reference by class:
@EntityField(property = "id", transformer = UuidStringTransformer::class)

// Or register by name and reference as a string:
@TransformerRegistry
object MyTransformers {
    @RegisterTransformer("uuidString")
    val uuid = UuidStringTransformer()
}

@EntityField(property = "id", transformerRef = "uuidString")
```

---

### Validation rules

Apply `@Rule.*` annotations inside `@CreateField` or `@UpdateField`:

| Rule | Parameters | Description |
|---|---|---|
| `@Rule.Required` | — | Field must be non-null. |
| `@Rule.NotBlank` | — | String must not be blank. |
| `@Rule.Email` | — | String must be a valid email. |
| `@Rule.Positive` | — | Number must be > 0. |
| `@Rule.Min` | `value: Double` | Number must be ≥ value. |
| `@Rule.Max` | `value: Double` | Number must be ≤ value. |
| `@Rule.MinLength` | `value: Int` | String length must be ≥ value. |
| `@Rule.MaxLength` | `value: Int` | String length must be ≤ value. |
| `@Rule.Pattern` | `regex`, `message` | String must match regex. |
| `@Rule.Past` | — | Date/time must be in the past. |
| `@Rule.Future` | — | Date/time must be in the future. |
| `@Rule.Custom` | `fn: String` | Calls a custom validation function by FQN. |

---

## Processor internals

```
DomainMappingProcessorProvider   ← registered via META-INF/services
    │
    └── process(resolver)
            │
            ├── getSymbolsWithAnnotation("@EntitySpec")
            │       │
            │       └── EntityGenerator.generate(specClass)
            │               │
            │               ├── extract for_ → domainClass
            │               ├── ClassResolver.resolve(domainClass)
            │               │       └── validate data class + primary constructor
            │               │           map params → List<FieldModel>
            │               └── KotlinPoet → write <DomainName>Entity.kt
            │
            └── (future) getSymbolsWithAnnotation("@DtoSpec") → DtoGenerator
```

### Key classes

| Class | Module | Role |
|---|---|---|
| `DomainMappingProcessorProvider` | `:processor` | KSP entry point; discovered via service loader. |
| `EntityGenerator` | `:processor` | Reads `@EntitySpec`, writes `<Name>Entity` data class. |
| `ClassResolver` | `:processor` | Validates the domain class and maps constructor params to `FieldModel`. |
| `FieldModel` | `:processor` | Carries per-field metadata (name, KSP type, KotlinPoet type) between resolver and generator. |

---

## Building

```bash
./gradlew build
```

## Requirements

- Kotlin 2.x
- JDK 21
- KSP `2.3.5`

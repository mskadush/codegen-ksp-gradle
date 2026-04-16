This is a summarisation task — no research needed, I'll compile directly from our conversation.

---

# Domain Mapping Annotation Processor — Full Specification

---

## 1. Overview

A Kotlin Symbol Processing (KSP) annotation processor that generates DB entities, DTOs, create/update request classes, and all mapping functions from annotated domain data classes. Config for each target is declared in separate spec objects, keeping domain models clean. Field value transformation is handled via a typed `FieldTransformer` interface with an optional named registry.

---

## 2. Module Layout

```
root/
├── annotations/          # Pure Kotlin — no KSP dep. Consumed by all modules.
├── processor/            # KSP processor. Depends on annotations.
├── runtime/              # FieldTransformer, RequestValidator, ValidationContext, etc.
└── sample/               # Working example driving development.
```

Consumer dependencies:
```kotlin
dependencies {
    implementation(project(":annotations"))
    implementation(project(":runtime"))
    ksp(project(":processor"))
}
```

---

## 3. Annotation API

### 3.1 Domain Model

The domain class itself carries no annotations. It must be a `data class`.

```kotlin
data class User(
    val id: UUID,
    val name: String,
    val email: String?,
    val passwordHash: String,
    val organisationId: UUID,
    val address: Address,
    val tags: List<Tag>,
    val status: PolicyStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val deletedAt: LocalDateTime?
)
```

---

### 3.2 Spec Objects

Each target has its own spec object in its own file. Spec objects are plain Kotlin `object` declarations annotated with a target-specific annotation.

#### Entity Spec

```kotlin
@EntitySpec(
    for_ = User::class,
    table = "users",
    schema = "public",
    bundles = ["AuditedEntity"],
    bundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    unmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    missingRelationStrategy = MissingRelationStrategy.FAIL,
    annotations = [
        DbAnnotation("jakarta.persistence.Entity"),
        DbAnnotation("org.hibernate.annotations.DynamicUpdate")
    ],
    indexes = [
        Index(columns = ["email"], unique = true),
        Index(columns = ["created_at", "status"])
    ]
)
@EntityField(property = "id",
    annotations = [
        DbAnnotation("jakarta.persistence.Id"),
        DbAnnotation("jakarta.persistence.GeneratedValue",
            members = [AnnotationMember("strategy", "GenerationType.UUID")]
        )
    ]
)
@EntityField(property = "name", column = "full_name")
@EntityField(property = "organisationId",
    transformer = UUIDStringTransformer::class
)
@EntityField(property = "status",
    transformer = PolicyStatusTransformer::class
)
@EntityField(property = "address",
    relation = Relation(
        type = RelationType.ONE_TO_ONE,
        cascade = [CascadeType.ALL],
        joinColumn = "address_id"
    )
)
@EntityField(property = "tags",
    relation = Relation(
        type = RelationType.ONE_TO_MANY,
        mappedBy = "user",
        fetch = FetchType.EAGER
    )
)
object UserEntitySpec
```

#### DTO Spec

```kotlin
@DtoSpec(
    for_ = User::class,
    suffix = "Response",
    prefix = "",
    bundles = ["TimestampedResponse"],
    bundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    unmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    excludedFieldStrategy = ExcludedFieldStrategy.NULLABLE_OVERRIDE
)
@DtoField(property = "name", rename = "fullName")
@DtoField(property = "passwordHash", exclude = true)
@DtoField(property = "createdAt", rename = "created")
@DtoField(property = "updatedAt", rename = "updated")
object UserDtoSpec
```

#### Request Spec

```kotlin
@RequestSpec(for_ = User::class)
@CreateSpec(
    suffix = "CreateRequest",
    validator = CreateUserValidator::class,
    fields = [
        CreateField(property = "name",  rules = [Rule.MinLength(2), Rule.MaxLength(100)]),
        CreateField(property = "email", rules = [Rule.Email, Rule.MaxLength(255)]),
        CreateField(property = "passwordHash", rules = [
            Rule.MinLength(8),
            Rule.Pattern("^(?=.*[A-Z])(?=.*\\d).+$")
        ])
    ]
)
@UpdateSpec(
    suffix = "UpdateRequest",
    partial = true,
    validator = UpdateUserValidator::class,
    fields = [
        UpdateField(property = "name",  rules = [Rule.MinLength(2), Rule.MaxLength(100)]),
        UpdateField(property = "age",   rules = [Rule.Min(0.0),     Rule.Max(150.0)])
    ]
)
object UserRequestSpec
```

---

### 3.3 Full Annotation Definitions

#### Entity Annotations

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntitySpec(
    val for_: KClass<*>,
    val table: String = "",
    val schema: String = "",
    val bundles: Array<String> = [],
    val bundleMergeStrategy: BundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    val unmappedNestedStrategy: UnmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    val missingRelationStrategy: MissingRelationStrategy = MissingRelationStrategy.FAIL,
    val annotations: Array<DbAnnotation> = [],
    val indexes: Array<Index> = []
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class EntityField(
    val property: String,
    val column: String = "",
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val transformerRef: String = "",              // named registry alternative
    val relation: Relation = Relation(RelationType.NONE),
    val annotations: Array<DbAnnotation> = [],
    val inline: Boolean = false,
    val inlinePrefix: String = ""
)
```

#### DTO Annotations

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DtoSpec(
    val for_: KClass<*>,
    val suffix: String = "Dto",
    val prefix: String = "",
    val bundles: Array<String> = [],
    val bundleMergeStrategy: BundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    val unmappedNestedStrategy: UnmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    val excludedFieldStrategy: ExcludedFieldStrategy = ExcludedFieldStrategy.USE_DEFAULT
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class DtoField(
    val property: String,
    val rename: String = "",
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val transformerRef: String = ""
)
```

#### Request Annotations

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RequestSpec(val for_: KClass<*>)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class CreateSpec(
    val suffix: String = "CreateRequest",
    val validator: KClass<out RequestValidator<*>> = NoOpValidator::class,
    val fields: Array<CreateField> = []
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class UpdateSpec(
    val suffix: String = "UpdateRequest",
    val partial: Boolean = true,
    val validator: KClass<out RequestValidator<*>> = NoOpValidator::class,
    val fields: Array<UpdateField> = []
)

annotation class CreateField(
    val property: String,
    val rules: Array<Rule> = [],
    val exclude: Boolean = false
)

annotation class UpdateField(
    val property: String,
    val rules: Array<Rule> = [],
    val exclude: Boolean = false
)
```

#### Shared Supporting Annotations

```kotlin
annotation class DbAnnotation(
    val fqn: String,
    val members: Array<AnnotationMember> = []
)

annotation class AnnotationMember(
    val name: String,
    val value: String
)

annotation class Index(
    val columns: Array<String>,
    val unique: Boolean = false,
    val name: String = ""
)

annotation class Relation(
    val type: RelationType,
    val mappedBy: String = "",
    val cascade: Array<CascadeType> = [],
    val fetch: FetchType = FetchType.LAZY,
    val joinColumn: String = "",
    val joinTable: String = ""
)

annotation class Rule {
    annotation class Required
    annotation class Email
    annotation class NotBlank
    annotation class Positive
    annotation class Past
    annotation class Future
    annotation class MinLength(val value: Int)
    annotation class MaxLength(val value: Int)
    annotation class Min(val value: Double)
    annotation class Max(val value: Double)
    annotation class Pattern(val regex: String, val message: String = "")
    annotation class Custom(val fn: String)
}
```

#### Enums

```kotlin
enum class NullableOverride  { UNSET, YES, NO }
enum class BundleMergeStrategy { SPEC_WINS, BUNDLE_WINS, MERGE_ADDITIVE }
enum class UnmappedNestedStrategy { FAIL, INLINE, EXCLUDE }
enum class MissingRelationStrategy { FAIL, INFER }
enum class ExcludedFieldStrategy { USE_DEFAULT, REQUIRE_MANUAL, NULLABLE_OVERRIDE }
enum class RelationType { NONE, ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }
enum class CascadeType { ALL, PERSIST, MERGE, REMOVE, REFRESH }
enum class FetchType { LAZY, EAGER }
```

---

### 3.4 Bundle Annotations

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntityBundle(val name: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DtoBundle(val name: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RequestBundle(val name: String)

annotation class IncludeBundles(val names: Array<String>)
```

Example bundle definition:

```kotlin
@EntityBundle(name = "AuditedEntity")
@IncludeBundles([])
@DbAnnotation("jakarta.persistence.EntityListeners",
    members = [AnnotationMember("value", "AuditingEntityListener::class")]
)
@EntityField(property = "createdAt",
    annotations = [DbAnnotation("org.springframework.data.annotation.CreatedDate")]
)
@EntityField(property = "updatedAt",
    annotations = [DbAnnotation("org.springframework.data.annotation.LastModifiedDate")]
)
@EntityField(property = "createdBy",
    annotations = [DbAnnotation("org.springframework.data.annotation.CreatedBy")]
)
object AuditedEntityBundle

@EntityBundle(name = "SoftDeletableAuditedEntity")
@IncludeBundles(["AuditedEntity"])
@EntityField(property = "deletedAt",
    annotations = [DbAnnotation("org.hibernate.annotations.SoftDelete")]
)
object SoftDeletableBundle
```

---

## 4. Runtime API

### 4.1 Field Transformer

```kotlin
interface FieldTransformer<Domain, Target> {
    fun toTarget(value: Domain): Target
    fun toDomain(value: Target): Domain
}

// Sentinel — used as default when no transformer specified
class NoOpTransformer : FieldTransformer<Any, Any> {
    override fun toTarget(value: Any) = value
    override fun toDomain(value: Any) = value
}
```

Transformers should be `object` declarations to avoid per-call instantiation:

```kotlin
object UUIDStringTransformer : FieldTransformer<UUID, String> {
    override fun toTarget(value: UUID) = value.toString()
    override fun toDomain(value: String) = UUID.fromString(value)
}

object PolicyStatusTransformer : FieldTransformer<PolicyStatus, String> {
    override fun toTarget(value: PolicyStatus) = value.name.lowercase()
    override fun toDomain(value: String) = PolicyStatus.valueOf(value.uppercase())
}
```

#### Named Transformer Registry

```kotlin
@TransformerRegistry
object AppTransformers {
    @RegisterTransformer("uuid-string")
    val uuidString = UUIDStringTransformer

    @RegisterTransformer("policy-status")
    val policyStatus = PolicyStatusTransformer
}
```

`transformerRef` on a field resolves against this registry at processor time. Build fails if name is not found.

---

### 4.2 Request Validator

```kotlin
interface RequestValidator<T> {
    fun validate(request: T, context: ValidationContext)
}

class NoOpValidator<T> : RequestValidator<T> {
    override fun validate(request: T, context: ValidationContext) = Unit
}
```

```kotlin
class ValidationContext {
    private val errors = mutableListOf<ValidationError>()

    fun require(condition: Boolean, field: String, message: String) {
        if (!condition) errors += ValidationError(field, message)
    }

    fun requireAtLeastOne(vararg fields: FieldRef, message: String) {
        if (fields.none { it.hasValue })
            fields.forEach { errors += ValidationError(it.name, message) }
    }

    fun requireAllOrNone(vararg fields: FieldRef, message: String) {
        val filled = fields.count { it.hasValue }
        if (filled != 0 && filled != fields.size)
            fields.forEach { errors += ValidationError(it.name, message) }
    }

    fun requireIf(condition: Boolean, field: String, message: String) {
        if (condition) require(false, field, message)
    }

    fun build(): ValidationResult =
        if (errors.isEmpty()) ValidationResult.Valid
        else ValidationResult.Invalid(errors)
}

data class FieldRef(val name: String, val hasValue: Boolean)
data class ValidationError(val field: String, val message: String)

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<ValidationError>) : ValidationResult()
}

class ValidationException(val errors: List<ValidationError>) : RuntimeException(
    errors.joinToString { "${it.field}: ${it.message}" }
)
```

---

## 5. Processor Architecture

### 5.1 Internal Models

```kotlin
data class FieldModel(
    val originalName: String,
    val originalType: KSTypeReference,
    val resolvedType: ResolvedType,
    val targetConfigs: Map<SpecTarget, FieldTargetConfig>
)

data class FieldTargetConfig(
    val included: Boolean,
    val name: String,
    val columnName: String,
    val nullable: NullableOverride,
    val transformer: TransformerModel?,
    val relation: Relation?,
    val dbAnnotations: List<DbAnnotation>
)

sealed class ResolvedType {
    data class Primitive(val type: KSType) : ResolvedType()
    data class MappedObject(
        val targetType: String,
        val toTargetFn: String,
        val toDomainFn: String
    ) : ResolvedType()
    data class MappedCollection(
        val targetType: String,
        val toTargetFn: String,
        val toDomainFn: String
    ) : ResolvedType()
}

enum class SpecTarget { ENTITY, DTO, CREATE_REQUEST, UPDATE_REQUEST }
```

### 5.2 Registries

```kotlin
class SpecRegistry {
    val entitySpecs: Map<String, EntitySpecModel> = mutableMapOf()
    val dtoSpecs: Map<String, DtoSpecModel> = mutableMapOf()
    val requestSpecs: Map<String, RequestSpecModel> = mutableMapOf()

    fun hasMappedEntity(ksFqn: String): Boolean
    fun hasMappedDto(ksFqn: String): Boolean
}

class BundleRegistry {
    val entityBundles: Map<String, EntityBundleModel> = mutableMapOf()
    val dtoBundles: Map<String, DtoBundleModel> = mutableMapOf()
}

class TransformerRegistry {
    val transformers: Map<String, TransformerModel> = mutableMapOf()
}
```

### 5.3 Processing Passes

```
Pass 1 — Register transformer registry objects
Pass 2 — Register bundle objects (entity, dto, request)
           → Flatten IncludeBundles inclusions
           → DFS cycle detection on bundle graph
Pass 3 — Register spec objects
           → Resolve bundles, apply merge strategy
           → Validate property name references against domain class (fail on unknown)
Pass 4 — Resolve type graph
           → For each spec, walk all field types
           → Check SpecRegistry for mapped nested types
           → Classify as Primitive / MappedObject / MappedCollection
           → DFS cycle detection on type graph
Pass 5 — Validate
           → MissingRelationStrategy check on MappedObject / MappedCollection fields
           → UnmappedNestedStrategy check on unmapped nested types
           → TransformerRef names resolved against TransformerRegistry
           → ExcludedField + REQUIRE_MANUAL: ensure toDomain skipped not just nulled
Pass 6 — Generate
           → EntityGenerator     → *Entity.kt
           → DtoGenerator        → *Dto / *Response.kt
           → RequestGenerator    → *CreateRequest.kt, *UpdateRequest.kt
           → MapperGenerator     → extension functions for all targets
```

### 5.4 Generator Outputs

#### Entity

- `data class` with all non-excluded fields
- Class-level `@DbAnnotation`s emitted verbatim
- `@Table(name, schema, indexes)` always emitted
- Field-level `@DbAnnotation`s emitted verbatim
- `@Column(name)` emitted when `column` specified
- Relation annotations (`@OneToOne`, `@OneToMany`, etc.) emitted from `Relation`
- `fun Domain.toEntity(): DomainEntity`
- `fun DomainEntity.toDomain(): Domain`
- Transformer calls inserted inline in mapper body

#### DTO

- `data class` with non-excluded fields, renamed where specified
- `NullableOverride` applied per field
- `fun Domain.toDto(): DomainDto`
- `fun DomainDto.toDomain(): Domain` — omitted and replaced with comment when `ExcludedFieldStrategy.REQUIRE_MANUAL`

#### Create Request

- `data class` with non-excluded fields
- Fields with `exclude = true` in `CreateField` dropped
- `init {}` block with generated `require()` calls from `Rule.*` annotations
- `fun validate(): ValidationResult` — runs cross-field validator
- `fun validateOrThrow()` — throws `ValidationException` on invalid

#### Update Request

- Same as create request
- When `partial = true` — all fields are nullable with `= null` defaults
- Only fields listed in `UpdateField` get `init {}` rules

#### Mapper — Nested Type Handling

```kotlin
// MappedObject
address = this.address.toEntity()           // toTarget
address = this.address.toDomain()           // toDomain

// MappedCollection
tags = this.tags.map { it.toEntity() }
tags = this.tags.map { it.toDomain() }

// Transformer
organisationId = UUIDStringTransformer.toTarget(this.organisationId)
organisationId = UUIDStringTransformer.toDomain(this.organisationId)
```

---

## 6. Validation Rules Reference

| Rule | Generated `require()` |
|---|---|
| `Rule.Required` | `value != null && value.isNotBlank()` |
| `Rule.Email` | `value.matches(Regex(EMAIL_REGEX))` |
| `Rule.NotBlank` | `value.isNotBlank()` |
| `Rule.MinLength(n)` | `value.length >= n` |
| `Rule.MaxLength(n)` | `value.length <= n` |
| `Rule.Min(n)` | `value >= n` |
| `Rule.Max(n)` | `value <= n` |
| `Rule.Pattern(r)` | `value.matches(Regex(r))` |
| `Rule.Positive` | `value > 0` |
| `Rule.Past` | `value.isBefore(LocalDateTime.now())` |
| `Rule.Future` | `value.isAfter(LocalDateTime.now())` |
| `Rule.Custom(fn)` | `fn(value)` — fn must be `(T) -> Boolean` |

---

## 7. Error Handling — Build-Time Failures

| Condition | Message |
|---|---|
| `property = "naem"` not found on domain class | `Unknown property 'naem' on User in UserEntitySpec` |
| Bundle name not found | `Unknown entity bundle 'AuditedEntity' on UserEntitySpec` |
| `transformerRef` name not found | `Unknown transformer 'uuid-string' on UserEntitySpec.organisationId` |
| Circular bundle inclusion | `Circular bundle dependency detected: AuditedEntity -> SoftDeletableAuditedEntity -> AuditedEntity` |
| Circular type mapping | `Circular mapping detected: User -> Organisation -> User. Use exclude = true to break the cycle.` |
| Mapped nested type without `Relation` when `FAIL` | `Address is a mapped entity but no Relation declared on User.address in UserEntitySpec` |
| Unmapped nested type when `FAIL` | `Address has no @EntitySpec. Declare one or set unmappedNestedStrategy = INLINE or EXCLUDE` |

---

## 8. Development Order

1. Annotations module — finalise all annotation and enum definitions
2. Runtime module — `FieldTransformer`, `RequestValidator`, `ValidationContext`, result types
3. `ClassResolver` — parse a domain class into `FieldModel` list, no overrides
4. `SpecRegistry` + `BundleRegistry` — registration only, no resolution
5. `EntityGenerator` — data class body only, no mappers
6. `MapperGenerator` — `toEntity()` / `toDomain()` for primitive fields only
7. Field overrides — column rename, exclusion, nullability
8. `TransformerRegistry` + transformer call generation
9. Nested type resolution — `MappedObject`, `MappedCollection`, cycle detection
10. Relation annotation generation
11. `DbAnnotation` and `Index` class-level generation
12. Bundle resolution + merge strategy
13. `DtoGenerator` + dto mappers
14. `RequestGenerator` + `init {}` rule generation + validator wiring
15. Bundle composition (`IncludeBundles`) + bundle cycle detection
16. Error message pass — ensure all failure conditions emit actionable messages
# Full Domain Mapping Annotation Processor — Implementation Plan

## Context

Implements the specification in `000-init-spec.md` using the existing Gradle project structure. A `runtime` module is added alongside the existing `annotations`, `processor`, and `app` modules.

---

## Module Structure

```
codegen-ksp-gradle/
├── annotations/   # annotation + enum definitions (spec §3)
├── processor/     # 6-pass KSP processor (spec §5)
├── runtime/       # FieldTransformer, RequestValidator, ValidationContext (spec §4)
└── app/           # consumer / sample module (spec §3.2 User example)
```

`settings.gradle.kts`: add `include(":runtime")`

`runtime/build.gradle.kts`:
```kotlin
plugins { kotlin("jvm") }
repositories { mavenCentral() }
dependencies { implementation(project(":annotations")) }
```

`app/build.gradle.kts`: add `implementation(project(":runtime"))`

---

## 16-Step Development Order

### Step 1 — `annotations` module: all annotation and enum definitions

Define every annotation and enum from spec §3.3 and §3.4:

- Spec annotations: `EntitySpec`, `EntityField`, `DtoSpec`, `DtoField`, `RequestSpec`, `CreateSpec`, `UpdateSpec`, `CreateField`, `UpdateField`
- Supporting annotations: `DbAnnotation`, `AnnotationMember`, `Index`, `Relation`, `Rule` (nested annotation class with all sub-annotations)
- Bundle annotations: `EntityBundle`, `DtoBundle`, `RequestBundle`, `IncludeBundles`
- Transformer registry annotations: `TransformerRegistry`, `RegisterTransformer`
- Enums: `NullableOverride`, `BundleMergeStrategy`, `UnmappedNestedStrategy`, `MissingRelationStrategy`, `ExcludedFieldStrategy`, `RelationType`, `CascadeType`, `FetchType`

All annotations use `@Retention(AnnotationRetention.SOURCE)` and declare appropriate `@Target`.

---

### Step 2 — `runtime` module: runtime interfaces and types

From spec §4:

- `FieldTransformer<Domain, Target>` interface with `toTarget` / `toDomain`
- `NoOpTransformer : FieldTransformer<Any, Any>` sentinel
- `RequestValidator<T>` interface with `validate(request, context)`
- `NoOpValidator<T>` sentinel
- `ValidationContext` with `require`, `requireAtLeastOne`, `requireAllOrNone`, `requireIf`, `build`
- `FieldRef`, `ValidationError`, `ValidationResult` (sealed: `Valid` / `Invalid`), `ValidationException`

---

### Step 3 — `ClassResolver`: parse domain class into `List<FieldModel>`

Internal processor model (`processor` module):

```kotlin
data class FieldModel(
    val originalName: String,
    val originalType: KSTypeReference,
    val resolvedType: ResolvedType,
    val targetConfigs: Map<SpecTarget, FieldTargetConfig>
)
```

`ClassResolver.resolve(ksCls: KSClassDeclaration): List<FieldModel>`:
- Fail if not a `data class`
- Return raw fields with no bundle/override resolution yet

---

### Step 4 — `SpecRegistry` + `BundleRegistry`: registration only

- Scan `@EntitySpec`, `@DtoSpec`, `@RequestSpec` → register in `SpecRegistry`
- Scan `@EntityBundle`, `@DtoBundle`, `@RequestBundle` → register in `BundleRegistry`
- No resolution yet

---

### Step 5 — `EntityGenerator`: data class body, no mappers

- Emit `data class UserEntity(val field: Type, ...)` for each entity spec
- `@Table(name = "...", schema = "...")`
- Basic field inclusion/exclusion, `@Column(name)` when `column` is set

---

### Step 6 — `MapperGenerator`: primitive fields only

- Emit `fun User.toEntity(): UserEntity { ... }`
- Emit `fun UserEntity.toDomain(): User { ... }`
- Primitive fields only — direct assignment, no transformer calls

---

### Step 7 — Field overrides

- `column` rename → `@Column`
- `exclude = true` → field dropped from generated class and mappers
- `NullableOverride.YES` / `NO` → override Kotlin nullability on generated field type

---

### Step 8 — `TransformerRegistry` + transformer call generation

- Scan `@TransformerRegistry` objects; register each `@RegisterTransformer` entry by name
- For `transformer = XClass::class`: emit `XClass.toTarget(this.field)` in mapper body
- For `transformerRef = "name"`: resolve against registry → fail build if not found
- Fail message: `Unknown transformer 'name' on SpecObject.field`

---

### Step 9 — Nested type resolution + cycle detection

- Walk all field types; check `SpecRegistry` for mapped nested types
- Classify each field as `Primitive`, `MappedObject`, or `MappedCollection`
- DFS cycle detection on the type dependency graph
- Fail message: `Circular mapping detected: A -> B -> A. Use exclude = true to break the cycle.`

---

### Step 10 — Relation annotation generation

Emit JPA/Hibernate relation annotations from `Relation` spec:
- `@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`
- `@JoinColumn(name)` from `joinColumn`
- `@JoinTable(name)` from `joinTable`
- `cascade`, `fetch`, `mappedBy` attributes

---

### Step 11 — `DbAnnotation` + `Index` class-level generation

- Emit arbitrary class-level annotations from `annotations = [DbAnnotation(fqn, members)]`
- Emit `@Table(indexes = [...])`  from `indexes = [Index(columns, unique, name)]`

---

### Step 12 — Bundle resolution + merge strategy

- Flatten `bundles = [...]` against `BundleRegistry`
- Apply `BundleMergeStrategy` (SPEC_WINS / BUNDLE_WINS / MERGE_ADDITIVE)
- Validate all property references in bundle field overrides exist on domain class
- DFS cycle detection on bundle inclusion graph
- Fail message: `Circular bundle dependency detected: A -> B -> A`

---

### Step 13 — `DtoGenerator` + DTO mappers

- Emit `data class UserResponse(...)` with renamed/excluded/nullable-overridden fields
- Emit `fun User.toDto(): UserResponse`
- Emit `fun UserResponse.toDomain(): User` — or emit a comment when `ExcludedFieldStrategy.REQUIRE_MANUAL`

---

### Step 14 — `RequestGenerator` + `init {}` + validator wiring

- Emit `data class UserCreateRequest(...)` with an `init {}` block containing `require()` calls from `Rule.*`
- Emit `fun validate(): ValidationResult` and `fun validateOrThrow()`
- For `UpdateRequest` with `partial = true`: all fields become nullable with `= null` defaults

Validation rule → `require()` mapping from spec §6 (MinLength, Email, Pattern, etc.)

---

### Step 15 — Bundle composition (`IncludeBundles`)

- Resolve transitive `IncludeBundles` inclusions
- Merge fields DFS-order; apply merge strategy at each level
- Detect and fail on circular inclusions

---

### Step 16 — Error message pass

Ensure every failure condition from spec §7 produces an actionable `logger.error()` with the exact message format:

| Condition | Message format |
|---|---|
| Unknown property on domain class | `Unknown property 'naem' on User in UserEntitySpec` |
| Unknown bundle name | `Unknown entity bundle 'Foo' on UserEntitySpec` |
| Unknown transformerRef | `Unknown transformer 'name' on UserEntitySpec.field` |
| Circular bundle inclusion | `Circular bundle dependency detected: A -> B -> A` |
| Circular type mapping | `Circular mapping detected: A -> B -> A. Use exclude = true to break the cycle.` |
| Mapped nested without Relation (FAIL) | `Address is a mapped entity but no Relation declared on User.address in UserEntitySpec` |
| Unmapped nested type (FAIL) | `Address has no @EntitySpec. Declare one or set unmappedNestedStrategy = INLINE or EXCLUDE` |

---

## Sample Domain in `app/`

Use the `User` domain class from spec §3.1 with `UserEntitySpec`, `UserDtoSpec`, and `UserRequestSpec` from spec §3.2. This drives the end-to-end test for all 16 steps.

---

## Verification

After each step:
```bash
./gradlew :app:kspKotlin   # processor runs; inspect generated files
```

After all steps:
```bash
./gradlew :app:run
# app/src/main/kotlin/Main.kt creates a User, calls .toEntity(), .toDto(), .toCreateRequest(), prints results
```

Generated files appear in:
```
app/build/generated/ksp/main/kotlin/
├── UserEntity.kt
├── UserResponse.kt
├── UserCreateRequest.kt
├── UserUpdateRequest.kt
└── UserMappers.kt
```

# Full Domain Mapping Annotation Processor — Implementation Plan

## Context

Implements the specification in `000-init-spec.md` using the existing Gradle project structure. A `runtime` module is added alongside the existing `annotations`, `processor`, and `app` modules. Annotations are already fully defined — work begins with module wiring and immediately moves into KotlinPoet-based code generation. Every step after the first produces testable generated output; a step is complete only when its generated code can be compiled and verified.

---

## Module Structure

```
codegen-ksp-gradle/
├── annotations/   # annotation + enum definitions (already complete)
├── processor/     # 16-step KSP processor (DomainMappingProcessorProvider)
├── runtime/       # FieldTransformer, RequestValidator, ValidationContext
└── app/           # consumer / sample module
```

**Files to touch for module wiring:**

- `settings.gradle.kts` — add `include(":runtime")`
- `runtime/build.gradle.kts` — new file:
  ```kotlin
  plugins { kotlin("jvm") }
  repositories { mavenCentral() }
  dependencies { implementation(project(":annotations")) }
  ```
- `processor/build.gradle.kts` — add KotlinPoet dependency:
  ```kotlin
  implementation("com.squareup:kotlinpoet:2.3.0")
  implementation("com.squareup:kotlinpoet-ksp:2.3.0")
  ```
- `app/build.gradle.kts` — add `implementation(project(":runtime"))`

---

## Implementation Checklist

### Step 1 — Module wiring + `DomainMappingProcessor` scaffold

- [x] Add `:runtime` to `settings.gradle.kts`
- [x] Create `runtime/build.gradle.kts`
- [x] Add KotlinPoet to `processor/build.gradle.kts`
- [x] Add `implementation(project(":runtime"))` to `app/build.gradle.kts`
- [x] Create `DomainMappingProcessorProvider` with inline anonymous `SymbolProcessor` (scans `@EntitySpec`; no output yet)
- [x] Register provider in `META-INF/services/`

**Verify:**
```bash
./gradlew :app:kspKotlin   # must compile; existing GeneratedHelloWorld.kt still emitted
```

---

### Step 2 — KotlinPoet: generate empty `Entity` data class

First KotlinPoet output. `EntityGenerator` emits a bare `data class UserEntity()` for each `@EntitySpec`.

- [x] Implement `EntityGenerator.generate(spec, env)` using KotlinPoet `TypeSpec.classBuilder`
- [x] Add `UserEntitySpec` object annotated with `@EntitySpec(for_ = User::class, table = "users")` to `app/`
- [x] Add `User` domain data class to `app/`
- [x] Wire `EntityGenerator` in `DomainMappingProcessorProvider` (inline object) `.process()`

**Verify:**
```bash
./gradlew :app:kspKotlin
# app/build/generated/ksp/main/kotlin/UserEntity.kt must exist
# data class UserEntity()
```

---

### Step 3 — `ClassResolver`: domain class fields → entity constructor params

`ClassResolver.resolve(ksCls)` reads primary constructor parameters and returns a `List<FieldModel>`. `EntityGenerator` uses these to emit a fully-populated constructor.

- [x] Define `FieldModel(originalName, originalType, resolvedType, targetConfigs)`
- [x] Implement `ClassResolver.resolve()` — fail if not a `data class`
- [x] `EntityGenerator` emits `data class UserEntity(val id: Long, val name: String, ...)`

**Verify:**
```bash
./gradlew :app:kspKotlin
# UserEntity.kt must have all fields from User's primary constructor
```

---

### Step 4 — Field overrides: `@Column`, `exclude`, `NullableOverride` + `@Table`

- [x] Apply `column` → emit `@Column(name = "...")` on field
- [x] Apply `exclude = true` → field absent from generated class
- [x] Apply `NullableOverride.YES / NO` → override Kotlin nullability on generated field type
- [x] Emit `@Table(name = "...", schema = "...")` on entity class from `EntitySpec.table/schema`
- [x] Add `@EntityField` overrides to `UserEntitySpec` in `app/` to exercise all three cases

**Verify:**
```bash
./gradlew :app:kspKotlin
# @Table on UserEntity
# @Column(name="user_name") on renamed field
# excluded field absent from UserEntity constructor
```

---

### Step 5 — `MapperGenerator`: `User.toEntity()` + `UserEntity.toDomain()` (primitives)

- [x] Implement `MapperGenerator` using KotlinPoet `FunSpec.builder` as extension functions
- [x] Emit `fun User.toEntity(): UserEntity = UserEntity(field = this.field, ...)`
- [x] Emit `fun UserEntity.toDomain(): User = User(field = this.field, ...)`
- [x] Primitive/excluded fields handled; excluded fields use default or null

**Verify:**
```bash
./gradlew :app:kspKotlin
# app/build/generated/ksp/main/kotlin/UserMappers.kt exists
# both extension functions present with correct field assignments
./gradlew :app:run   # Main.kt calls user.toEntity() — must compile and run
```

---

### Step 6 — `DtoGenerator`: `UserResponse` + DTO mappers

- [x] Scan `@DtoSpec`; implement `DtoGenerator` (mirrors `EntityGenerator`)
- [x] Apply `rename`, `exclude`, `NullableOverride` on DTO fields
- [x] Emit `fun User.toDto(): UserResponse` and `fun UserResponse.toDomain(): User`
- [x] Add `UserDtoSpec` to `app/` with at least one renamed and one excluded field
- [x] Respect `ExcludedFieldStrategy.REQUIRE_MANUAL` → emit `TODO("manual mapping required")` comment

**Verify:**
```bash
./gradlew :app:kspKotlin
# UserResponse.kt generated with renamed field, excluded field absent
# toDto() and toDomain() in UserMappers.kt (or separate DtoMappers.kt)
```

---

### Step 7 — `RequestGenerator`: `UserCreateRequest` + `init {}` validation

- [x] Scan `@RequestSpec` + `@CreateSpec`; implement `RequestGenerator`
- [x] Emit `data class UserCreateRequest(...)` with `init {}` containing `require()` calls from `Rule.*`
- [x] Rule → require mapping: `MinLength(n)` → `require(field.length >= n)`, `Email` → `require(field.contains("@"))`, `NotBlank` → `require(field.isNotBlank())`, etc.
- [x] Add `UserRequestSpec` to `app/` with at least two rules

**Verify:**
```bash
./gradlew :app:kspKotlin
# UserCreateRequest.kt generated
# init {} block with require() calls present
```

---

### Step 8 — `UpdateRequest` with `partial = true`

- [x] Extend `RequestGenerator` to emit `UserUpdateRequest` from `@UpdateSpec`
- [x] When `partial = true`: all fields become `val field: Type? = null`
- [x] Apply `UpdateField` rules the same as `CreateField`

**Verify:**
```bash
./gradlew :app:kspKotlin
# UserUpdateRequest.kt: every field is nullable with = null default
```

---

### Step 9 — `TransformerRegistry` + transformer calls in mappers

- [x] Scan `@TransformerRegistry` objects; register each `@RegisterTransformer(name)` entry
- [x] For `transformer = XClass::class`: emit `XClass().toTarget(this.field)` in mapper body
- [x] For `transformerRef = "name"`: resolve against registry → `logger.error(...)` + return if not found
  - Error: `Unknown transformer 'name' on UserEntitySpec.field`
- [x] Add a sample transformer + registry entry to `app/`

**Verify:**
```bash
./gradlew :app:kspKotlin
# Mapper body contains transformer call for transformed field
# Providing an unknown transformerRef fails the build with the correct message
```

---

### Step 10 — Nested type resolution + cycle detection

- [ ] Walk all `FieldModel` types; check `SpecRegistry` for mapped nested types
- [ ] Classify each field as `Primitive`, `MappedObject`, or `MappedCollection`
- [ ] Use the target-type name (`UserEntity`) as the field type when the nested domain type is mapped
- [ ] DFS cycle detection on type dependency graph
  - Error: `Circular mapping detected: A -> B -> A. Use exclude = true to break the cycle.`
- [ ] Implement `UnmappedNestedStrategy.FAIL / INLINE / EXCLUDE` logic

**Verify:**
```bash
./gradlew :app:kspKotlin
# Nested mapped field uses entity type (e.g., AddressEntity) in UserEntity
# Introducing a cycle in test specs fails the build with correct message
```

---

### Step 11 — Relation annotations (`@OneToOne`, `@JoinColumn`, etc.)

- [ ] Emit JPA/Hibernate relation annotations from `Relation` spec on entity fields:
  - `@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`
  - `@JoinColumn(name)` from `joinColumn`
  - `@JoinTable(name)` from `joinTable`
  - `cascade`, `fetch`, `mappedBy` attributes
- [ ] Add a `Relation` on an `@EntityField` in `UserEntitySpec`

**Verify:**
```bash
./gradlew :app:kspKotlin
# @OneToMany(cascade=[...], fetch=LAZY) present on the relational field in UserEntity.kt
```

---

### Step 12 — `DbAnnotation` + `Index` class-level generation

- [ ] Emit arbitrary class-level annotations from `annotations = [DbAnnotation(fqn, members)]`
- [ ] Emit `@Table(indexes = [Index(columns=[...], unique, name)])` from `indexes` in `EntitySpec`
- [ ] Add `DbAnnotation` + `Index` entries to `UserEntitySpec` in `app/`

**Verify:**
```bash
./gradlew :app:kspKotlin
# Custom annotation and @Table(indexes=[...]) present on UserEntity class
```

---

### Step 13 — `SpecRegistry` + `BundleRegistry` + bundle resolution

- [ ] `SpecRegistry`: register all `@EntitySpec`, `@DtoSpec`, `@RequestSpec` by canonical name
- [ ] `BundleRegistry`: register all `@EntityBundle`, `@DtoBundle`, `@RequestBundle`
- [ ] Flatten `bundles = [...]` from specs against `BundleRegistry`
- [ ] Apply `BundleMergeStrategy` (SPEC_WINS / BUNDLE_WINS / MERGE_ADDITIVE)
- [ ] Validate all property references in bundle field overrides exist on domain class
  - Error: `Unknown property 'naem' on User in UserEntitySpec`
  - Error: `Unknown entity bundle 'Foo' on UserEntitySpec`
- [ ] Add an `EntityBundle` + reference it from `UserEntitySpec` in `app/`

**Verify:**
```bash
./gradlew :app:kspKotlin
# Bundle fields merged into UserEntity according to merge strategy
# Referencing an unknown bundle fails build with correct message
```

---

### Step 14 — `IncludeBundles` transitive resolution

- [ ] Resolve transitive `IncludeBundles` inclusions DFS-order
- [ ] Apply merge strategy at each level
- [ ] DFS cycle detection on bundle inclusion graph
  - Error: `Circular bundle dependency detected: A -> B -> A`

**Verify:**
```bash
./gradlew :app:kspKotlin
# Two-level bundle inclusion produces correct merged fields
# Cyclic bundle inclusion fails build with correct message
```

---

### Step 15 — `runtime` module: `ValidationContext`, `ValidationResult`, `validate()` wiring

- [ ] Move `FieldTransformer` / `NoOpTransformer` / `RequestValidator` / `NoOpValidator` from `annotations` to `runtime`
- [ ] Add `ValidationContext` with `require`, `requireAtLeastOne`, `requireAllOrNone`, `requireIf`, `build`
- [ ] Add `FieldRef`, `ValidationError`, `ValidationResult` (sealed: `Valid`/`Invalid`), `ValidationException`
- [ ] `RequestGenerator` emits `fun validate(): ValidationResult` and `fun validateOrThrow()` on request classes

**Verify:**
```bash
./gradlew :app:kspKotlin
# UserCreateRequest.validate() compiles and returns ValidationResult
./gradlew :app:run
# Main.kt creates a UserCreateRequest, calls validate(), prints result
```

---

### Step 16 — Error message audit pass

Ensure every failure condition in spec §7 produces an actionable `logger.error()` with the exact format:

| Condition | Message |
|---|---|
| Unknown property | `Unknown property 'naem' on User in UserEntitySpec` |
| Unknown bundle | `Unknown entity bundle 'Foo' on UserEntitySpec` |
| Unknown transformerRef | `Unknown transformer 'name' on UserEntitySpec.field` |
| Circular bundle | `Circular bundle dependency detected: A -> B -> A` |
| Circular type mapping | `Circular mapping detected: A -> B -> A. Use exclude = true to break the cycle.` |
| Mapped nested, no Relation (FAIL) | `Address is a mapped entity but no Relation declared on User.address in UserEntitySpec` |
| Unmapped nested type (FAIL) | `Address has no @EntitySpec. Declare one or set unmappedNestedStrategy = INLINE or EXCLUDE` |

- [ ] Audit every `logger.error()` call against the table above
- [ ] Add/fix any missing or incorrect messages
- [ ] Introduce intentionally broken specs to `app/` (in a separate test object) to exercise each message

**Verify:**
```bash
# For each broken spec, ./gradlew :app:kspKotlin must fail with the exact message above
```

---

## Sample Domain in `app/`

Use the `User` domain class from spec §3.1 with `UserEntitySpec`, `UserDtoSpec`, and `UserRequestSpec` from spec §3.2. This drives the end-to-end test for all 16 steps.

---

## Final End-to-End Verification

```bash
./gradlew :app:run
# Main.kt creates a User, calls .toEntity(), .toDto(), .toCreateRequest(), .validate(), prints results
```

Generated files in `app/build/generated/ksp/main/kotlin/`:
```
UserEntity.kt
UserResponse.kt
UserCreateRequest.kt
UserUpdateRequest.kt
UserMappers.kt
```

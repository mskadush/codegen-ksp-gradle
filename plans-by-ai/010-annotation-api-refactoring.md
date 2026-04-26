# Plan 010: Annotation API Refactoring

**Created**: 2026-04-21
**Context**: TODO.md captures annotation API improvements to implement before steps 13–16 of plan 003.
Goals: (1) collapse all output-class spec annotations into a single repeatable `@ClassSpec`;
(2) unify field overrides into `@ClassField` (all outputs) + `@FieldSpec` (per-output);
(3) rename `DbAnnotation` → `CustomAnnotation` with ergonomic string members;
(4) make validation rules consumer-defined via `@RuleExpression`.

---

## Checklist

- [x] Step 1 — Add `@RuleExpression`; annotate existing `Rule.*` built-ins
- [x] Step 2 — Rename `DbAnnotation` → `CustomAnnotation`; replace `AnnotationMember` with `Array<String>`
- [x] Step 3 — New file: `@ClassSpec` (repeatable) + `@ClassField` + `@FieldSpec`
- [x] Step 4 — Delete old specific spec/field annotations
- [x] Step 5 — Update processor: new scan target, merged field model, output-kind inference, rule codegen
- [x] Step 6 — Update `app/` sample specs to new API
- [x] Step 7 — End-to-end verification (`./gradlew :app:run` — BUILD SUCCESSFUL)

---

## Design Summary

### Annotation roles

| Annotation | Repeatable | Scope | Purpose |
|---|---|---|---|
| `@ClassSpec` | yes | spec class | One instance per generated output class; suffix is the discriminator |
| `@ClassField` | yes | spec class | Field override applied to **all** `@ClassSpec` instances on this class |
| `@FieldSpec` | yes | spec class | Field override scoped to **one or more** named `@ClassSpec` instances via `for_` |

### `@ClassField` vs `@FieldSpec`
- `@ClassField` — only shared params (exclude, nullable, transformer, annotations). Intent is explicit: "this applies everywhere."
- `@FieldSpec` — flat with all output-kind params. `for_: Array<String>` targets one or more suffixes; `FieldSpec` params win over `ClassField` for the same property.

### Output-kind inference (processor)
```
val hasRules = fieldSpecs(suffix).values.any { it.rules.isNotEmpty() }
kind = when {
    hasRules && spec.partial -> UPDATE_REQUEST
    hasRules                 -> CREATE_REQUEST
    spec.partial             -> UPDATE_REQUEST
    else                     -> DATA_CLASS       // entity or DTO; no further distinction needed
}
```

### Example spec class (app/)
```kotlin
@ClassSpec(for_ = User::class, suffix = "Entity",
           annotations = [CustomAnnotation(Entity::class),
                          CustomAnnotation(Table::class, members = ["name=\"users\""])])
@ClassSpec(for_ = User::class, suffix = "Response")
@ClassSpec(for_ = User::class, suffix = "CreateRequest")
@ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true)

@ClassField(property = "passwordHash", exclude = true)               // excluded from all outputs

@FieldSpec(for_ = ["Entity"],        property = "createdAt", column = "created_at")
@FieldSpec(for_ = ["Response"],      property = "createdAt", rename = "updatedAt")
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"],
           property = "email",
           rules   = [Rule.Email::class, Rule.NotBlank::class])
class UserSpec
```

---

## Step 1 — `@RuleExpression` meta-annotation

**File**: `annotations/src/main/kotlin/com/example/annotations/CustomAnnotation.kt`

```kotlin
/**
 * Marks an annotation class as a validation rule.
 * `{field}` in [expression] is replaced by the actual field name when the processor emits
 * `require(...)`. Parameterised rules (e.g. [Rule.MinLength]) may use the names of their
 * own annotation parameters as additional placeholders (e.g. `{value}`).
 *
 * Example — consumer-defined rule:
 * ```kotlin
 * @RuleExpression("{field}.startsWith(\"ACM-\")")
 * annotation class AcmPrefix
 * ```
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RuleExpression(val expression: String)
```

Annotate every existing `Rule.*` nested class:

| Rule | `@RuleExpression` expression |
|---|---|
| `Required` | `{field} != null` |
| `Email` | `{field}.contains("@")` |
| `NotBlank` | `{field}.isNotBlank()` |
| `Positive` | `{field} > 0` |
| `Past` | `{field}.isBefore(java.time.LocalDate.now())` |
| `Future` | `{field}.isAfter(java.time.LocalDate.now())` |
| `MinLength(value)` | `{field}.length >= {value}` |
| `MaxLength(value)` | `{field}.length <= {value}` |
| `Min(value)` | `{field} >= {value}` |
| `Max(value)` | `{field} <= {value}` |
| `Pattern(regex)` | `Regex("{regex}").matches({field})` |
| `Custom(fn)` | processor calls `fn` reference directly (special-cased) |

Processor resolution: for each rule `KClass` in `FieldSpec.rules`, read `@RuleExpression.expression`
from the rule class's annotations, then replace `{field}` with the field name, and replace
`{paramName}` placeholders with the corresponding parameter value from the rule annotation instance
at the call site.

---

## Step 2 — `DbAnnotation` → `CustomAnnotation`; members as `Array<String>`

**Files**:
- `annotations/src/main/kotlin/com/example/annotations/CustomAnnotation.kt`
- `annotations/build.gradle.kts`
- All files referencing `DbAnnotation` or `AnnotationMember`

### API

```kotlin
annotation class CustomAnnotation(
    val annotation: KClass<out Annotation>,
    @Language("kotlin") val members: Array<String> = []
    // Each entry: "paramName=literal"   e.g. "name=\"users\""
    //          or "paramName=ENUM_SHORT" e.g. "fetch=LAZY"
)
```

Add to `annotations/build.gradle.kts`:
```kotlin
compileOnly("org.jetbrains:annotations:24.1.0")
```

`AnnotationMember` class is deleted.

### Enum short-name resolution (processor, `KspAnnotationExtensions.kt`)

For each `"paramName=VALUE"` member string when emitting a `CustomAnnotation`:
1. Resolve the annotation `KSType` (already done, step 11).
2. Look up the annotation class's parameter `paramName` via `KSClassDeclaration.primaryConstructor.parameters`.
3. If the parameter type is an enum, find `VALUE` among the enum's constants → obtain FQN.
4. Call `fileSpecBuilder.addImport(enumPkg, enumName)` on the `FileSpec.Builder`.
5. Emit via KotlinPoet: `AnnotationSpec.Builder.addMember("paramName = %T.VALUE", enumClassName)`.

---

## Step 3 — `@ClassSpec`, `@ClassField`, `@FieldSpec`

**New file**: `annotations/src/main/kotlin/com/example/annotations/ClassSpec.kt`

### `@ClassSpec`

```kotlin
/**
 * Drives generation of a single output class from a domain type.
 *
 * Apply multiple `@ClassSpec` annotations to the same spec class to generate several output
 * classes from one domain type. Each instance is uniquely identified by [suffix], which is
 * used by [FieldSpec.for_] to scope field overrides.
 *
 * Output-kind inference (processor):
 * - Any [FieldSpec] scoped to this suffix contains non-empty [rules]  → request class (init {})
 * - [partial] = true                                                  → all fields nullable + = null
 * - Otherwise                                                         → plain data class + mappers
 *
 * @param for_                   Domain class to generate from.
 * @param suffix                 Appended to the domain class name to form the output class name.
 * @param prefix                 Prepended to the domain class name.
 * @param partial                All generated fields are nullable with `= null` defaults.
 * @param bundles                Named bundle classes merged into this spec.
 * @param bundleMergeStrategy    Conflict-resolution when spec and bundle configure the same field.
 * @param unmappedNestedStrategy What to do when a nested domain type has no explicit mapping.
 * @param excludedFieldStrategy  How to treat excluded fields in the output class.
 * @param annotations            Annotations forwarded to the generated class (e.g. `@Entity`).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class ClassSpec(
    val for_: KClass<*>,
    val suffix: String = "",
    val prefix: String = "",
    val partial: Boolean = false,
    val bundles: Array<String> = [],
    val bundleMergeStrategy: BundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    val unmappedNestedStrategy: UnmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    val excludedFieldStrategy: ExcludedFieldStrategy = ExcludedFieldStrategy.USE_DEFAULT,
    val annotations: Array<CustomAnnotation> = []
)
```

### `@ClassField`

```kotlin
/**
 * Shared field override applied to **every** output class generated by this spec class.
 *
 * Only carries params that are meaningful for all output kinds (exclude, nullability,
 * transformer, pass-through annotations). Use [FieldSpec] for output-kind-specific params
 * (column name, rename, validation rules) or to target a subset of output classes.
 *
 * [FieldSpec] params win over [ClassField] for the same [property].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class ClassField(
    val property: String,
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val transformerRef: String = "",
    val annotations: Array<CustomAnnotation> = []
)
```

### `@FieldSpec`

```kotlin
/**
 * Per-output-class field override.
 *
 * [for_] lists the [ClassSpec.suffix] values this override applies to. Targeting multiple
 * suffixes avoids duplicating identical rules across CreateRequest and UpdateRequest.
 *
 * Flat design — all output-kind params coexist; the processor ignores inapplicable ones:
 * - Entity: [column], [inline], [inlinePrefix]
 * - DTO:    [rename]
 * - Request: [rules] (each rule class must be annotated with [@RuleExpression])
 *
 * [FieldSpec] params win over [ClassField] for the same [property] + suffix combination.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class FieldSpec(
    val for_: Array<String>,          // must match one or more ClassSpec.suffix values
    val property: String,
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val transformerRef: String = "",
    val annotations: Array<CustomAnnotation> = [],
    // Entity-specific
    val column: String = "",
    val inline: Boolean = false,
    val inlinePrefix: String = "",
    // DTO-specific
    val rename: String = "",
    // Request-specific
    val rules: Array<KClass<out Annotation>> = []
)
```

---

## Step 4 — Remove old specific annotations

| File | Action |
|---|---|
| `annotations/.../EntityAnnotations.kt` | Delete `EntitySpec`, `EntityField`; file can be deleted |
| `annotations/.../DtoAnnotations.kt` | Delete `DtoSpec`, `DtoField`; file can be deleted |
| `annotations/.../RequestAnnotations.kt` | Delete `RequestSpec`, `CreateSpec`, `UpdateSpec`, `CreateField`, `UpdateField`; file can be deleted |
| `annotations/.../CustomAnnotation.kt` | Delete `AnnotationMember` |
| `annotations/.../IncludeBundles.kt` | **Keep as-is** — bundle consolidation is a later task |

---

## Step 5 — Update processor

### 5a — New scan target (`DomainMappingProcessorProvider.kt`)

Replace scan of `@EntitySpec` / `@DtoSpec` / `@RequestSpec` with a single scan of `@ClassSpec`.
A declaration carrying N `@ClassSpec` annotations triggers N generation calls.

### 5b — `KspAnnotationExtensions.kt` additions

```kotlin
// All @ClassSpec instances on a declaration
fun KSClassDeclaration.classSpecs(): List<ClassSpecModel>

// All @ClassField on a declaration, keyed by property
fun KSClassDeclaration.classFields(): Map<String, ClassFieldModel>

// All @FieldSpec matching a given suffix, keyed by property
fun KSClassDeclaration.fieldSpecsFor(suffix: String): Map<String, FieldSpecModel>

// Merged view: ClassField base, FieldSpec overrides on top
fun KSClassDeclaration.mergedFields(suffix: String): Map<String, MergedFieldModel>

// Updated CustomAnnotation emitter: parses "k=V" strings, resolves enum FQN, calls addImport()
fun customAnnotationSpecs(
    ksAnnotations: List<KSAnnotation>,
    fileBuilder: FileSpec.Builder
): List<AnnotationSpec>
```

### 5c — Generator routing

| Kind | Generated class | Has `init {}` | All fields nullable |
|---|---|---|---|
| `DATA_CLASS` | plain data class | no | no |
| `CREATE_REQUEST` | data class | yes (requires) | no |
| `UPDATE_REQUEST` | data class | yes (requires, if rules present) | yes |

Mapper functions for `DATA_CLASS` outputs only:
- `fun User.to${suffix}(): User${suffix}` (forward)
- `fun User${suffix}.toDomain(): User` (reverse)

### 5d — Rule codegen in request classes (`RequestGenerator.kt` or unified generator)

```kotlin
// For each field with non-empty rules:
fieldSpec.rules.forEach { ruleKClass ->
    val exprTemplate = ruleKClass.annotations
        .first { it is RuleExpression }.expression  // read via KSP

    val expr = exprTemplate
        .replace("{field}", fieldName)
        .resolveParams(ruleAnnotationInstance)  // replace {value}, {regex}, etc.

    initBlock.addStatement("require(%L)", expr)
}
```

`Custom(fn)` is special-cased: emit a call to the FQN function reference stored in `fn`.

---

## Step 6 — Update `app/` sample specs

Consolidate `UserEntitySpec.kt`, `UserDtoSpec.kt`, `UserRequestSpec.kt` → single `UserSpec.kt`.

```kotlin
@ClassSpec(
    for_ = User::class, suffix = "Entity",
    annotations = [
        CustomAnnotation(Entity::class),
        CustomAnnotation(Table::class, members = ["name=\"users\"", "schema=\"public\""])
    ]
)
@ClassSpec(for_ = User::class, suffix = "Response")
@ClassSpec(for_ = User::class, suffix = "CreateRequest")
@ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true)

@ClassField(property = "passwordHash", exclude = true)

@FieldSpec(for_ = ["Entity"],   property = "createdAt", column = "created_at")
@FieldSpec(for_ = ["Response"], property = "createdAt", rename = "updatedAt")
@FieldSpec(
    for_  = ["CreateRequest", "UpdateRequest"],
    property = "email",
    rules = [Rule.Email::class, Rule.NotBlank::class]
)
class UserSpec
```

Add consumer-defined rule:
```kotlin
@RuleExpression("{field}.startsWith(\"ACM-\")")
annotation class AcmPrefix
```

Update `app/Main.kt` mapper call sites: `user.toEntity()` → `user.toEntity()` (same suffix,
unchanged); `user.toDto()` → `user.toResponse()` (suffix change).

---

## Step 7 — End-to-end verification

```bash
./gradlew :app:kspKotlin
# UserEntity.kt       — @Entity, @Table(name="users") present; toEntity()/toDomain() mappers
# UserResponse.kt     — Jackson annotations; toResponse()/toDomain()
# UserCreateRequest.kt — init {} with require() from @RuleExpression
# UserUpdateRequest.kt — all fields nullable; init {} if rules present

./gradlew :app:run
# Main.kt end-to-end: user.toEntity(), user.toResponse(), UserCreateRequest(...).validate()
```

---

## Critical Files

| File | Change |
|---|---|
| `annotations/.../CustomAnnotation.kt` | Add `@RuleExpression`; rename `DbAnnotation` → `CustomAnnotation`; delete `AnnotationMember`; annotate `Rule.*` |
| `annotations/.../ClassSpec.kt` | **New** — `@ClassSpec`, `@ClassField`, `@FieldSpec` |
| `annotations/.../EntityAnnotations.kt` | Delete |
| `annotations/.../DtoAnnotations.kt` | Delete |
| `annotations/.../RequestAnnotations.kt` | Delete |
| `annotations/build.gradle.kts` | Add `compileOnly("org.jetbrains:annotations:24.1.0")` |
| `processor/.../KspAnnotationExtensions.kt` | Merged-field readers; string-member parser; enum FQN + import resolution |
| `processor/.../DomainMappingProcessorProvider.kt` | Scan `@ClassSpec`; route each instance to generator |
| `processor/.../EntityGenerator.kt` | Update to read merged `ClassSpec + ClassField + FieldSpec` model |
| `processor/.../DtoGenerator.kt` | Same |
| `processor/.../RequestGenerator.kt` | `@RuleExpression`-driven require codegen |
| `app/.../*Spec.kt` → `UserSpec.kt` | Consolidate; new API |
| `app/.../Main.kt` | Update mapper call sites for suffix-derived names |

---

## Risks / Notes

- **Breaking**: all old spec/field annotations removed. Every spec class in `app/` must be rewritten.
- **Mapper naming**: `toDto()` → `toResponse()` (or whatever suffix is used). Callers in `Main.kt` must update.
- **`@Language("kotlin")`** on `members: Array<String>` is IDE-only — no compile-time format validation.
- **Import management**: enum FQN resolution in the processor must call `addImport()` — missing imports cause silent compile errors in generated code.
- **Bundle annotations** (`EntityBundle`, `DtoBundle`, `RequestBundle`) are kept as-is; consolidation deferred.
- **`FieldSpec.for_` as `Array<String>`**: Kotlin annotations do not allow `vararg` as a parameter name `for_`; declare it as `val for_: Array<String>`.

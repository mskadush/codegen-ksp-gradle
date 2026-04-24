# Plan: Add Extra Fields to Generated Classes

## Context

All fields in generated classes currently come from the domain class's primary constructor
parameters. There is no way to inject additional fields into a specific generated output that
don't exist in the domain. This plan adds an `@ExtraField` annotation that lets users declare
synthetic fields on individual generated outputs (e.g. a `createdAt: Instant` on an `Entity`
class that has no counterpart in the domain model).

## Checklist

- [ ] 1. Add `@ExtraField` annotation to `annotations` module
- [ ] 2. Add `AN_EXTRA_FIELD`, `FQN_EXTRA_FIELD`, and property name constants to `AnnotationConstants.kt`
- [ ] 3. Emit extra fields in `ClassGenerator` after the domain-derived fields loop
- [ ] 4. Validate extra fields in `DomainMappingProcessorProvider` (PASS 1d)
- [ ] 5. Add example usage in `UserSpec.kt`

---

## Annotation design

New repeatable annotation placed on spec classes alongside `@ClassField` / `@FieldSpec`:

```kotlin
// annotations/ClassAnnotations.kt
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class ExtraField(
    val for_: Array<String>,                 // suffixes this field applies to
    val name: String,                        // field name in the generated class
    val type: KClass<*>,                     // field type (simple/non-generic types)
    val nullable: Boolean = false,           // whether the type is nullable
    val defaultValue: String = "",           // optional default value expression
    val annotations: Array<CustomAnnotation> = [],
)
```

**Rules enforced at compile time (PASS 1d):**
- Non-partial, non-nullable extra fields with no `defaultValue` → processor error.
  The mapper cannot synthesize a value for them; users must provide a default.
- Non-partial, nullable extra fields with no `defaultValue` → implicitly default to `null`.

**Mapper behaviour:**
Extra fields that have a `defaultValue` (or are nullable with implicit `= null`) get a
default argument in the constructor. The mapper call therefore **omits** them — Kotlin fills
the default. No changes are required in `MapperGenerator`.

---

## Step-by-step

### Step 1 — `annotations/ClassAnnotations.kt`

Append `@ExtraField` after the existing `@FieldSpec` declaration.

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class ExtraField(
    val for_: Array<String>,
    val name: String,
    val type: KClass<*>,
    val nullable: Boolean = false,
    val defaultValue: String = "",
    val annotations: Array<CustomAnnotation> = [],
)
```

---

### Step 2 — `processor/AnnotationConstants.kt`

```kotlin
internal val AN_EXTRA_FIELD      = ExtraField::class.simpleName!!
internal val AN_EXTRA_FIELDS     = ExtraField::class.simpleName!! + "s"  // @Repeatable container
internal val FQN_EXTRA_FIELD     = ExtraField::class.qualifiedName!!

// ExtraField property names
internal val PROP_EXTRA_NAME     = ExtraField::name.name
internal val PROP_EXTRA_TYPE     = ExtraField::type.name
internal val PROP_EXTRA_NULLABLE = ExtraField::nullable.name
internal val PROP_EXTRA_DEFAULT  = ExtraField::defaultValue.name
```

(`PROP_FOR`, `PROP_ANNOTATIONS` are already defined and reused.)

---

### Step 3 — `ClassGenerator.kt`

After the existing domain-field loop (after `classBuilder.primaryConstructor(ctorBuilder.build())`
is set up, but before calling `primaryConstructor`), add a second loop that reads
`@ExtraField` annotations from the spec class filtered to `model.suffix`.

**Logic per extra field:**
- Resolve `KClass<*>` argument → `ClassName` via its qualified name
- Apply `nullable` flag and, for partial outputs, force-nullable the type
- If `partial`: emit `paramName: Type? = null`
- If not partial:
  - nullable + no default → emit `paramName: Type? = null`
  - nullable + default  → emit `paramName: Type? = <defaultValue>`
  - non-nullable + default → emit `paramName: Type = <defaultValue>`
  - non-nullable + no default → already caught in validation (step 4); skip defensively
- Forward `annotations` exactly like domain-field annotations
- Append a `FieldRules` entry (with empty `stmts` since validators aren't supported on extra fields)

Key code location: `ClassGenerator.kt:106` — right after the closing `}` of the field loop,
before `classBuilder.primaryConstructor(ctorBuilder.build())`.

---

### Step 4 — `DomainMappingProcessorProvider.kt` (PASS 1d)

Inside `validatePropertyRefs`, after the bundle-property check, add:

```kotlin
// Validate ExtraField constraints
spec.extraFieldAnnotations().forEach { ann ->
    val suffixes = ann.argStringList(PROP_FOR)
    val fieldName = ann.argString(PROP_EXTRA_NAME)
    val nullable = ann.argBool(PROP_EXTRA_NULLABLE)
    val default = ann.argString(PROP_EXTRA_DEFAULT)

    spec.classSpecAnnotations().forEach { csAnn ->
        val model = csAnn.toClassSpecModel()
        if (model.suffix !in suffixes) return@forEach
        if (!model.partial && !nullable && default.isBlank()) {
            logger.error(
                "ExtraField '$fieldName' on $specName for suffix '${model.suffix}' " +
                "must have a defaultValue because the output is non-partial and non-nullable."
            )
        }
    }
}
```

Add a private `KSClassDeclaration.extraFieldAnnotations()` helper (mirrors
`classSpecAnnotations()` but for `@ExtraField` / its repeatable container).

---

### Step 5 — Example usage in `UserSpec.kt`

```kotlin
@ExtraField(
    for_ = ["Entity"],
    name = "version",
    type = Long::class,
    defaultValue = "0L",
    annotations = [CustomAnnotation(annotation = jakarta.persistence.Version::class)]
)
```

This adds a `version: Long = 0L` field (with `@Version`) to `UserEntity` only.
The mapper omits it and Kotlin uses the default.

---

## Critical files

| File | Change |
|---|---|
| `annotations/src/main/kotlin/com/example/annotations/ClassAnnotations.kt` | Add `@ExtraField` |
| `processor/src/main/kotlin/AnnotationConstants.kt` | Add `AN_EXTRA_FIELD`, constants |
| `processor/src/main/kotlin/ClassGenerator.kt` | Emit extra fields in `generate()` |
| `processor/src/main/kotlin/DomainMappingProcessorProvider.kt` | Validate extra fields in PASS 1d |
| `app/src/main/kotlin/UserSpec.kt` | Add `@ExtraField` example |

`MapperGenerator.kt` requires **no changes** — extra fields have constructor defaults so the
mapper call naturally omits them.

---

## Verification

1. Run `./gradlew :app:build` — should compile cleanly with the new `@ExtraField` on `UserSpec`.
2. In the generated `UserEntity.kt`, confirm `version: Long = 0L` appears with `@Version`.
3. In `UserEntityMappers.kt`, confirm `version` is NOT present in the `toEntity()` args.
4. In `Main.kt` (or a test), construct a `UserEntity` without passing `version` and verify
   the default `0L` is used.
5. Add a non-partial, non-nullable `@ExtraField` with no `defaultValue` and verify the
   processor emits an error.

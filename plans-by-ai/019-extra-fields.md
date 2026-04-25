# Plan: Add Extra Fields to Generated Classes

## Context

All fields in generated classes currently come from the domain class's primary constructor
parameters. There is no way to inject additional fields into a specific generated output that
don't exist in the domain. This plan adds an `@AddField` annotation that lets users declare
synthetic fields on individual generated outputs (e.g. a `createdAt: Instant` on an `Entity`
class that has no counterpart in the domain model).

## Checklist

- [x] 1. Add `@AddField` annotation to `annotations` module
- [x] 2. Add `AN_ADD_FIELD`, `FQN_ADD_FIELD`, and property name constants to `AnnotationConstants.kt`
- [x] 3. Emit extra fields in `ClassGenerator` after the domain-derived fields loop
- [x] 4. Validate extra fields in `DomainMappingProcessorProvider` (PASS 1d)
- [x] 5. Add example usage in `UserSpec.kt`

---

## Annotation design

New repeatable annotation placed on spec classes alongside `@ClassField` / `@FieldSpec`:

```kotlin
// annotations/ClassAnnotations.kt
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class AddField(
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

Append `@AddField` after the existing `@FieldSpec` declaration.

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class AddField(
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
internal val AN_ADD_FIELD      = AddField::class.simpleName!!
internal val AN_ADD_FIELDS     = AddField::class.simpleName!! + "s"  // @Repeatable container
internal val FQN_ADD_FIELD     = AddField::class.qualifiedName!!

// AddField property names
internal val PROP_ADD_NAME     = AddField::name.name
internal val PROP_ADD_TYPE     = AddField::type.name
internal val PROP_ADD_NULLABLE = AddField::nullable.name
internal val PROP_ADD_DEFAULT  = AddField::defaultValue.name
```

(`PROP_FOR`, `PROP_ANNOTATIONS` are already defined and reused.)

---

### Step 3 — `ClassGenerator.kt`

After the existing domain-field loop (after `classBuilder.primaryConstructor(ctorBuilder.build())`
is set up, but before calling `primaryConstructor`), add a second loop that reads
`@AddField` annotations from the spec class filtered to `model.suffix`.

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
// Validate AddField constraints
spec.addFieldAnnotations().forEach { ann ->
    val suffixes = ann.argStringList(PROP_FOR)
    val fieldName = ann.argString(PROP_ADD_NAME)
    val nullable = ann.argBool(PROP_ADD_NULLABLE)
    val default = ann.argString(PROP_ADD_DEFAULT)

    spec.classSpecAnnotations().forEach { csAnn ->
        val model = csAnn.toClassSpecModel()
        if (model.suffix !in suffixes) return@forEach
        if (!model.partial && !nullable && default.isBlank()) {
            logger.error(
                "@AddField '$fieldName' on $specName for suffix '${model.suffix}' " +
                "must have a defaultValue because the output is non-partial and non-nullable."
            )
        }
    }
}
```

Add a private `KSClassDeclaration.addFieldAnnotations()` helper (mirrors
`classSpecAnnotations()` but for `@AddField` / its repeatable container).

---

### Step 5 — Example usage in `UserSpec.kt`

```kotlin
@AddField(
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
| `annotations/src/main/kotlin/com/example/annotations/ClassAnnotations.kt` | Add `@AddField` |
| `processor/src/main/kotlin/AnnotationConstants.kt` | Add `AN_ADD_FIELD`, constants |
| `processor/src/main/kotlin/ClassGenerator.kt` | Emit extra fields in `generate()` |
| `processor/src/main/kotlin/DomainMappingProcessorProvider.kt` | Validate extra fields in PASS 1d |
| `app/src/main/kotlin/UserSpec.kt` | Add `@AddField` example |

`MapperGenerator.kt` requires **no changes** — extra fields have constructor defaults so the
mapper call naturally omits them.

---

## Verification

1. Run `./gradlew :app:build` — should compile cleanly with the new `@AddField` on `UserSpec`.
2. In the generated `UserEntity.kt`, confirm `version: Long = 0L` appears with `@Version`.
3. In `UserEntityMappers.kt`, confirm `version` is NOT present in the `toEntity()` args.
4. In `Main.kt` (or a test), construct a `UserEntity` without passing `version` and verify
   the default `0L` is used.
5. Add a non-partial, non-nullable `@AddField` with no `defaultValue` and verify the
   processor emits an error.

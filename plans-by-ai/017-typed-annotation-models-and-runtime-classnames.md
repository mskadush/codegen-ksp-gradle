# Plan 017: Typed Annotation Models and Runtime ClassNames

**Created**: 2026-04-23
**Context**: Two stringly-typed patterns remain in the processor:
1. `argString`/`argBool`/`argEnumName` calls are scattered at every call site — unsafe casts, string-based field lookup
2. `ClassName("com.example.runtime", "ValidationContext")` and friends are unverified string FQNs

## Approach

### Part A — Typed annotation model wrappers
Create `AnnotationModels.kt` with typed data classes for each annotation (`ClassSpecModel`, `ClassFieldModel`, `FieldSpecModel`). The `argXxx` helpers are called **once** in factory functions on those types; all downstream code works with typed fields.

### Part B — Runtime `ClassName` via `::class.asClassName()`
Add `:runtime` as `compileOnly` dependency in `processor/build.gradle.kts`. Replace every `ClassName("com.example.runtime", "...")` and `imports += "com.example.runtime" to "..."` with `RuntimeClass::class.asClassName()` and `VALIDATION_CONTEXT.packageName to VALIDATION_CONTEXT.simpleName`.

## Steps

- [x] 1. Add `implementation(project(":runtime"))` to `processor/build.gradle.kts` (compileOnly insufficient — processor needs runtime on KSP worker classpath)
- [x] 2. Create `processor/src/main/kotlin/AnnotationModels.kt` with `ClassSpecModel` + factory `fun KSAnnotation.toClassSpecModel()`
- [x] 3. Replace `ClassName("com.example.runtime", "...")` in `ClassGenerator.kt` with `RuntimeClass::class.asClassName()`; replace manual import strings with `ClassName.packageName to ClassName.simpleName`
- [x] 4. `ClassGenerator.generate()` now accepts `ClassSpecModel` directly — no `argString`/`argBool` at call site
- [x] 5. `MapperGenerator.generate()` now accepts `ClassSpecModel` directly
- [x] 6. `DomainMappingProcessorProvider` builds model once per annotation, passes to both generators
- [x] 7. Full build passes — KSP generation produces correct output

## Dependencies/Risks
- `compileOnly` scope means `:runtime` is NOT included in the processor JAR — correct, since `:app` already depends on `:runtime` at runtime.
- `KSAnnotation.toAnnotation<ClassSpec>()` can't be used because `for_: KClass<*>` throws. We keep the `argXxx` helpers but move their call sites into the model factories.

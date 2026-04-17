# Step 1 — Module Wiring + DomainMappingProcessor Scaffold

## Context

Implements Step 1 of `003-full-domain-mapping-processor.md`. Wires the `:runtime` module, adds KotlinPoet to the processor, and creates an empty `DomainMappingProcessor` that scans `@EntitySpec` symbols without emitting any output yet.

---

## Checklist

- [x] Add `include(":runtime")` to `settings.gradle.kts`
- [x] Create `runtime/build.gradle.kts` with `kotlin("jvm")` + `implementation(project(":annotations"))`
- [x] Add KotlinPoet deps to `processor/build.gradle.kts`:
  - `implementation("com.squareup:kotlinpoet:2.3.0")`
  - `implementation("com.squareup:kotlinpoet-ksp:2.3.0")`
- [x] Add `implementation(project(":runtime"))` to `app/build.gradle.kts`
- [x] Create `processor/src/main/kotlin/DomainMappingProcessor.kt`
- [x] Create `processor/src/main/kotlin/DomainMappingProcessorProvider.kt`
- [x] Append `DomainMappingProcessorProvider` to `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`

---

## Verification

```bash
./gradlew :app:kspKotlin   # BUILD SUCCESSFUL ✓
```

**Status: Complete**

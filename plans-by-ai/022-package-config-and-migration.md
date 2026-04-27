# Plan 022: Package Configuration for Generated Files + Package Migration

**Created**: 2026-04-27
**Supersedes**: none

## Approach

Two changes ship together: adding configurable output packages for generated files, and renaming
the repo's own packages from `com.example` to `za.skadush.codegen.gradle`.

**What was built:**
- `outputPackage: String = ""` parameter on `@ClassSpec` for per-spec output package control
- KSP processor option `codegen.defaultPackage` for a project-level default
- Three-tier precedence: per-spec `outputPackage` → KSP `codegen.defaultPackage` → domain class package
- Full package rename throughout `annotations/`, `runtime/`, `app/`, and `processor/` imports

**Riskiest integration:** `ClassGenerator` writes to package `""` (root/unnamed) and `MapperGenerator`
constructs `ClassName("", outputName)` for the generated class reference in mapper bodies. Both had
to move atomically. `SpecRegistry` was extended to store `(outputPackage, simpleName)` pairs so
`ClassResolver` can create correct `ClassName` references for nested mapped types.

## Checklist

- [x] Stage 1: tracer bullet — `outputPackage` on `@ClassSpec`, both generators updated, demo works
- [x] Stage 2: KSP default option, three-tier precedence in `ClassSpecModel`
- [x] Stage 3: package migration `com.example` → `za.skadush.codegen.gradle`
- [x] Stage 4: cleanup + ROADMAP update

## Step Sequence

### Stage 1 — Tracer Bullet ✅
- W1. Add `outputPackage: String = ""` to `@ClassSpec` — `annotations/.../ClassSpec.kt`
- W2. Add `PROP_OUTPUT_PACKAGE` to `AnnotationConstants.kt`
- W3. Add `outputPackage`, `resolvedOutputPackage` to `ClassSpecModel` — `AnnotationModels.kt`
- W4. `ClassGenerator.kt` line 166: `FileSpec.builder(model.resolvedOutputPackage, outputName)`
- W5. `MapperGenerator.kt`: `ClassName(model.resolvedOutputPackage, outputName)` + same for FileSpec
- W6. Demo via `AddressSpec.kt` tracer (reverted after Stage 2)

### Stage 2 — KSP Default Option ✅
- W7. Read `environment.options["codegen.defaultPackage"]` in `DomainMappingProcessorProvider`
- W8. Add `processorDefaultPackage` to `ClassSpecModel`; update `resolvedOutputPackage` precedence
- W9. Add `ksp { arg("codegen.defaultPackage", ...) }` to `app/build.gradle.kts`
- **Bonus fix**: Extended `SpecRegistry` to store `(outputPackage, simpleName)` pairs and added
  `lookupNestedClassName()`. Updated `ClassResolver.classifyField()` to use it so nested mapped
  types (`AddressEntity` inside `OrderEntity`) get the correct package in their `ClassName`.

### Stage 3 — Package Migration ✅
- W11. `annotations/` directory rename + 12 package declarations
- W12. `runtime/` directory rename + 2 package declarations
- W13. 6 `processor/` files: replace all `com.example.annotations.*` / `com.example.runtime.*` imports
- W14. `app/` 10 files: package declarations + annotation imports + UpperCaseTransformer import
- W15. Full clean verify: `./gradlew clean :app:run` — all output matches pre-migration

### Stage 4 — Cleanup ✅
- W16. `app/build.gradle.kts`: `"com.example.generated"` → `"za.skadush.codegen.gradle.generated"`
       `Main.kt`: update 4 import lines to match new generated package
- W17. Fix doc-comment FQN strings in `TransformerRegistryScanner.kt`, `FieldValidator.kt`,
       `RequestValidator.kt`, `Validation.kt`
- W18. Update `ROADMAP.md`

## What Was Done

Everything in the plan was implemented. One additional change beyond the plan was required:

**`SpecRegistry` extended with package storage.** The original plan assumed `ClassResolver` only
needed the simple name for `FieldKind.MappedObject`/`MappedCollection`. But `ClassGenerator` uses
`kind.targetClassName` (a `ClassName`) to emit the type reference. Before this change it was always
`ClassName("", simpleName)` — fine when generated classes were in the root package, broken when
they moved to a named package. Fix: `SpecRegistry.targets` changed from
`Map<String, Map<String, String>>` to `Map<String, Map<String, Pair<String, String>>>` (stores
`(outputPackage, simpleName)`), and a new `lookupNestedClassName()` method was added.

**Gradle daemon caching caveat.** After the package migration, KSP silently produced 0 generated
files until all Gradle daemons were stopped (`./gradlew --stop`). The daemon had loaded the old
annotation classes and didn't pick up the recompiled versions. Always run `./gradlew --stop` before
the first build after a package rename in KSP projects.

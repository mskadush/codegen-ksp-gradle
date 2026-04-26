# Plan 011: Step 13 — Bundle System (Annotation Composition + BundleRegistry)

**Created**: 2026-04-21
**Context**: Step 13 of the master implementation plan (003-full-domain-mapping-processor.md). The annotation API was unified in step 11/plan-010: three separate annotation families (EntitySpec/DtoSpec/RequestSpec) collapsed into `@ClassSpec` + `@ClassField` + `@FieldSpec`. The bundle annotations (`@EntityBundle`, `@DtoBundle`, `@RequestBundle`) were *not* updated and are now stale — they predate the unified API and reference removed annotations in their KDoc. Step 13 implements the bundle resolution system **and** consolidates the bundle annotations to compose with the unified API vocabulary.

---

## The Composition Insight

A bundle is structurally identical to a spec class **minus** `@ClassSpec`. It should carry the same `@ClassField` and `@FieldSpec` annotations — the same vocabulary, no new annotation surface. The three separate bundle type annotations collapse into one:

```
@EntityBundle(name)  ─┐
@DtoBundle(name)     ─┼─► @FieldBundle(name)
@RequestBundle(name) ─┘
```

Field overrides inside bundles already scope themselves via `@FieldSpec(for_ = ["Entity"])`, so no "entity-bundle field" vs "dto-bundle field" distinction is needed.

---

## Approach

Five sequential parts: annotations → registry → resolution logic → generator wiring → app example.

---

## Checklist

### Part A — Annotation consolidation
**File:** `annotations/src/main/kotlin/com/example/annotations/IncludeBundles.kt`

- [x] A1: Delete `@EntityBundle`
- [x] A2: Delete `@DtoBundle`
- [x] A3: Delete `@RequestBundle`
- [x] A4: Add `@FieldBundle(name: String)` with `@Target(CLASS)`, `@Retention(SOURCE)`, full KDoc explaining that the bundle class carries `@ClassField`/`@FieldSpec` annotations
- [x] A5: Update `@IncludeBundles` KDoc to reference `@FieldBundle` (step 14 prep)

**File:** `annotations/src/main/kotlin/com/example/annotations/ClassSpec.kt`

- [x] A6: Update `@param bundles` KDoc in `@ClassSpec` to say "Names of [@FieldBundle] classes whose field configs are merged into this spec"

---

### Part B — BundleRegistry
**New file:** `processor/src/main/kotlin/BundleRegistry.kt`

- [x] B1: Create file with `data class BundleRegistry(val bundles: Map<String, KSClassDeclaration>)`
- [x] B2: Add `companion object { val EMPTY; fun build(resolver, logger) }`
- [x] B3: `build()` scans `resolver.getSymbolsWithAnnotation("com.example.annotations.FieldBundle")`
- [x] B4: `build()` reads `argString("name")` from the annotation — reuses existing helper from `KspAnnotationExtensions.kt`
- [x] B5: `build()` logs error + skips on blank name: `"@FieldBundle on ${decl.simpleName} has a blank name"`
- [x] B6: `build()` logs error + skips on duplicate: `"Duplicate @FieldBundle name '$name': declared on both X and Y"`

---

### Part C — Bundle resolution logic
**File:** `processor/src/main/kotlin/KspAnnotationExtensions.kt`
*(This is where `MergedOverride`, `mergedFieldOverrides` already live — NOT FieldModel.kt)*

- [x] C1: Add `argStringList(name: String): List<String>` helper (reads `Array<String>` annotation args — needed to read `ClassSpec.bundles`)
  ```kotlin
  @Suppress("UNCHECKED_CAST")
  internal fun KSAnnotation.argStringList(name: String): List<String> =
      (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
          ?.filterIsInstance<String>() ?: emptyList()
  ```

- [x] C2: Add `KSClassDeclaration.resolveWithBundles(suffix, bundleNames, mergeStrategy, bundleRegistry, logger): Map<String, MergedOverride>` extension function
  - Calls `mergedFieldOverrides(suffix)` on the spec class (unchanged fast path when `bundleNames` is empty)
  - For each bundle name: look up in `bundleRegistry.bundles`; if missing → `logger.error("Unknown bundle '$name' referenced in @ClassSpec(suffix=\"$suffix\") on ${simpleName.asString()}. Declare a class annotated with @FieldBundle(\"$name\").")`
  - Calls `mergedFieldOverrides(suffix)` on each resolved bundle `KSClassDeclaration`
  - Multiple bundles: first-bundle-wins within bundle layer (mirrors order declared in `bundles = [...]`)
  - Merge spec + bundle layer per `BundleMergeStrategy`:
    - **SPEC_WINS** (default): spec entries take priority; bundle fills gaps
    - **BUNDLE_WINS**: bundle entries take priority; spec fills gaps
    - **MERGE_ADDITIVE**: per-sub-field, spec's non-default value wins; bundle supplies the rest

- [x] C3: Add private `mergeAdditive(spec: Map<String, MergedOverride>, bundle: Map<String, MergedOverride>): Map<String, MergedOverride>`
  - For each property in the union: pick non-default values from spec first, bundle fills the rest
  - "Non-default": `exclude=true`, `nullable != "UNSET"`, non-blank `transformerRef`/`transformerFQN`, non-empty `annotations`, non-blank `column`/`rename`/`rules`

---

### Part D — Generator wiring
**Pattern**: All four generators already hold `logger` and `classResolver` as constructor-injected `val`s. Add `var bundleRegistry: BundleRegistry = BundleRegistry.EMPTY` as a mutable field (same lazy-injection pattern as `classResolver.registry`). Set it from `DomainMappingProcessorProvider` at the end of Pass 1, before Pass 2.

**Files:** `EntityGenerator.kt`, `DtoGenerator.kt`, `RequestGenerator.kt`, `MapperGenerator.kt`

- [x] D1: Add `var bundleRegistry: BundleRegistry = BundleRegistry.EMPTY` to `EntityGenerator`
- [x] D2: In `EntityGenerator.generate()`: read `bundleNames = classSpecAnn.argStringList("bundles")` and `mergeStrategy = classSpecAnn.argEnumName("bundleMergeStrategy")`, then replace `spec.mergedFieldOverrides(suffix)` with `spec.resolveWithBundles(suffix, bundleNames, mergeStrategy, bundleRegistry, logger)`
- [x] D3: Same for `DtoGenerator` (D3a: add field, D3b: replace call)
- [x] D4: Same for `RequestGenerator` (D4a: add field, D4b: replace call)
- [x] D5: Same for `MapperGenerator` (D5a: add field, D5b: replace call site)

**File:** `processor/src/main/kotlin/DomainMappingProcessorProvider.kt`

- [x] D6: At end of Pass 1 (after cycle detection, before Pass 2): build BundleRegistry with KSP-AA multi-round caching (cachedBundleRegistry field)
- [x] D7: Inject into all four generators:
  ```kotlin
  entityGenerator.bundleRegistry  = bundleRegistry
  dtoGenerator.bundleRegistry     = bundleRegistry
  requestGenerator.bundleRegistry = bundleRegistry
  mapperGenerator.bundleRegistry  = bundleRegistry
  ```

> **Note**: `hasRulesForSuffix` in Pass 1 is intentionally NOT changed — bundles cannot make a plain output into a request. Output-kind classification stays spec-only.

---

### Part E — App example
**New file:** `app/src/main/kotlin/TimestampsBundle.kt`

A realistic audit-fields bundle demonstrating the composition: same `@ClassField`/`@FieldSpec` vocabulary, scoped to output kinds via `for_`.

```kotlin
@FieldBundle("timestamps")
// Entity: snake_case column names + Jakarta annotations
@FieldSpec(for_ = ["Entity"], property = "createdAt", column = "created_at",
    annotations = [CustomAnnotation(jakarta.persistence.Column::class,
        members = ["name=\"created_at\"", "nullable=false", "updatable=false"])])
@FieldSpec(for_ = ["Entity"], property = "updatedAt", column = "updated_at",
    nullable = NullableOverride.YES,
    annotations = [CustomAnnotation(jakarta.persistence.Column::class,
        members = ["name=\"updated_at\""])])
// Requests: exclude audit fields (not user-supplied)
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "updatedAt", exclude = true)
object TimestampsBundle
```

- [x] E1: Create `app/src/main/kotlin/TimestampsBundle.kt` with the above
- [x] E2: Add `val createdAt: java.time.Instant` and `val updatedAt: java.time.Instant?` to `User.kt`
- [x] E3: Add `bundles = ["timestamps"], bundleMergeStrategy = BundleMergeStrategy.SPEC_WINS` to `@ClassSpec(suffix = "Entity")` in `UserSpec.kt`; also add `bundles = ["timestamps"]` to CreateRequest and UpdateRequest specs so bundle exclusions take effect
- [x] E4: Add `import com.example.annotations.BundleMergeStrategy` to `UserSpec.kt`

---

## Verification

```bash
./gradlew :app:kspKotlin
# Must compile cleanly.
# app/build/generated/ksp/main/kotlin/UserEntity.kt must contain:
#   - createdAt field with @Column(name="created_at", nullable=false, updatable=false)
#   - updatedAt: Instant? field with @Column(name="updated_at")
# UserCreateRequest and UserUpdateRequest must NOT contain createdAt or updatedAt fields.

# Negative test: rename "timestamps" to "timestamps_typo" in UserSpec.bundles → build must fail with:
# error: Unknown bundle 'timestamps_typo' referenced in @ClassSpec(suffix="Entity") on UserSpec
```

---

## Critical Files

| File | Change |
|---|---|
| `annotations/.../IncludeBundles.kt` | Replace 3 annotations with `@FieldBundle` |
| `annotations/.../ClassSpec.kt` | Update `@param bundles` KDoc |
| `processor/.../BundleRegistry.kt` | **New file** |
| `processor/.../KspAnnotationExtensions.kt` | Add `argStringList`, `resolveWithBundles`, `mergeAdditive` |
| `processor/.../EntityGenerator.kt` | Add `bundleRegistry` field, swap call |
| `processor/.../DtoGenerator.kt` | Add `bundleRegistry` field, swap call |
| `processor/.../RequestGenerator.kt` | Add `bundleRegistry` field, swap call |
| `processor/.../MapperGenerator.kt` | Add `bundleRegistry` field, swap both calls |
| `processor/.../DomainMappingProcessorProvider.kt` | Build + inject `BundleRegistry` |
| `app/.../TimestampsBundle.kt` | **New file** |
| `app/.../User.kt` | Add `createdAt`, `updatedAt` fields |
| `app/.../UserSpec.kt` | Reference `bundles = ["timestamps"]` on Entity spec |

## Dependencies / Risks

- `argStringList` must be added before `resolveWithBundles` can compile (same file, same PR)
- Removing `@EntityBundle`/`@DtoBundle`/`@RequestBundle` is safe — nothing references them yet (bundle resolution was not implemented)
- `User.kt` change adds fields to the domain class; existing generated output files (`UserEntity`, `UserResponse`, etc.) will gain the new fields automatically on next KSP run — verify existing `@FieldSpec` overrides in `UserSpec` don't conflict

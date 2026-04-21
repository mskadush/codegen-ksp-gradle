# Plan 012: Step 13 — Property Reference Validation + UserEntityBundle

**Created**: 2026-04-21
**Context**: Completing the two remaining items from step 13 of plan 003. The bundle registry and merge logic (plan 011) were already done. Missing pieces were: (1) no validation that `property` values in `@ClassField`/`@FieldSpec` annotations reference actual domain class constructor params, and (2) no entity-specific bundle example in `app/`.

---

## Approach

Add a PASS 1d validation sweep in `DomainMappingProcessorProvider` that runs after `BundleRegistry` is built. Add a `UserEntityBundle` in `app/` that contributes JPA `@Id`/`@GeneratedValue` to the entity `id` field via `MERGE_ADDITIVE`.

---

## Checklist

### Part A — Property reference validation

- [x] A1: Add `import com.google.devtools.ksp.processing.KSPLogger` to `DomainMappingProcessorProvider.kt`
- [x] A2: Add PASS 1d block in `process()` (after bundle registry injection, before PASS 2) that calls `spec.validatePropertyRefs(cachedBundleRegistry, logger)` for every `@ClassSpec`-annotated declaration
- [x] A3: Implement `private fun KSClassDeclaration.validatePropertyRefs(bundleRegistry, logger)` at the bottom of `DomainMappingProcessorProvider.kt`:
  - Collects domain constructor param names from all `@ClassSpec` annotations on the spec
  - Checks each `@ClassField`/`@FieldSpec` `property` value — error: `Unknown property '$property' on $domainName in $specName`
  - Checks each bundle name in `@ClassSpec.bundles` — error: `Unknown bundle '$bundleName' on $specName`
  - Checks each resolved bundle's `@ClassField`/`@FieldSpec` properties against the domain class
- [x] A4: Remove the redundant `logger.error(...)` in `resolveWithBundles()` (`KspAnnotationExtensions.kt`) — PASS 1d now owns unknown-bundle reporting; `resolveWithBundles()` silently `continue`s

### Part B — UserEntityBundle app example

- [x] B1: Create `app/src/main/kotlin/UserEntityBundle.kt` with `@FieldBundle("userEntity")` that attaches `@Id` and `@GeneratedValue(strategy = IDENTITY)` to the `id` field for the Entity suffix
- [x] B2: Update `UserSpec.kt` Entity `@ClassSpec`:
  - Change `bundles = ["timestamps"]` → `bundles = ["timestamps", "userEntity"]`
  - Change `bundleMergeStrategy = BundleMergeStrategy.SPEC_WINS` → `MERGE_ADDITIVE` so that the spec's `nullable = YES` on `id` is preserved while the bundle's annotations are merged in (spec's `fieldLevelAnn` for `id` is empty, so bundle's `@Id`/`@GeneratedValue` fill it)

---

## Verification

```bash
# Happy path — clean build + @Id/@GeneratedValue on UserEntity.id
./gradlew :app:kspKotlin
# UserEntity.kt must have @Id and @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) on id

# Negative test — both error messages fire exactly once
# (temporarily add BrokenSpec.kt with a typo property + unknown bundle, then delete)
# e: [ksp] Unknown property 'naem' on User in BrokenSpec
# e: [ksp] Unknown bundle 'nonExistentBundle' on BrokenSpec
```

---

## Critical Files

| File | Change |
|---|---|
| `processor/.../DomainMappingProcessorProvider.kt` | Add KSPLogger import, PASS 1d block, `validatePropertyRefs` function |
| `processor/.../KspAnnotationExtensions.kt` | Remove duplicate unknown-bundle error from `resolveWithBundles()` |
| `app/.../UserEntityBundle.kt` | **New file** — `@FieldBundle("userEntity")` with `@Id`/`@GeneratedValue` on `id` |
| `app/.../UserSpec.kt` | Add `"userEntity"` to Entity bundles; switch to `MERGE_ADDITIVE` |

## Dependencies / Risks

- `validatePropertyRefs` relies on `classSpecAnnotations()` and `domainClass()` helpers already defined in the same file — no new helpers needed
- `MERGE_ADDITIVE` change in `UserSpec` is non-breaking: spec's non-default values still win; the only new contribution is the bundle's `@Id`/`@GeneratedValue` on `id` (which the spec had no annotations for)

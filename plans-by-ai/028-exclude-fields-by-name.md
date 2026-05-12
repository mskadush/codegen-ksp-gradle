# 028 — Exclude Fields by Name on `@ClassSpec`

**Created**: 2026-05-12
**Supersedes**: none

## Context

`@ClassSpec` already supports field-level removal via `@FieldOverride(exclude = true)`, but doing so for several fields requires one `@FieldOverride` per name — verbose for the common "this output kind drops these N source fields wholesale" case. This plan adds an `exclude: Array<String>` parameter directly on `@ClassSpec`, applied after bundle merge but before per-field overrides/additions.

## Interrogation

### Q1: Where does exclude apply — domain fields only, or also bundle-contributed fields?

**Context:** `@ClassSpec` aggregates from the domain class plus any `bundles`.
**Options:** a) final-set (post-bundle merge), b) domain-only.
**Counter-example:** (b) is surprising — `exclude = ["createdAt"]` would still leave `TimestampsBundle.createdAt` in place. (a) is intuitive but can silently erase bundle behaviour.
**Decision:** (a) final-set, **with a KSP warning** when the excluded name originated from a bundle — surfaces the bundle interaction without blocking it.

### Q2: What if an `exclude` entry matches no field at all (typo case)?

**Context:** `exclude = ["createAt"]` (typo) would silently do nothing under naive impl.
**Options:** a) hard error, b) warning, c) silent.
**Counter-example:** (a) breaks refactors when a domain field is genuinely removed.
**Decision:** (a) hard error — typos rot otherwise; legitimate "domain field is gone" cases are caught and resolved at the spec.

### Q3: Interaction with `@FieldOverride` / `@AddField` targeting an excluded name?

**Context:** A spec could `exclude = ["email"]` while also `@FieldOverride(name="email", ...)` or `@AddField(name="email", ...)`.
**Options:** a) hard error on conflict, b) exclude wins, c) override/add wins.
**Counter-example:** (a) blocks the "drop domain field, re-add with different type" pattern; users wanting that can omit the exclude entry and use `@FieldOverride` directly.
**Decision:** (a) hard error — contradiction is a bug, not a feature.

### Q4: Scope — per-`ClassSpec` only, or also on `@FieldBundle`?

**Context:** Bundles today only contribute fields.
**Options:** a) `ClassSpec.exclude` only, b) both.
**Counter-example:** (b) would let bundles act subtractively and complicate the additive mental model.
**Decision:** (a) `ClassSpec` only.

### Q5: Defaults for remaining questions.

**Decision:** Exact-match name comparison (same as `FieldOverride.name`); orthogonal to `partial = true` (exclude first, then nullable-ify survivors); update `docs/annotations/ClassSpec.md`.

## Final shape

```kotlin
annotation class ClassSpec(
    val for_: KClass<*>,
    val suffix: String = "",
    // ...existing params...
    val exclude: Array<String> = [],
    // ...
)
```

### Semantics

1. Resolve domain fields and merge bundles → "final set".
2. For each name in `exclude`:
   - If it does not appear in the final set → **KSP error**.
   - If it appears **only** because a bundle contributed it (i.e. not a domain property) → **KSP warning**, then remove.
   - Otherwise → remove silently.
3. If the same name appears in any `@FieldOverride.name` or `@AddField.name` targeting this spec's suffix → **KSP error**.

## Scope

- Annotation surface: add `exclude: Array<String> = []` to `@ClassSpec`.
- Processor:
  - `AnnotationConstants.kt` — add `PROP_EXCLUDE`.
  - `DomainMappingProcessorProvider.kt` — read `exclude` via existing `argStringList()`; validation passes for unknown-name, override-overlap, addfield-overlap; warn-on-bundle-origin.
  - `ClassResolver.kt` (or `ClassGenerator.kt:66–68` insertion point) — apply filtering after bundle merge, before override application.
- App sample: `UserSpec.kt` — demonstrate on one spec (e.g. `UpdateRequest` excluding `updatedAt`).
- Docs: update `docs/annotations/ClassSpec.md` with a new `exclude` section.
- Verification: `./gradlew :app:build` green; inspect generated `UserUpdateRequest.kt` confirms field is absent.

## Out of scope

- Bundle-level exclude (Q4).
- Pattern/glob matching on exclude names — exact-match only.
- Re-introducing an excluded field via `@AddField` (Q3 — hard error).

## Critical files

- `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/ClassSpec.kt`
- `processor/src/main/kotlin/AnnotationConstants.kt`
- `processor/src/main/kotlin/DomainMappingProcessorProvider.kt` (validation in `validateAnnotations()` around lines 345–403; reading the array via `argStringList()` at line ~382 precedent)
- `processor/src/main/kotlin/ClassResolver.kt` / `ClassGenerator.kt:66–68` (filter point)
- `app/src/main/kotlin/UserSpec.kt` (demo)
- `docs/annotations/ClassSpec.md`

## Approval checklist

- [x] Add `exclude: Array<String> = []` to `@ClassSpec`
- [x] Add `PROP_EXCLUDE` constant
- [x] Read `exclude` in processor; thread through to resolution
- [x] Filter the merged field set; emit warning when name originated from a bundle
- [x] KSP error: unknown exclude name
- [x] KSP error: exclude overlaps `@FieldOverride.name` for same suffix
- [x] KSP error: exclude overlaps `@AddField.name` for same suffix
- [x] Demo in `UserSpec.kt`
- [x] Update `docs/annotations/ClassSpec.md`
- [x] `./gradlew :app:build` passes
- [x] Inspect generated source confirms field omitted

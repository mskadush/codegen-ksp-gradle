# Change Report — Default field values

**Date:** 2026-05-12 14:13
**Session type:** feature
**Plan:** 027
**Detekt:** not configured

## What changed

- `annotations/.../Default.kt` — new nested annotation type (`value`, `inherit`, `clearInherited`).
- `annotations/.../FieldSpec.kt` — added `default: Default = Default()` parameter.
- `annotations/.../FieldOverride.kt` — added `default: Default = Default()` parameter.
- `annotations/.../AddField.kt` — migrated `defaultValue: String` to `default: Default = Default()`.
- `processor/.../AnnotationConstants.kt` — added `PROP_DEFAULT`, `PROP_DEFAULT_VALUE`, `PROP_DEFAULT_INHERIT`, `PROP_DEFAULT_CLEAR_INHERITED`. `PROP_ADD_DEFAULT` retargeted at `AddField::default`.
- `processor/.../KspAnnotationExtensions.kt` — added `DefaultConfig`, `argDefault()`, validating `readDefault()`, `String.isBalancedDefaultExpression()`, `KSValueParameter.readSourceDefaultExpression()`, the private `extractDefaultExpression()` scanner. `MergedOverride.defaultConfig` field. `mergedFieldOverrides` + `resolveWithBundles` now accept a `KSPLogger?`. `mergeAdditive` merges `defaultConfig` with spec-wins-if-non-sentinel.
- `processor/.../FieldModel.kt` — added `sourceParam: KSValueParameter?` (needed for source-offset reads).
- `processor/.../ClassResolver.kt` — populates `sourceParam` in `resolve()`.
- `processor/.../ClassGenerator.kt` — threads `logger` into `resolveWithBundles`; new `resolveSourceDefaultExpression()` translates the merged `DefaultConfig` into a verbatim expression (or null) and applies it to the non-partial constructor parameter.
- `processor/.../DomainMappingProcessorProvider.kt` — `@AddField` constraint check reads `defaultCfg.value` instead of the legacy string param; error message updated.
- `app/.../User.kt` — added `createdAt: Instant = java.time.Instant.now()` source default to exercise `inherit = true`.
- `app/.../UserSpec.kt` — migrated `@AddField(default = "0L")` → `Default(value = "0L")`; added explicit-value default for `name` on `CreateRequest` and inherit default for `createdAt` on `Response`. Imports `Default`.
- `docs/annotations/AddField.md` — `defaultValue` → `default`, migration callout, examples updated, see-also added.
- `docs/annotations/FieldSpec.md` — `default` row + new "Provide a default value across all outputs" example + see-also.
- `docs/annotations/FieldOverride.md` — `default` row + new "Override a class-wide default for one output" example + see-also.
- `docs/annotations/Default.md` — new page (properties table, mutual-exclusion rules, layering, examples, constraints, see-also).
- `plans-by-ai/027-default-field-values.md` — checklist marked done, Outcome section with deviations.
- `plans-by-ai/ROADMAP.md` — plan moved to Done.

## Why

The generator had no way to declare a default value for a source-derived field. The only existing surface was `AddField.defaultValue` for synthetic fields; everything sourced from the domain class forced callers to pass every argument. This plan added that surface uniformly across `@FieldSpec`, `@FieldOverride`, and `@AddField`.

The interrogation landed three flat parameters (`default: String`, `inheritDefault: Boolean`, `clearDefault: Boolean`) on `@FieldSpec` and `@FieldOverride`. Mid-implementation the user pushed back: three default-related params on annotations that already carry seven or eight each is exactly the bloat that plan 026 fought to avoid. The shape was restructured into a nested `Default` annotation type with one `default: Default = Default()` parameter on each host. `AddField` was migrated for consistency rather than left as a string-typed outlier. The cost is a one-line rename in any existing user code (one site in the sample app); the win is that all three annotations now share the same concept and the same docs page.

The riskiest part was `inherit = true` — KSP exposes `KSValueParameter.hasDefault` as a boolean but not the expression text. The reader is a hand-rolled scanner that locates the parameter declaration via its `FileLocation`, advances past `:` and the type (with `<()[]{}` depth tracking), then captures the post-`=` expression up to the next depth-0 `,` or `)` while respecting `"`/`'`/`"""` literals. It's not a full parser — pathological expressions (an `=` inside a generic argument or comparison at the parameter level) will misparse — but it handles every realistic shape (`Instant.now()`, `UUID.randomUUID()`, `listOf(...)`, numeric / string literals) and surfaces obvious failures as malformed generated code that `kotlinc` catches.

Two deviations worth recording for future readers:
- **Layering went from field-by-field merge to whole-annotation replacement.** When the underlying type was three flat parameters this was a real question; once `default` became a single nested annotation, "override wins, sentinel falls through" is the only sensible rule. `Default(clearInherited = true)` exists so a `FieldOverride` can still say "no default here" without restating the spec config.
- **`MapperGenerator` skips validation by passing `logger = null` to `resolveWithBundles`.** `ClassGenerator` is the single source of truth for default-related KSP errors; running validation again from the mapper would double-report every diagnostic.

## Notes

- `NonExistLocation` and `clearInherited`-on-`@FieldSpec` error paths are **not exercised by manual probes** — the sample project has no JAR-sourced domain class and no `@FieldSpec` usage. The code paths exist (`readSourceDefaultExpression` checks `location is FileLocation`; `readDefault` checks `allowClearInherited`); first real verification will happen when one of those shapes appears.
- The source-offset reader opens the source file from disk every time `inherit = true` resolves a field. For a sample with one such field this is invisible; for a real project with dozens of inherited defaults, file reads are cheap but not free. A per-file cache would be a one-screen optimisation if it ever shows up in build profiles.
- Detekt is not configured for the project. The skill flagged this as a one-time WARN; adding `detekt` would catch the kind of inline scanner code added here (`KspAnnotationExtensions.kt`'s scanner is the busiest function in the patch by far).
- The `@AddField.defaultValue → default` rename is a breaking change for any out-of-tree user. The only in-tree consumer is the sample app, which was migrated. Anyone consuming the artefact externally will see a clear KSP error pointing at the missing parameter.

# 025 — Field annotation rename: `@ClassField` / `@FieldSpec` → `@FieldSpec` / `@FieldOverride`

## Motivation

The original names misrepresented what each annotation does:

- **`@ClassField`** read like "a field on a class" but actually meant "field config that applies to **every** generated output". The cross-output scope was invisible in the name.
- **`@FieldSpec`** sat next to `@ClassSpec` and read as "a spec for a field" — but it was actually a per-output **override** keyed by `for_ = [...suffixes...]`.

The intended pattern across the public annotation surface is **"spec for X"**:

| Concept | Annotation |
|---|---|
| Spec for an output class | `@ClassSpec` |
| Spec for a field (default config across all outputs) | `@FieldSpec` |
| Spec for a per-output override of a field | `@FieldOverride` |

`@FieldSpec` now carries the meaning its name implies; `@FieldOverride` reads as exactly what it does.

## Final shape

| Old | New | Meaning |
|---|---|---|
| `@ClassField` | `@FieldSpec` | Default field configuration applied to **every** generated output |
| `@FieldSpec` (per-output override) | `@FieldOverride` | Per-output override — wins over `@FieldSpec` for the same `property` + suffix |

Merge precedence is unchanged: `@FieldOverride` > `@FieldSpec`.

## Scope

- Rename both annotation classes.
- Update every internal reference (processor, app sample, docs, README).
- **Hard break**: no deprecated shadows. `@ClassField` is gone; the new `@FieldSpec` is not the same annotation as the old `@FieldSpec`. Pre-1.0 library, branch unpushed at the time of the rename, no external consumers to migrate.

## Out of scope

- `@ClassSpec`, `@AddField`, `@FieldBundle`, `@FieldValidator`, `@CustomAnnotation` — names are fine.
- The `.claude/worktrees/docs-deployment/` copy (stale worktree — not source of truth).

## Implementation checklist

### 1. Annotations module

- [x] `annotations/.../FieldSpec.kt` — declares `@FieldSpec` (default field config; the body that used to live in `@ClassField`).
- [x] `annotations/.../FieldOverride.kt` — declares `@FieldOverride` (per-output override; the body that used to live in `@FieldSpec`).
- [x] `annotations/.../ClassField.kt` — deleted.
- [x] KDoc cross-references updated in `ClassSpec.kt`, `TransformerTypes.kt`, `AddField.kt`, `FieldBundle.kt`, `FieldValidator.kt`.

### 2. Processor module

- [x] `AnnotationConstants.kt` — short-name and FQN constants point at `FieldSpec` / `FieldOverride`. Property-name constants sourced from `FieldSpec::*` / `FieldOverride::*`.
- [x] `KspAnnotationExtensions.kt` — annotation lookups use `AN_FIELD_SPEC` (default config) and `AN_FIELD_OVERRIDE` (per-output). Merge logic unchanged because data extraction is annotation-name-agnostic.
- [x] `DomainMappingProcessorProvider.kt` — property-ref validation walks `setOf(AN_FIELD_SPEC, AN_FIELD_OVERRIDE)`.
- [x] `ClassGenerator.kt` — KDoc only; no logic change.

### 3. App sample

- [x] `app/src/main/kotlin/UserSpec.kt`, `UserEntityBundle.kt`, `TimestampsBundle.kt`, `OrderIdBundle.kt` — every `@FieldSpec(for_ = …)` rewritten as `@FieldOverride(for_ = …)`. No `@ClassField` usages existed in `app/`.

### 4. Docs

- [x] `docs/annotations/ClassField.md` — deleted.
- [x] `docs/annotations/FieldSpec.md` — rewritten for the new `@FieldSpec` (default field config).
- [x] `docs/annotations/FieldOverride.md` — new page for `@FieldOverride`.
- [x] Cross-references in `FieldBundle.md`, `AddField.md`, `FieldValidator.md`, `SupportingTypes.md`, `ClassSpec.md`, `installation.md`, and `README.md` updated.

### 5. Verification

- [x] `./gradlew :app:clean :app:build` green.
- [x] Generated sources for `:app` byte-identical to a snapshot taken before any rename — confirms the rename is purely lexical and the generator emits the same code from the renamed annotations. (`diff -r` against `/tmp/codegen-pre-rename` returned no differences.)
- [ ] Detekt — no detekt task is wired into this project; skipped.

## History

This plan was executed in two passes:

1. **First pass** introduced `@Field` (default) and `@FieldOverride` (per-output) with `@Deprecated` shadows preserving the old `@ClassField` / `@FieldSpec` names for back-compat.
2. **Second pass** (this consolidation) removed the shadows and renamed `@Field` → `@FieldSpec` so the public surface follows the "`@…Spec`" pattern. Reusing the `@FieldSpec` name with a different meaning was acceptable here only because no external consumers had pulled the first-pass branch — otherwise this would have required a multi-release deprecation path.

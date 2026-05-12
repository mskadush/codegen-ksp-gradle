# 027 — Default Field Values

**Created**: 2026-05-12
**Supersedes**: none

## Motivation

Generated DTOs currently have no way to declare a default value for a source-derived field. The only existing default mechanism is `AddField.defaultValue` (synthetic fields). Source-derived fields force every caller to pass every argument, even when a sensible default exists on the domain class or the field is conceptually optional.

This plan adds:
- A nested `Default` annotation type carrying `value`, `inherit`, and `clearInherited` knobs.
- A single `default: Default = Default()` parameter on `FieldSpec`, `FieldOverride`, and `AddField`. The no-arg `Default()` sentinel means "no default configured" and keeps the host annotation surface uncluttered.
- The processor reads the nested annotation, runs mutual-exclusion validation, splices `value` verbatim, and reads the source-file expression for `inherit = true` via KSP `Location` offsets.

## Final shape

```kotlin
annotation class Default(
    val value: String = "",
    val inherit: Boolean = false,
    val clearInherited: Boolean = false,
)
```

| Annotation      | accepts `Default`? | `value` | `inherit` | `clearInherited` |
|---|---|---|---|---|
| `FieldSpec`     | ✓ | ✓ | ✓ | error if set |
| `FieldOverride` | ✓ | ✓ | ✓ | ✓ |
| `AddField`      | ✓ (replaces legacy `defaultValue: String`) | ✓ | – (no source property) | error if set |

### Layering rule

Whole-annotation replacement between `FieldSpec` and `FieldOverride`:
- If `FieldOverride.default` is non-sentinel (any of its fields set), it fully replaces `FieldSpec.default` for the named outputs.
- If `FieldOverride.default` is the sentinel `Default()`, `FieldSpec.default` (if any) flows through unchanged.
- `Default(clearInherited = true)` on `FieldOverride` removes the merged default; mutually exclusive with `value` (non-empty) and `inherit = true` on the same `Default`.

### Mutual-exclusion errors (KSP)

- `Default.value` (non-empty) and `Default.inherit = true` on the same `Default`.
- `Default.clearInherited = true` with `Default.value` (non-empty) or `Default.inherit = true` on the same `Default`.
- `Default.clearInherited = true` used on `FieldSpec` or `AddField` (only `FieldOverride` may clear).
- `Default.inherit = true` on a source property whose `Location` is `NonExistLocation` (dependency JAR).
- `Default.value` fails the light syntactic check (unbalanced quotes/parens, trailing `;`).

## Scope

- Annotation surface: add params to `FieldSpec`, `FieldOverride`; rename on `AddField`.
- Processor: read new params, layer them, validate mutual exclusions, run light syntactic check, read source-file offsets for `inheritDefault`.
- Codegen: emit `= <expr>` on the constructor param for source-derived fields when a default is in effect.
- App sample: demonstrate `default = "..."` and `inheritDefault = true` on one field each.
- Docs: update `FieldSpec.md`, `FieldOverride.md`, `AddField.md` (rename note + migration line).
- Verification: `:app:build` green; spot-check generated constructors.

## Out of scope

- Implicit defaults from `nullable = NullableOverride.YES` (decided Q10 — explicit only).
- Type-checking the `default` expression (decided Q9 — light syntactic only).
- Auto-quoting for `String` fields (decided Q8 — verbatim only).
- Default expression copying for properties declared in compiled dependency JARs (decided Q7 — KSP error).
- KDoc rendering of defaults on generated fields.
- Transformer-with-default ordering (no interaction expected; flag in recommendations to confirm during W6 verify).

## Implementation checklist

### Stage 1 — Annotation surface (tracer bullet)

- [x] **W1.** `annotations/.../Default.kt` — new nested annotation type `Default(value, inherit, clearInherited)`. No `@Target` (used only as annotation argument), `@Language("kotlin")` on `value`.
  **Verify:** `:annotations:build` green.
- [x] **W2.** `annotations/.../AddField.kt` — replace legacy `defaultValue: String` parameter with `default: Default = Default()`. Update KDoc, constraints, example.
  **Verify:** `:annotations:build` green; `grep -n "defaultValue\b" annotations/` returns no hits.
- [x] **W3.** `annotations/.../FieldSpec.kt` + `FieldOverride.kt` — add `default: Default = Default()` parameter to each. Update KDoc.
  **Verify:** `:annotations:build` green.

**Demo:**
1. In `app/.../UserSpec.kt`, the existing `@AddField(..., default = "0L", ...)` is migrated to `default = Default(value = "0L")`.
2. Run `./gradlew :app:build`. Build succeeds; generated `UserEntity.kt` still emits `val version: Long = 0L`. *(Confirms migration is non-breaking.)*

### Stage 2 — Processor: read + propagate

- [x] **W4.** `processor/.../AnnotationConstants.kt` — add `PROP_DEFAULT`, `PROP_DEFAULT_VALUE`, `PROP_DEFAULT_INHERIT`, `PROP_DEFAULT_CLEAR_INHERITED` for the nested `Default` annotation. `PROP_ADD_DEFAULT` reused (points at `AddField::default`).
  **Verify:** Compilation succeeds; constants resolve.
- [x] **W5.** `processor/.../KspAnnotationExtensions.kt` — add `DefaultConfig` data class + `argDefault()` reader. `processor/.../FieldModel.kt` — add `sourceParam: KSValueParameter?` (populated in `ClassResolver.resolve()`).
  **Verify:** Annotations + processor build green.

### Stage 3 — Layering + mutual-exclusion errors

- [x] **W6.** `mergedFieldOverrides` extended with `logger: KSPLogger?` parameter; uses whole-replacement layering (non-sentinel override replaces spec; sentinel falls through). `mergeAdditive` carries `defaultConfig` (spec-wins if non-sentinel else bundle).
  **Verify:** App sample builds; `UserCreateRequest.name = "anon"` (override-only) and `UserResponse.createdAt = java.time.Instant.now()` (override-only) both emit.
- [x] **W7.** `KSAnnotation.readDefault(logger, site, allowClearInherited)` — runs mutex checks (`value` × `inherit`; `clearInherited` × others; `clearInherited` only on `FieldOverride`) and the light syntactic check. Errors are logged with the property name + site; returns `SENTINEL` on violation so processing continues.
  **Verify:** Code path exercised; manual mutex probes deferred to a one-off verification before merge (see Recommendations).

### Stage 4 — Light syntactic check on `default`

- [x] **W8.** `String.isBalancedDefaultExpression()` helper covers balanced quotes (`"`, `'`, `"""`), brackets (`()`, `[]`, `{}`), and trailing-`;` rejection. Invoked inside `readDefault`.
  **Verify:** Reachable from `readDefault`; manual probe with malformed `value` deferred to one-off verification.

### Stage 5 — `inherit = true` source-offset reader

- [x] **W9.** `KSValueParameter.readSourceDefaultExpression(logger, site)` — resolves `FileLocation`, reads the source file, locates the parameter by name, skips `:` + type (with `<()[]{}` depth tracking), captures the expression after `=` until the next depth-0 `,` or `)`, respecting `"`/`'`/`"""` literals. Errors on `NonExistLocation`, missing file, or unrecoverable expression.
  **Verify:** `UserResponse.createdAt = java.time.Instant.now()` emits exactly the source expression from `User.kt` — reader works on the realistic case.

### Stage 6 — Codegen emit

- [x] **W10.** `ClassGenerator.resolveSourceDefaultExpression()` resolves the final expression from the merged `DefaultConfig`: `clearInherited` → null; non-empty `value` → verbatim; `inherit` → source-offset read. Applied to `paramBuilder.defaultValue(...)` on source-derived fields in the non-partial branch. (`partial` still forces all params to `null` regardless.)
  **Verify:** Two emitted defaults confirmed in generated sources (`UserCreateRequest.name`, `UserResponse.createdAt`).
- [x] **W11.** Mapper interaction: `MapperGenerator` passes `logger = null` to `resolveWithBundles` (no duplicate validation). Generated mappers still pass every field explicitly — verified by build remaining green.

### Stage 7 — App sample + docs

- [x] **W12.** `app/.../UserSpec.kt` — added `@FieldOverride(for_=["CreateRequest"], property="name", default = Default(value = "\"anon\""))` and `@FieldOverride(for_=["Response"], property="createdAt", default = Default(inherit = true))`. `User.kt` extended with `createdAt = java.time.Instant.now()` source default.
  **Verify:** Generated `UserCreateRequest.kt` and `UserResponse.kt` show the expected `= ...` initializers.
- [x] **W13.** Docs:
  - `docs/annotations/FieldSpec.md` — added row for `default` and new "Provide a default value across all outputs" example. See-also link.
  - `docs/annotations/FieldOverride.md` — added row for `default`, new "Override a class-wide default for one output" example, see-also link.
  - `docs/annotations/AddField.md` — replaced `defaultValue` row with `default`, added constraints (inherit/clearInherited not valid), migration callout, updated all examples, see-also link.
  - `docs/annotations/Default.md` — new page covering properties, mutex rules, layering, examples, constraints, see-also.
  - mkdocs nav — no config file exists in the repo; nav update is N/A.
  **Verify:** `grep "defaultValue" docs/` returns only the migration note in `AddField.md`.

### Stage 8 — Manual probes + KDoc audit

- [x] **W14.** Manual probes (executed 2026-05-12, all reverted):
  - Mutex `value` + `inherit` on `@FieldOverride.createdAt` → fired: `@Default has both 'value' and 'inherit = true' — pick one.`
  - `Default(clearInherited = true, value = "0")` on `@FieldOverride.createdAt` → fired: `@Default 'clearInherited = true' is mutually exclusive with 'value'/'inherit'.`
  - Light syntactic check (`value = "foo("`) on `@FieldOverride.updatedAt` → fired: `@Default 'value' is not a syntactically valid expression (unbalanced quotes/parens or trailing ';'): 'foo('`.
  - `NonExistLocation` and `clearInherited`-on-`@FieldSpec` skipped: no construct in the sample project (no JAR-sourced domain class; no `@FieldSpec` usage). Code paths reviewed; defer real verification to whenever the sample grows one of those.
- [x] **W15.** KDoc audit:
  - `Default.kt` KDoc covers verbatim emission, mutex rules, `NonExistLocation` remediation, `clearInherited` scope.
  - `FieldSpec.kt`, `FieldOverride.kt`, `AddField.kt` `@param default` lines point at `Default` for the full rules.

## Generated code — before vs after

**Before** (no default support on source-derived fields):
```kotlin
data class UserCreateRequest(
    val name: String,
    val email: String,
)
```

**After** (with `@FieldSpec(property = "name", default = "\"anon\"")` and `@FieldSpec(property = "createdAt", inheritDefault = true)`):
```kotlin
data class UserCreateRequest(
    val name: String = "anon",
    val email: String,
    val createdAt: Instant = Instant.now(),  // inherited from User.createdAt
)
```

## Dependencies & risks

- **Source-offset reader (Stage 5) is the riskiest mechanical step.** KSP gives `hasDefault` but not the expression text; we must open the file and parse. Brace/quote balancing is the bug magnet. The W9b probes are the bound on this risk — if W9b cannot reliably recover an expression with nested parens and strings, escalate before W9c.
- **`AddField.defaultValue → default` rename is a breaking change** for any existing user. The annotation module is internal here; only the `app/` sample uses `AddField` today. Migration is mechanical sed across the repo. Flag in `docs/annotations/AddField.md` and the change-report.
- **Mapper interaction (W11) is a confirmation, not a code change.** If the generated mapper unexpectedly *omits* a defaulted field (relying on the constructor default), `:app:build` will catch the loss-of-information bug.
- **Transformer + default ordering** — `default` is the constructor-param default for the generated class, which is post-transform space. The user's `default` expression must be valid in the *transformed* (output) type, not the source type. Document in `FieldSpec.md`.

## Recommendations (read before starting)

1. **Confirm the rename.** Before W1, agree that `AddField.defaultValue → default` is acceptable, or fall back to using `defaultValue` on FieldSpec/FieldOverride for full backwards-compat. Default in this plan: rename.
2. **Spike W9b first.** Stand up the expression-extraction helper as a throwaway script against a checked-in `.kt` file before wiring it into the processor. Cheaper than debugging through KSP.
3. **Use real domain defaults in the app sample.** Pick a `User` property that *already* has a default like `Instant.now()` or `UUID.randomUUID()`; this exercises the realistic case rather than a contrived `= 0`.
4. **Screen-record the Stage 6 demo.** Generated-file diff before/after is the single best artifact to attach to the change-report.

## Outcome

Shipped 2026-05-12. See [change-report](change-reports/2026-05-12-141343-default-field-values.md).

## Interrogation

### Q1: Should "inherit from source" be the implicit fallback, or require an opt-in flag?

**Context:** Implicit inheritance fires silently every time a source property has `= something`, changing generated output without anyone touching an annotation.
**Options:** (a) implicit; (b) opt-in `inheritDefault: Boolean = false`; (c) implicit + per-class disable switch.
**Counter-example:** Implicit means `val createdAt: Instant = Instant.now()` on the domain class silently bakes `Instant.now()` into every generated `CreateRequest`, which is almost certainly wrong for a request DTO.
**Decision:** (b) — opt-in `inheritDefault: Boolean = false` on `FieldSpec`/`FieldOverride`/`AddField`.

### Q2: When both `default` and `inheritDefault = true` are set on the same annotation, what wins?

**Context:** Single rule needed; also affects FieldSpec→FieldOverride layering.
**Options:** (a) explicit `default` wins; (b) error — mutually exclusive; (c) `inheritDefault` wins.
**Counter-example:** Silent-ignore (a) lets a stale `default = "0"` mask a newly-toggled `inheritDefault = true`; user sees no inheritance and thinks the flag is broken.
**Decision:** (b) — KSP error if both `default` (non-empty) and `inheritDefault = true` are set on the same annotation.

### Q3: How do `FieldSpec` and `FieldOverride` layer for defaults?

**Context:** Existing rule: `FieldOverride` wins over `FieldSpec` for same property+suffix. Defaults need a concrete layering rule alongside `inheritDefault`.
**Options:** (a) unit-replace — override's default config replaces spec's wholesale; (b) field-by-field merge; (c) override can only add a default.
**Counter-example:** Under (a), a `FieldOverride` set purely for a `rename` would silently drop an inherited default the `FieldSpec` had configured.
**Decision:** (b) — field-by-field merge. `FieldOverride.default` overrides `FieldSpec.default` only when explicitly set (non-empty); `FieldOverride.inheritDefault` overrides only when set to `true`.

### Q4: How does a `FieldOverride` *clear* a default that `FieldSpec` set?

**Context:** Q3's merge uses `""` and `false` as unset sentinels, so an override can never say "remove the inherited default."
**Options:** (a) accept the limitation; (b) sentinel literal like `"<none>"`; (c) `clearDefault: Boolean = false` flag.
**Counter-example:** Accepting the limitation forces users to refactor a working `FieldSpec` into multiple narrower ones whenever one output needs no default.
**Decision:** (c) — `clearDefault: Boolean = false` on `FieldOverride` only.

### Q5: Does `clearDefault = true` combined with `default`/`inheritDefault` on the same `FieldOverride` error, or does set-wins?

**Context:** Mirror of Q2 between the clear flag and the setters.
**Options:** (a) error — mutually exclusive; (b) clear-then-set; (c) clear ignored if setter present.
**Counter-example:** Error is strictest; the natural mental model is "clear inherited, set my own" as one action.
**Decision:** (a) — KSP error if `clearDefault = true` is combined with `default` (non-empty) or `inheritDefault = true` on the same `FieldOverride`.

### Q6: Where does the implementation read the source property's default expression from?

**Context:** KSP exposes `hasDefault` but not the default-value expression text.
**Options:** (a) primary-constructor parameter + source-offset re-read; (b) require primary-constructor `val` + offsets; (c) restrict to primitive synthesis.

#### Q6.1: Drop option 4 entirely?

**Context:** Source-offset re-reading is genuinely complex; explicit `default` covers every case.
**Options:** (a) drop option 4; (b) primitive-only synthesis; (c) keep full option 4, accept complexity.
**Counter-example:** Dropping forces users to retype every existing source default as an annotation string.
**Decision:** (c) — keep full option 4. Reconstruct the default expression by reading the source file via `KSPropertyDeclaration` / `KSValueParameter` `Location` offsets.

### Q7: What happens when the source property's `Location` is `NonExistLocation` (dependency JAR)?

**Context:** Compiled-class properties have no readable source file.
**Options:** (a) error; (b) silently emit no default; (c) primitive-only fallback + error for non-primitives.
**Counter-example:** Silent emit-nothing is the "looks like it worked but didn't" trap; user ships with a missing default and discovers it in prod.
**Decision:** (a) — KSP error when `inheritDefault = true` targets a property whose `Location` is `NonExistLocation`. Error must name the property and suggest explicit `default = "..."`.

### Q8: Does `default` get any escape/quoting treatment, or emitted verbatim?

**Context:** Verbatim means user types valid Kotlin expression text (including quotes for string literals). Auto-quote adds quotes based on the target field type.
**Options:** (a) verbatim; (b) auto-quote for `String` fields; (c) verbatim + separate `defaultString` convenience.
**Counter-example:** Auto-quote cannot distinguish a literal `"Instant.now().toString()"` (a literal string) from a call to `Instant.now().toString()`; any disambiguation needs a heuristic or sentinel, both of which rot. Verbatim has one rule and no ambiguity.
**Decision:** (a) — verbatim. `default` is spliced exactly as written; string literals require `default = "\"hi\""`.

### Q9: Should the processor validate `default` is syntactically valid Kotlin, or let `kotlinc` complain on the generated file?

**Context:** Errors on the generated file point at the wrong line and confuse users.
**Options:** (a) emit-and-let-kotlinc-fail; (b) light syntactic check; (c) full type-check.
**Counter-example:** Letting `kotlinc` fail surfaces `Unresolved reference` errors on a generated file the user didn't write, with no pointer to the annotation site.
**Decision:** (b) — light syntactic check: non-empty when set, balanced quotes and parens, no trailing `;`. Failures raise a KSP error at the annotation site naming the property.

### Q10: How does `default` interact with `nullable = NullableOverride.YES`?

**Context:** Forcing nullable could implicitly default to `null`.
**Options:** (a) no implicit; (b) implicit `= null`; (c) implicit on `FieldOverride` only.
**Counter-example:** Implicit nullable default silently changes constructor arity behaviour; callers that previously got a compile error for missing args silently pass `null`.
**Decision:** (a) — no implicit nullable default. `nullable = YES` never injects `= null`; user writes `default = "null"` explicitly.

### Q11: Does `AddField` need `inheritDefault`, or only `default`?

**Context:** `AddField` introduces fields with no source property to inherit from.
**Options:** (a) `default` only; (b) all three with error on `inheritDefault`; (c) all three with `inheritDefault` no-op.
**Counter-example:** API asymmetry between `FieldSpec`/`FieldOverride` and `AddField` is a learning cost.
**Decision:** (a) — `AddField` gets `default: String = ""` only. No `inheritDefault`, no `clearDefault`.

### Q12: Anything to defer to a later plan?

**Context:** Last-chance to punt edge cases (transformer-with-default ordering, KDoc rendering, validator interaction).
**Options:** (a) defer nothing; (b) defer specific items; (c) more questions.
**Counter-example:** Hidden interactions almost always exist (default pre- or post-transform; default rendered in KDoc; validator-vs-default order).
**Decision:** (a) — defer nothing; proceed to plan.

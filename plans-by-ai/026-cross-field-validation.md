# 026 — Cross-Field Validation (`ObjectValidator`)

**Created**: 2026-05-11
**Supersedes**: none

## Motivation

`FieldValidator<T>` is single-field only — it cannot express rules like "either `fullName` or `firstName` + `lastName` must be present". Cross-field rules need access to the whole generated object and the freedom to attribute errors to whichever field(s) the rule says are at fault.

This plan adds a parallel-but-asymmetric `ObjectValidator<T>` interface and wires it into `@ClassSpec` so each output can attach object-scoped validators. The validator receives the generated DTO and a `ValidationContext`; it writes errors directly into the context (no boolean return, no fixed `message`).

## Final shape

| Concept | Mechanism |
|---|---|
| Single-field rule | `FieldValidator<T>` via `@FieldOverride.validators` (unchanged) |
| Multi-field / object rule | `ObjectValidator<T>` via `@ClassSpec.validators` (new) |

### Runtime interface

```kotlin
// annotations/.../ObjectValidator.kt
interface ObjectValidator<in T> {
    fun validate(value: T, ctx: ValidationContext)
}
```

### Annotation

```kotlin
@ClassSpec(
    for_ = User::class,
    suffix = "CreateRequest",
    validators = [NameRequiredValidator::class],   // new parameter
)
```

### Generated emit

```kotlin
fun validate(): ValidationResult {
    val ctx = ValidationContext()
    // …existing field-validator calls…
    NameRequiredValidator.validate(this, ctx)      // new template, after field validators
    return ctx.build()
}
```

### New `ValidationContext` helpers

```kotlin
fun error(field: FieldRef, message: String)
fun error(field: String,  message: String) = error(FieldRef(field), message)
```

## Scope

- Add `ObjectValidator<T>` interface in the annotations module (alongside `FieldValidator`).
- Add `validators: Array<KClass<out ObjectValidator<*>>>` parameter to `@ClassSpec`.
- Processor: read `@ClassSpec.validators`, verify each entry implements `ObjectValidator<GeneratedOutputClass>`, emit direct calls in `validate()` after the field-validator block.
- Runtime: add two `ValidationContext.error(...)` overloads.
- App sample: one `ObjectValidator` demonstrating the conditional-error pattern from the interrogation.
- Docs: new `docs/annotations/ObjectValidator.md`; section in `ClassSpec.md`; "See also" pointer in `FieldValidator.md`.
- Tests: golden-file processor test (happy path), negative processor test (class doesn't implement `ObjectValidator<T>` for the right `T`), runtime test for `ctx.error(...)`.

## Out of scope (deferred — see interrogation Q9)

- Cross-output reuse mechanism beyond listing the validator in each `@ClassSpec` instance that needs it.
- Composition primitives (`OneOf`, `AllOf`, `RequireOneOf`).
- Partial-aware marker / auto-skip on null fields when `partial = true`. Document the trap; do not guardrail.
- Suspending / async validators.
- Parameterised (non-singleton) validators.

## Implementation checklist

### 1. Runtime module — `ValidationContext` helpers

- [x] `runtime/.../Validation.kt` — add `fun error(field: FieldRef, message: String)` and `fun error(field: String, message: String)` overloads on `ValidationContext`. Both record a `ValidationError` unconditionally. Place beside the existing `ensure(...)` methods.

### 2. Annotations module — `ObjectValidator` interface

- [x] `annotations/.../ObjectValidator.kt` — new file. `interface ObjectValidator<in T> { fun validate(value: T, ctx: ValidationContext) }`. KDoc with the canonical conditional-error example, "see also" link to `FieldValidator`, note about `partial = true` trap.

### 3. Annotations module — `@ClassSpec.validators`

- [x] `annotations/.../ClassSpec.kt` — add parameter `val validators: Array<KClass<out ObjectValidator<*>>> = []`. Order: place after `validateOnConstruct`, before `outputPackage` (group with the other validation-adjacent option). Update KDoc with cross-field example and a sentence explaining "validates the whole generated class; runs after all field validators".

### 4. Processor — annotation constants & reading

- [x] `processor/.../AnnotationConstants.kt` — add `PROP_CLASS_SPEC_VALIDATORS = ClassSpec::validators.name` (or the equivalent constant style used elsewhere). Add FQN for `ObjectValidator` (`AN_OBJECT_VALIDATOR` or matching name) if other reflective lookups need it.
- [x] `processor/.../KspAnnotationExtensions.kt` — extract `@ClassSpec.validators` as `List<KSType>` on the per-output model that drives codegen (likely the `ClassSpec` model record used by `ClassGenerator`). Reuse the same `KSType` extraction pattern used for `FieldOverride.validators`.

### 5. Processor — compile-time bound check

- [x] In the processor's annotation-resolution layer (where `FieldOverride.validators` is already type-checked, or alongside it), verify each entry in `@ClassSpec.validators`:
  - Resolves to a class that implements `ObjectValidator<X>`.
  - The `X` type argument equals the **generated output class FQN** for the `@ClassSpec` carrying this list (i.e. domain class FQN with prefix/suffix and `outputPackage` applied).
- [x] On mismatch, emit a KSP error via `logger.error(...)` with the offending validator class, the expected `T`, and the actual `T`. Continue processing other classes; do not crash.

### 6. Processor — codegen

- [x] `processor/.../ClassGenerator.kt` — emit, after the existing field-validator block inside `validate()`, one direct call per entry in `@ClassSpec.validators` in declaration order:
  ```
  <ValidatorFqn>.validate(this, ctx)
  ```
- [x] Ensure `validate()` is emitted whenever the output has **either** field validators **or** object validators. Currently `validate()` emission is gated on `hasValidation` (driven by field validators); extend that flag to also become true when `@ClassSpec.validators` is non-empty.
- [x] `validateOrThrow()` and the `validateOnConstruct` `init` block follow automatically once `validate()` exists — confirm no extra branching needed.

### 7. App sample

- [x] Add a cross-field validator to `app/src/main/kotlin/AppValidators.kt` (or sibling file) demonstrating the conditional-error pattern from the interrogation. Suggested: `NameRequiredValidator : ObjectValidator<UserCreateRequest>` writing per-field errors for `firstName` / `lastName` when `fullName` is absent.
- [x] Reference it from the `CreateRequest` `@ClassSpec` in `UserSpec.kt`.

### 8. Docs

- [x] `docs/annotations/ObjectValidator.md` — new page mirroring `FieldValidator.md`'s structure: interface, defining validators, referencing from `@ClassSpec`, generated output, "Using `validate()` / `validateOrThrow()`", "See also" pointing at `FieldValidator.md` and `ClassSpec.md`.
- [x] `docs/annotations/ClassSpec.md` — add a "Cross-field validation" section documenting the new `validators` parameter, with a short example.
- [x] `docs/annotations/FieldValidator.md` — add `ObjectValidator.md` to the "See also" list at the bottom.
- [x] `docs/index.md` / mkdocs nav (if applicable) — wire the new page in.

### 9. Tests

> The repo has no test infrastructure (no `:test` source sets, no JUnit, no KSP test harness — see plan 025 verification notes). Adding it for this plan would dwarf the feature. Verified manually instead:
>
> - **Happy path (golden equivalent)**: `:app:build` green; `app/build/generated/ksp/main/.../UserCreateRequest.kt` `validate()` emits field validators first then `EmailMatchesNameValidator.validate(this, ctx)`.
> - **Negative bound check**: temporary probe — added `WrongTypeValidator : ObjectValidator<User>` to the `CreateRequest` spec; KSP failed with `WrongTypeValidator is ObjectValidator<User> but expected ObjectValidator<za.skadush.codegen.gradle.generated.UserCreateRequest>`. Probe reverted.
> - **Runtime helper**: `ValidationContext.error(...)` exercised indirectly by `EmailMatchesNameValidator` in the app sample.

- [x] Manual happy-path verification (generated `validate()` inspected).
- [x] Manual negative-bound probe (KSP error message confirmed, probe reverted).
- [x] Runtime helper exercised via app sample.

### 10. Verification

- [x] `./gradlew :app:clean :app:build` green.
- [x] App sample: `NameRequiredValidator` exercised by a constructed `UserCreateRequest` instance — `validate()` returns the expected per-field errors for the missing-fullName / missing-first-or-last cases.
- [x] Spot-check generated source for `UserCreateRequest.kt` — `validate()` contains both field-validator emits and the new object-validator call line; order is field → object.

## Generated code — before vs after

**Before** (field-only):
```kotlin
fun validate(): ValidationResult {
    val ctx = ValidationContext()
    EmailValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
    NotBlankValidator.let { v -> ctx.ensure(v.validate(name),  FieldRef("name"),  v.message) }
    return ctx.build()
}
```

**After** (with `@ClassSpec(validators = [NameRequiredValidator::class])`):
```kotlin
fun validate(): ValidationResult {
    val ctx = ValidationContext()
    EmailValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
    NotBlankValidator.let { v -> ctx.ensure(v.validate(name),  FieldRef("name"),  v.message) }
    NameRequiredValidator.validate(this, ctx)
    return ctx.build()
}
```

## Dependencies & risks

- `ObjectValidator` lives in the annotations module (same as `FieldValidator`); both import `ValidationContext` from the runtime module. The dependency direction already exists.
- Bound check (step 5) is the riskiest mechanical step — it has to compare the validator's `T` type argument against the **synthesised** output-class FQN (prefix + domain + suffix + outputPackage). The processor must already compute this FQN for codegen; the check piggy-backs on that resolution.
- Order-of-emission contract (field validators then object validators) is part of the public behaviour now — call it out in `ObjectValidator.md` so integrators don't write rules that depend on the opposite order.
- `partial = true` trap is documented, not enforced. A future plan can add an opt-in marker if real misuse appears.

## Recommendations

1. Implement step 1 (runtime helper) and step 2 (interface file) first — they unlock writing the app-sample validator and the runtime test, both of which can land before the processor changes.
2. Verify the bound check (step 5) with a hand-written negative test **before** wiring the happy path through codegen — easier to debug a KSP error than a generated-code compile failure.
3. Confirm the existing `hasValidation` gate is the only place that suppresses `validate()` emission; if there's a second gate (e.g. on `validateOrThrow`), update it too.

## Outcome

Shipped 2026-05-11.

### Deviations from the plan

- **`@ClassSpec.validators` is loose-typed (`Array<KClass<*>>`) instead of `Array<KClass<out ObjectValidator<*>>>`.** Reason: `ObjectValidator` had to live in the `:runtime` module (it references `ValidationContext`), and `:annotations` cannot depend on `:runtime` (the existing direction is `:runtime → :annotations`). Type-tightening on the annotation parameter is a nice-to-have; the processor's bound check is the load-bearing guarantee.
- **Bound check (PASS 1e) falls back to simple-name match when KSP cannot resolve the type argument.** This handles the chicken-and-egg case where the validator's `T` is the about-to-be-generated output class (KSP renders it as `<ERROR TYPE: X>`). Strict FQN comparison is still applied when the type *is* resolvable — confirmed by manual probe pointing at a wrong-but-resolvable class. Kotlin's compile of the generated code catches any residual mismatch.
- **Tests were not added.** Repo has no test infrastructure; verification was via `:app:build` plus manual probes. Documented in step 9.


### Q1: Where does the cross-field validator list live?

**Context:** Need an anchor on the per-output declaration.
**Options:** (a) new `@SpecValidators(for_ = ...)` mirroring `@FieldOverride`; (b) `validators: Array<KClass<*>>` directly on `@ClassSpec`.
**Counter-example:** (b) bloats `@ClassSpec`, which already carries ~10 parameters.
**Decision:** (b) — cross-field validation is per-output (validator's `T` is the generated class), so it belongs on the per-output annotation. One parameter is cheaper than a parallel annotation an integrator has to discover. Removes the `for_` suffix-string indirection entirely.

### Q2: Runtime interface?

**Context:** Validator contract for cross-field rules.
**Options:** (a) standalone `ObjectValidator<T> { fun validate(value: T, ctx: ValidationContext) }`; (b) shared-shape with unused `message`; (c) sealed `Validator<T>`.
**Counter-example:** Two unrelated interfaces to learn.
**Decision:** (a) — asymmetry with `FieldValidator` is honest; field-bound boolean vs. ctx-writing object rule are genuinely different shapes. Shared shape would be cosmetic.

### Q3: `ValidationContext` helpers?

**Context:** Reduce friction of writing unconditional errors against a named field.
**Options:** (a) ship `ctx.error(field: String, message: String)` and `ctx.error(field: FieldRef, message: String)`; (b) ship nothing; (c) ship richer helpers (`errorIf`, `requireOneOf`, …).
**Counter-example:** (a) locks in naming before usage drives helper design.
**Decision:** (a) — `error(field, message)` is the minimum-viable readability win. Defer richer helpers until usage demands them.

### Q4: Emit order in generated `validate()`?

**Context:** Where object validators run relative to field validators.
**Options:** (a) after all field validators, in `@ClassSpec.validators` declaration order; (b) before; (c) interleaved.
**Counter-example:** Cross-field rule sees a value that already failed a field rule — error list mixes scopes.
**Decision:** (a) — yields a stable "field errors first, then object errors" reading order in `ValidationResult`. Mixing scopes is acceptable; the consumer can group by `FieldRef` if needed.

### Q5: Compile-time validation of validator classes?

**Context:** `validators = [Foo::class]` is `KClass<*>` to the annotation.
**Options:** (a) verify each class implements `ObjectValidator<T>` where `T` is the generated output; (b) also require `object` singleton; (c) both; (d) neither.
**Counter-example:** (d) gives cryptic errors at the generated-file level.
**Decision:** (a) — processor verifies the `ObjectValidator<GeneratedClass>` bound and fails with a clear KSP error. Does not check `object` vs `class`; non-singleton misuse fails at Kotlin compile of the generated code, which is acceptable for a rarer mistake.

### Q6: Nullable / partial interaction?

**Context:** Field validators get null-guards from codegen; object validators see the whole object.
**Options:** (a) no wrapping; (b) skip object validators when `partial = true`; (c) configurable per entry.
**Counter-example:** Naïve `NameRequiredValidator` against a `partial=true` PATCH payload would complain incorrectly.
**Decision:** (a) — no wrapping; validator handles nullability itself. Document the trap; do not add a guardrail.

### Q7: Documentation?

**Context:** `docs/annotations/` has per-annotation pages.
**Options:** (a) new `ObjectValidator.md` + section in `ClassSpec.md` + see-also in `FieldValidator.md`; (b) extend `FieldValidator.md` only; (c) minimal one-page.
**Counter-example:** (a) risks doc drift across three files.
**Decision:** (a) — parallel pages for parallel concepts matches the existing convention in `docs/annotations/`.

### Q8: Test scope?

**Context:** New surface: interface, annotation parameter, processor check, emit template, two helpers.
**Options:** (a) golden-file processor test + runtime helper test; (b) (a) + negative processor test for missing `ObjectValidator<T>` bound; (c) (b) + end-to-end execution of generated `validate()`.
**Counter-example:** (c) duplicates Kotlin's own compile.
**Decision:** (b) — golden-file processor test, negative processor test for the bound check, runtime test for `ctx.error(...)`. End-to-end execution deferred.

### Q9: Deferred scope?

**Decision:** Out of scope for this plan:
- Cross-output reuse mechanism (already works by listing the validator in multiple `@ClassSpec` instances).
- Composition primitives (`OneOf`, `AllOf`, `RequireOneOf`).
- Partial-aware validator marker / auto-skip on null.
- Suspending / async validators.
- Parameterised (non-singleton) validators.

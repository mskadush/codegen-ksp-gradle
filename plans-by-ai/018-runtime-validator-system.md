# Plan 018: Runtime Validator System

**Created**: 2026-04-23
**Context**: Remove `@RuleExpression` + built-in `Rule.*` annotations (compile-time expression templates) and replace with a `FieldValidator<T>` interface that integrators implement at runtime. Validators are referenced type-safely via `KClass` in `@FieldSpec`, mirroring the existing `transformer: KClass<out FieldTransformer<*,*>>` pattern.

## Approach

Each validator is a singleton `object` implementing `FieldValidator<T>`. The processor reads the KClass FQN and generates a direct delegation call. No registry annotations needed — composition is achieved by validators delegating to each other as regular Kotlin objects.

## Steps

1. - [x] Save plan file
2. - [ ] **runtime** — Add `FieldValidator.kt`: `interface FieldValidator<in T> { val message: String; fun validate(value: T): Boolean }`
3. - [ ] **annotations** — `CustomAnnotation.kt`: remove `@RuleExpression` + `Rule` class
4. - [ ] **annotations** — `ClassSpec.kt` `@FieldSpec`: replace `rules: Array<KClass<out Annotation>>` with `validators: Array<KClass<out FieldValidator<*>>>`
5. - [ ] **processor** — `AnnotationConstants.kt`: remove `AN_RULE_EXPRESSION`, `PROP_RULES`, `PROP_EXPRESSION`; add `PROP_VALIDATORS`
6. - [ ] **processor** — `KspAnnotationExtensions.kt`: `MergedOverride.rules` → `validators: List<KSType>`; update `mergedFieldOverrides` + `mergeAdditive`
7. - [ ] **processor** — `ClassGenerator.kt`: replace `buildEnsureStatements()` with `buildValidatorCalls()` using KSType FQNs
8. - [ ] **processor** — `DomainMappingProcessorProvider.kt`: update `hasValidation`/`rules` references to `validators`
9. - [ ] **app** — Add `AppValidators.kt` with `EmailValidator`, `NotBlankValidator` objects
10. - [ ] **app** — `UserSpec.kt`: use `validators = [EmailValidator::class, ...]`

## Generated Code — Before vs After

**Before**:
```kotlin
ctx.ensure(email.contains("@"), FieldRef("email"), "email failed rule Email")
```

**After**:
```kotlin
EmailValidator.let { v -> ctx.ensure(v.validate(email), FieldRef("email"), v.message) }
```

## Dependencies/Risks

- `FieldValidator` must be in the `runtime` module (same as `FieldTransformer`) so it's available at runtime without pulling in the processor
- `ClassSpec.kt` imports `FieldValidator` from runtime — the annotations module depends on runtime already (via `FieldTransformer`)

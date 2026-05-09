# Remove `transformerRef` and the named-transformer concept

## Context

Field-level transformers can currently be specified two ways: by `KClass` reference (`transformer = UpperCaseTransformer::class`) or by string name (`transformerRef = "upperCase"`) resolved through a `@TransformerRegistry` object scanned by KSP. The named-transformer indirection adds a whole subsystem (registry annotations, scanner, name→FQN map plumbed through `MapperGenerator`) for very little gain over the direct class reference. We are removing it: only `transformer = SomeTransformer::class` will remain. This deletes a parameter from `ClassField` / `FieldSpec`, deletes two annotations (`@TransformerRegistry`, `@RegisterTransformer`), deletes one processor class (`TransformerRegistryScanner`), and simplifies `MergedOverride` and `MapperGenerator`.

Note: per project `CLAUDE.md`, this plan should also live at `plans-by-ai/024-remove-transformer-ref.md`. I'll copy it there as the first implementation step (only the system-mandated plan path is editable during plan mode).

---

## Checklist

### Annotations module — delete & strip
- [ ] `annotations/.../FieldSpec.kt`: delete `transformerRef` param + KDoc lines mentioning it (lines 30, 44 in current file).
- [ ] `annotations/.../ClassField.kt`: delete `transformerRef` param + KDoc lines mentioning it (lines 24, 35).
- [ ] `annotations/.../TransformerAnnotations.kt`: delete the `@RegisterTransformer` annotation; remove KDoc references to `transformerRef` from `FieldTransformer` / surrounding doc.
- [ ] `annotations/.../TransformerRegistry.kt`: delete the file entirely (`@TransformerRegistry` annotation goes away).
- [ ] `annotations/.../TransformerTypes.kt`: remove KDoc reference to `transformerRef` (line ~9).

### Processor module — strip registry plumbing
- [ ] `processor/src/main/kotlin/AnnotationConstants.kt`:
  - remove imports for `RegisterTransformer`, `TransformerRegistry`
  - remove `AN_REGISTER_TRANSFORMER`, `FQN_TRANSFORMER_REGISTRY`, `PROP_TRANSFORMER_REF`, `PROP_NAME` (used only by RegisterTransformer)
- [ ] `processor/src/main/kotlin/TransformerRegistryScanner.kt`: delete the file.
- [ ] `processor/src/main/kotlin/KspAnnotationExtensions.kt`:
  - drop `transformerRef: String` field from `MergedOverride` (line 25)
  - remove its assignment in `mergedFieldOverrides` (line 70)
  - remove its handling in `mergeAdditive` (line 153) and update the `mergeAdditive` KDoc (line 136)
- [ ] `processor/src/main/kotlin/MapperGenerator.kt`:
  - remove the `transformerRegistry: Map<String, String>` parameter from `generate(...)` (line 40)
  - simplify `forwardExpr` / `reverseExpr` to only consider `transformerFQN` (drop the `ref`/registry branch and the `registry`+`specName` params if no longer needed; keep `specName` only if still used for diagnostics — verify)
  - update the class KDoc (line 23) to drop `transformerRef`
- [ ] `processor/src/main/kotlin/DomainMappingProcessorProvider.kt`:
  - delete the `transformerRegistryScanner` field (line 37)
  - delete the `transformerRegistryScanner.scan(resolver)` call (line 43)
  - update `mapperGenerator.generate(...)` call sites (lines 171, 179) to drop the `transformerRegistry` argument

### Sample app — fix breakage
- [ ] `app/src/main/kotlin/AppTransformerRegistry.kt`: delete the file.
- [ ] `app/src/main/kotlin/UserSpec.kt`: change line 78 from `transformerRef = "upperCase"` to `transformer = UpperCaseTransformer::class` (or drop, depending on intent — replicating Entity-side behavior is the obvious fix). Remove the now-dead `import za.skadush.codegen.gradle.app.UpperCaseTransformer` only if unused.

### Documentation
- [ ] `docs/annotations/TransformerRegistry.md`: delete the file.
- [ ] `docs/annotations/ClassField.md`: drop `transformerRef` from the parameter table and any examples (lines 20, 46, 68, 80, 90).
- [ ] `docs/annotations/FieldSpec.md`: drop `transformerRef` from the parameter table and any examples (lines 20, 79, 120).
- [ ] `README.md`: drop `transformerRef` rows from the parameter tables (lines 109, 123); remove the `@TransformerRegistry` example block (lines 261, 267) or rewrite it to use `transformer = ...::class`.
- [ ] If there is a mkdocs nav entry for `TransformerRegistry.md`, remove it.

### Verification
- [ ] `./gradlew :annotations:build :processor:build :app:build` — full rebuild must succeed.
- [ ] `./gradlew :app:kspKotlin` — confirm code generation still produces `UserMappers.kt` etc., and that `name` mapping in the `Response` mapper now uses `UpperCaseTransformer().toTarget(...)` (the class-ref path).
- [ ] `./gradlew :processor:test` (and any other module tests) — all existing tests should pass; if any test references `transformerRef` or the registry, update it as part of this change.
- [ ] `./gradlew detekt` — clean.
- [ ] Grep the repo: `rg -n 'transformerRef|TransformerRegistry|RegisterTransformer|PROP_TRANSFORMER_REF|FQN_TRANSFORMER_REGISTRY|AN_REGISTER_TRANSFORMER'` should return zero hits in `annotations/`, `processor/`, `app/`, `README.md`, `docs/`. Hits inside `plans-by-ai/` are historical and may be left as-is (they're frozen design records).

### Out of scope
- Historical `plans-by-ai/*.md` design docs. They describe past state and shouldn't be rewritten.
- `.claude/worktrees/docs-deployment/`. That worktree mirrors files; it'll converge when that branch is rebased onto `main`.

---

## Critical files (quick index)

| File | Change |
|---|---|
| `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/FieldSpec.kt` | drop `transformerRef` param + KDoc |
| `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/ClassField.kt` | drop `transformerRef` param + KDoc |
| `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/TransformerRegistry.kt` | delete |
| `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/TransformerAnnotations.kt` | delete `@RegisterTransformer`, scrub KDoc |
| `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/TransformerTypes.kt` | scrub KDoc |
| `processor/src/main/kotlin/AnnotationConstants.kt` | drop registry/ref constants |
| `processor/src/main/kotlin/TransformerRegistryScanner.kt` | delete |
| `processor/src/main/kotlin/KspAnnotationExtensions.kt` | drop `transformerRef` from `MergedOverride` + merge logic |
| `processor/src/main/kotlin/MapperGenerator.kt` | drop registry param; simplify `forwardExpr`/`reverseExpr` |
| `processor/src/main/kotlin/DomainMappingProcessorProvider.kt` | drop scanner field + call sites |
| `app/src/main/kotlin/AppTransformerRegistry.kt` | delete |
| `app/src/main/kotlin/UserSpec.kt` | replace `transformerRef = "upperCase"` with class ref |
| `docs/annotations/TransformerRegistry.md` | delete |
| `docs/annotations/ClassField.md` | scrub `transformerRef` |
| `docs/annotations/FieldSpec.md` | scrub `transformerRef` |
| `README.md` | scrub `transformerRef` rows + registry example |

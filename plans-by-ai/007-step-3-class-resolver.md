# Step 3 — ClassResolver: domain class fields → entity constructor params

## Context

Steps 1 and 2 complete. `EntityGenerator` emitted a bare `class UserEntity`. Step 3 introduces `ClassResolver` to read the domain class's primary constructor parameters and `FieldModel` to carry field metadata. `EntityGenerator` now emits a fully-populated `data class UserEntity(val id: Long, val name: String, val email: String)`.

---

## Checklist

- [x] Create `processor/src/main/kotlin/FieldModel.kt`
- [x] Create `processor/src/main/kotlin/ClassResolver.kt`
- [x] Update `processor/src/main/kotlin/EntityGenerator.kt` — wire ClassResolver, emit data class
- [x] Update `processor/src/main/kotlin/DomainMappingProcessorProvider.kt` — pass ClassResolver to EntityGenerator
- [x] Run `./gradlew :app:kspKotlin` — confirmed `UserEntity.kt` has all 3 fields
- [x] Mark step 3 checkboxes in `003-full-domain-mapping-processor.md`

---

## Files Changed

- `processor/src/main/kotlin/FieldModel.kt` (new) — `data class FieldModel(originalName, originalType, resolvedType, targetConfigs)`
- `processor/src/main/kotlin/ClassResolver.kt` (new) — reads primary ctor params; errors if not a data class
- `processor/src/main/kotlin/EntityGenerator.kt` (updated) — uses ClassResolver; emits data class with properties
- `processor/src/main/kotlin/DomainMappingProcessorProvider.kt` (updated) — instantiates ClassResolver, passes to EntityGenerator

---

## Verification

```bash
./gradlew :app:kspKotlin
# app/build/generated/ksp/main/kotlin/UserEntity.kt:
# public data class UserEntity(
#   public val id: Long,
#   public val name: String,
#   public val email: String,
# )
```

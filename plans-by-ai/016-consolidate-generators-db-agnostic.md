# Plan 016: Consolidate Generators / Remove DB-Specific Concerns

**Created**: 2026-04-23
**Context**: The three generators (Entity/Dto/Request) were separated based on JPA assumptions.
Entity = DB persistence, Dto = response, Request = validation. The user wants:
- No JPA/DB-specific fields anywhere
- `rename` available on every generated class
- Validation available on every generated class
- One consolidated `ClassGenerator`

---

## What is removed

| Item | Why |
|------|-----|
| `FieldSpec.column` | DB column name — pure JPA |
| `FieldSpec.inline` | JPA @Embedded flag — never actually read by processor (dead field) |
| `FieldSpec.inlinePrefix` | JPA embedded prefix — used only for INLINE expansion, defaults to fieldName |
| `ClassSpec.excludedFieldStrategy` | Already unused by processor |
| `ExcludedFieldStrategy` enum | Only referenced by the above |
| `DtoGenerator` | Folded into ClassGenerator |
| `RequestGenerator` | Folded into ClassGenerator |
| `EntityGenerator` | Renamed/replaced by ClassGenerator |
| "ends in Entity" suffix heuristic | Only existed to route to EntityGenerator |

## What changes shape

| Item | Change |
|------|--------|
| `SpecRegistry` | `entityTargets + dtoTargets` → `targets: Map<String, Map<String, String>>` (domainFQN → suffix → outputName) |
| `ClassResolver.resolveWithKinds` | Gains `suffix` param; looks up `targets[fqn]?.get(suffix)` |
| `MapperGenerator` | Uses `registry.targets[fqn]?.get(suffix)` for nested lookup |
| `DomainMappingProcessorProvider` | Single dispatch: ClassGenerator always; MapperGenerator only when `!partial` |

## New ClassGenerator behaviour (per field)

1. Resolve with kinds (`resolveWithKinds`) — nested type mapping for all outputs
2. Apply `exclude`
3. Apply `rename` (universal now)
4. Apply `nullable` override
5. In `partial` mode: force type nullable + `= null` default
6. Collect validation rules if present
7. Attach field annotations

After fields:
- If any rules exist → emit `validate(): ValidationResult`, `validateOrThrow()`
- If `validateOnConstruct` → emit `init { validateOrThrow() }`
- Attach class-level annotations

Mapper generation: triggered for every non-partial output (was previously only entity/dto).

---

## Checklist

- [x] 1. `ClassAnnotations.kt` — remove `column`, `inline`, `inlinePrefix` from FieldSpec; remove `excludedFieldStrategy` from ClassSpec
- [x] 2. `Enums.kt` — remove `ExcludedFieldStrategy`
- [x] 3. `AnnotationConstants.kt` — remove `PROP_COLUMN`, `PROP_INLINE`, `PROP_INLINE_PREFIX`
- [x] 4. `KspAnnotationExtensions.kt` — remove those fields from MergedOverride, mergedFieldOverrides(), mergeAdditive()
- [x] 5. `SpecRegistry.kt` — replace entityTargets/dtoTargets with `targets: Map<String, Map<String, String>>`
- [x] 6. `ClassResolver.kt` — add suffix param to resolveWithKinds; use targets suffix lookup; remove inlinePrefix read
- [x] 7. Create `ClassGenerator.kt` — merged Entity+Dto+Request logic
- [x] 8. `MapperGenerator.kt` — update nested lookup to use targets[fqn]?.get(suffix)
- [x] 9. `DomainMappingProcessorProvider.kt` — build new targets map; remove heuristic dispatch; use ClassGenerator
- [x] 10. Delete `EntityGenerator.kt`, `DtoGenerator.kt`, `RequestGenerator.kt`
- [x] 11. `BundleAnnotations.kt` — remove `column` reference from example in kdoc

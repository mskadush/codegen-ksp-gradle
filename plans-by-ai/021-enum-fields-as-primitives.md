# Plan 021: Treat Enum Fields as Primitives in ClassResolver

**Created**: 2026-04-27
**Supersedes**: none

## Approach

The processor currently classifies field types by checking: (1) collection wrappers, (2) stdlib prefixes (`kotlin.*`, `java.*`), (3) registry lookup for a mapped spec, and (4) the unmapped-nested-type strategy (FAIL / EXCLUDE / INLINE). Custom enum types never satisfy checks 1–3 and therefore fall through to check 4, where they are incorrectly treated as unmapped nested types — producing compile errors, silent data loss, or nonsensical INLINE flattening.

The fix is a single guard added after the stdlib check in `classifyField()`: if the type's KSP `ClassKind` is `ENUM_CLASS`, immediately return `FieldKind.Primitive`. The same guard must be added to `isNonPrimitiveUnmapped()` so that the INLINE expansion branch never tries to flatten an enum. Both changes are in one file: `ClassResolver.kt`. The `ClassKind` import already exists in the codebase (`KspAnnotationExtensions.kt` line 4), so there is no new dependency to wire in.

The riskiest part of this change is the interaction with the `INLINE` strategy: the inline path in `resolveWithKinds()` (lines 82–96) activates when `classifyField()` returns `FieldKind.Primitive` **and** `isNonPrimitiveUnmapped()` returns `true`. Without the guard in `isNonPrimitiveUnmapped()`, enums would silently hit that branch and crash (`field.originalType.declaration as? KSClassDeclaration` cast would succeed but `resolve()` would fail because enums have no primary constructor parameters — wrong behaviour). Both guards are therefore required together.

The integration test is adding an enum field to the `:app` module's domain classes and running a KSP build; the generated sources must compile without errors and the mapper must pass the enum value through unchanged.

## Step Sequence

### Stage 1 — Guard enum types in ClassResolver and verify with the app

- **W1.** In `processor/src/main/kotlin/ClassResolver.kt`, add `import com.google.devtools.ksp.symbol.ClassKind` at the top.
  **Verify:** File compiles (import is valid — same symbol used in `KspAnnotationExtensions.kt`).

- **W2.** In `classifyField()`, after the stdlib guard on line 128, add:
  ```kotlin
  // Enums are atomic — treat as primitive regardless of whether they have a spec
  if ((field.originalType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS)
      return FieldKind.Primitive
  ```
  **Verify:** The new branch sits between the stdlib check and the registry lookup; it can be read sequentially without ambiguity.

- **W3.** In `isNonPrimitiveUnmapped()`, after the stdlib guard on line 163, add:
  ```kotlin
  if ((field.originalType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) return false
  ```
  **Verify:** Enum fields now return `false` from `isNonPrimitiveUnmapped()`, preventing INLINE expansion.

- **W4.** In `app/src/main/kotlin/Order.kt`, add an `OrderStatus` enum and a `status` field:
  ```kotlin
  enum class OrderStatus { PENDING, CONFIRMED, SHIPPED, DELIVERED }

  data class Order(
      val id: Long,
      val address: Address,
      val status: OrderStatus,
      val tags: List<String>,
  )
  ```
  Also add `status: OrderStatus` to the expected usages in `Main.kt` / wherever `Order` is constructed.
  **Verify:** Source change is syntactically valid; project has not been built yet.

- **W5.** Run `./gradlew :app:build` (or `:app:kspKotlin`).
  **Verify:** Build succeeds with zero errors. KSP generates `OrderEntity` (or whichever suffixed class applies) containing a `status: OrderStatus` field typed as the enum. The `toEntity()` mapper passes `status` through without a nested mapper call.

**Demo:**
1. Run `./gradlew :app:build --info 2>&1 | grep -E "BUILD|OrderStatus"`
2. Observe `BUILD SUCCESSFUL` with no error about `OrderStatus has no spec`.
3. Open the generated source under `app/build/generated/ksp/main/kotlin/` and confirm `status: OrderStatus` appears as a plain field (not a mapped object) in the generated class.

<!-- No further stages — the fix is entirely self-contained. -->

## Recommendations (read before starting)

1. The `KSClassDeclaration` cast in the guard uses `as?` — safe-cast is correct because the declaration could be a `KSTypeAlias` or other non-class node in theory; `null` falls through to the existing registry lookup, which is fine.
2. Enum types inside collections (e.g., `List<OrderStatus>`) already work correctly: the collection branch returns `FieldKind.Primitive` when `lookupNested` returns null (line 122 of `ClassResolver.kt`). No change needed there.
3. If `OrderSpec.kt` uses `unmappedNestedStrategy = FAIL` (the default), the build would currently fail with `OrderStatus has no spec for suffix 'Entity'` — this confirms the bug is reproducible before the fix.
4. No annotation-module changes or registry changes are needed; enums simply bypass the registry entirely after this fix.

## Checklist

- [x] W1 — Add `ClassKind` import to `ClassResolver.kt`
- [x] W2 — Enum guard in `classifyField()` (`ClassResolver.kt:131`)
- [ ] W3 — Enum guard in `isNonPrimitiveUnmapped()` — **not added; unnecessary.** `toAutoGenerateCandidate()` (`DomainMappingProcessorProvider.kt:219`) already returns `null` for enum classes, so `isNonPrimitiveUnmapped()` short-circuits on the `toAutoGenerateCandidate() == null` early-return before any enum can reach the INLINE expansion branch. Per project convention ("don't add validation for scenarios that can't happen") the explicit guard is redundant.
- [x] W4 — Added `OrderStatus` enum + `status` field to `Order.kt` in `:app`
- [x] W5 — KSP generation passes; `OrderEntity` contains `status: OrderStatus` as a plain field (`OrderEntity.kt:15`); mapper passes it through unchanged (`OrderEntityMappers.kt:5`)

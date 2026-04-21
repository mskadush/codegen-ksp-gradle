# Plan 004: IncludeBundles Transitive Resolution + Cycle Detection

**Created**: 2026-04-21
**Context**: Step 14 of the domain-mapping processor. The `@IncludeBundles` annotation existed in `BundleAnnotations.kt` but was never read by the processor. This plan wires it up: bundles can declare transitive includes, the processor resolves them DFS-order, and cycle detection prevents infinite loops.

## Approach

Expand `BundleRegistry` to compute the inclusion graph and expose a transitive-expansion helper. Update `resolveWithBundles` to call that helper. Add validation for unknown `@IncludeBundles` names. Add a two-level app sample using `OrderSpec` / `OrderBaseBundle` / `OrderIdBundle`.

**Merge semantics**: DFS pre-order traversal (direct bundle first, then its includes depth-first). Within the resulting bundle list, first-definition-wins (unchanged from before). The spec vs. bundle-layer merge uses the spec's `bundleMergeStrategy`.

## Steps

- [x] Expand `BundleRegistry` — add `inclusionGraph`, `transitiveBundleNamesFor()`, and cycle detection via `CycleDetector`
- [x] Update `resolveWithBundles()` in `KspAnnotationExtensions.kt` to call `transitiveBundleNamesFor()`
- [x] Create `app/src/main/kotlin/OrderIdBundle.kt` — leaf bundle with `@Id`/`@GeneratedValue` on `id`
- [x] Create `app/src/main/kotlin/OrderBaseBundle.kt` — wrapper bundle with `@IncludeBundles(["orderId"])`
- [x] Update `app/src/main/kotlin/OrderSpec.kt` — reference `"orderBase"` to exercise the transitive chain
- [x] Tick step 14 checkboxes in `plans-by-ai/003-full-domain-mapping-processor.md`

## Dependencies/Risks

- `CycleDetector` (already existed) reused unchanged — no risk.
- Unknown `@IncludeBundles` names are caught during `BundleRegistry.build()` and logged as errors.
- Cycle error format: `Circular bundle dependency detected: A -> B -> A`

## Verification

```bash
./gradlew :app:kspKotlin
# OrderEntity.id has @Id and @GeneratedValue (from transitive orderId bundle)
# Unknown @IncludeBundles name → build error
# Cyclic inclusion → build error: Circular bundle dependency detected: A -> B -> A
```

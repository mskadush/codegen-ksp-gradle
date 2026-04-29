# Plan 023: Recursive ClassSpec Generation (AUTO_GENERATE)

**Created**: 2026-04-29
**Supersedes**: none

## Context

Every domain class that should be code-generated currently requires a separate spec file — even when it has zero customisations. `AddressSpec.kt` is the archetypal example: it contributes nothing beyond `@ClassSpec(for_ = Address::class, suffix = "Entity")`. As domain models grow, this creates maintenance overhead of empty boilerplate files. This plan eliminates that by adding `UnmappedNestedStrategy.AUTO_GENERATE`, making the processor recursively discover and generate output classes for nested domain types that lack an explicit spec.

---

## Approach

A fourth value `AUTO_GENERATE` is added to the existing `UnmappedNestedStrategy` enum. When a `@ClassSpec` carries `unmappedNestedStrategy = AUTO_GENERATE`, the processor inserts a new **discovery pass** between the existing Pass 1 (registry build) and Pass 2 (generation). The discovery pass walks the domain class constructor parameters, finds any non-primitive/non-enum/unregistered types, adds them to `SpecRegistry` with synthetic entries, and recurses through their own constructors (BFS). The result is a fully-populated registry that Pass 2 can use without modification.

The riskiest integration is ensuring type-classification in the discovery pass is consistent with `ClassResolver.classifyField`. Both must agree on what counts as an auto-generatable type — divergence produces either missing registry entries (FAIL path triggered) or spurious entries (primitives treated as mapped types). This is addressed by extracting a shared `isDomainType(type: KSType): Boolean` predicate used in both places.

Pass 2 is extended to iterate over both explicit `@ClassSpec`-driven specs AND synthetic `ClassSpecModel` objects produced by the discovery pass. Auto-generated `ClassSpecModel`s carry `unmappedStrategy = AUTO_GENERATE` so their own nested types are also discovered transitively without extra code. Explicit specs always win: if a type is already registered, the discovery pass skips it.

Cycle detection runs over the fully-expanded registry after discovery; the BFS visited-set already prevents infinite loops, and the existing `CycleDetector` covers the full target map.

---

## Step Sequence

### Stage 1 — Tracer bullet: single-level AUTO_GENERATE end-to-end

- **W1.** Add `AUTO_GENERATE` to `UnmappedNestedStrategy` in
  `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/Enums.kt`.
  **Verify:** Project compiles with `unmappedNestedStrategy = AUTO_GENERATE` in a `@ClassSpec`.

- **W2.** In `processor/src/main/kotlin/ClassResolver.kt` → `classifyField()` `when` block: add an `AUTO_GENERATE` branch returning `FieldKind.Primitive` as a temporary stub.
  **Verify:** A spec with `AUTO_GENERATE` compiles without error.

- **W3.** In `processor/src/main/kotlin/DomainMappingProcessorProvider.kt`, after SpecRegistry build: single-level discovery scan; collect `syntheticModels`; extend Pass 2 to also generate for them.
  **Verify:** `AddressEntity.kt` generated without `AddressSpec.kt` when parent spec uses `AUTO_GENERATE`.

**Demo:**
1. Delete `app/src/main/kotlin/AddressSpec.kt`
2. Add `val address: Address` to `User` data class
3. Add `unmappedNestedStrategy = UnmappedNestedStrategy.AUTO_GENERATE` to the `"Entity"` `@ClassSpec` on `UserSpec`
4. Run `./gradlew :app:kspKotlin`
5. Confirm `AddressEntity.kt` and `AddressEntityMappers.kt` exist in generated sources
6. Confirm `UserEntity` has field `val address: AddressEntity`

---

### Stage 2 — Recursive BFS discovery

- **W4.** Replace single-level scan with BFS loop; track visited `fqn:suffix` pairs.
  **Verify:** Chain `User → Address → Street` produces `AddressEntity`, `StreetEntity`, mappers.

- **W5.** Extract shared `isDomainType(type: KSType): Boolean` helper; use in both BFS and `ClassResolver.classifyField`.
  **Verify:** Generated output identical (safe refactor).

**Demo:**
1. Add `data class Street(val name: String, val city: String)` and wire into `Address`
2. Run `./gradlew :app:kspKotlin`
3. Confirm `StreetEntity.kt` generated; `AddressEntity.street: StreetEntity` present

---

### Stage 3 — Cycle detection and ClassResolver final wiring

- **W6.** Add auto-generated entries to `nonPartialDomainDecls`; run `CycleDetector` over expanded graph.
  **Verify:** Cyclic pair emits "Circular mapping detected" and fails cleanly.

- **W7.** Replace `AUTO_GENERATE → Primitive` stub with correct resolution (type is in registry by generation time → `MappedObject`/`MappedCollection`).
  **Verify:** `FAIL`/`INLINE`/`EXCLUDE` unchanged; `AUTO_GENERATE` resolves to `MappedObject`.

**Demo:**
1. Write `NodeA(val b: NodeB)` and `NodeB(val a: NodeA)` with parent spec using `AUTO_GENERATE`
2. Confirm build fails with "Circular mapping detected"

---

### Stage 4 — Documentation + app module cleanup

- **W8.** Expand KDoc for `AUTO_GENERATE` in `Enums.kt`.
- **W9.** Remove `app/src/main/kotlin/AddressSpec.kt`; wire demo via `AUTO_GENERATE`.
- **W10.** Update `README.md` `unmappedNestedStrategy` table.

---

## Checklist

- [x] W1: `AUTO_GENERATE` added to `UnmappedNestedStrategy`
- [x] W2: Temporary stub in `ClassResolver.classifyField`
- [x] W3: Single-level discovery + Pass 2 generation (Stage 1 demo passing)
- [x] W4: BFS recursive discovery
- [x] W5: Shared `toAutoGenerateCandidate()` helper extracted and wired into both sites
- [x] W6: Cycle detection over expanded registry (auto-generated entries added to `nonPartialDomainDecls`)
- [x] W7: `ClassResolver.classifyField` final `AUTO_GENERATE` handling (defensive warn branch)
- [x] W8: KDoc for `AUTO_GENERATE` in `Enums.kt`
- [x] W9: Removed `AddressSpec.kt`; `OrderSpec` uses `AUTO_GENERATE` as canonical demo
- [x] W10: README — new "Nested type strategies" section with `AUTO_GENERATE` table and example

---

## Design decisions (confirmed)

- **`outputPackage` is inherited** from the triggering parent spec's `resolvedOutputPackage`. Auto-generated types land in the same package as the class that references them.
- **`prefix` is inherited** from the triggering parent spec. If the parent uses `prefix = "Foo"`, nested auto-generated classes also carry that prefix.
- **`partial` is never inherited** — auto-generated types are always non-partial. `partial = true` must be declared explicitly on a real `@ClassSpec`.

## Files to Modify

| File | Change |
|---|---|
| `annotations/src/main/kotlin/za/skadush/codegen/gradle/annotations/Enums.kt` | Add `AUTO_GENERATE` to `UnmappedNestedStrategy` |
| `processor/src/main/kotlin/DomainMappingProcessorProvider.kt` | Add BFS discovery pass; extend Pass 2 for synthetic models |
| `processor/src/main/kotlin/ClassResolver.kt` | `AUTO_GENERATE` case in `classifyField`; extract `isDomainType` helper |
| `app/src/main/kotlin/AddressSpec.kt` | Delete (or update to demo `AUTO_GENERATE` usage) |
| `app/src/main/kotlin/UserSpec.kt` | Add `unmappedNestedStrategy = AUTO_GENERATE` to Entity spec as canonical example |
| `README.md` | Add `AUTO_GENERATE` to `unmappedNestedStrategy` table |

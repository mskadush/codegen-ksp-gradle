# Step 10 — Nested Type Resolution + Cycle Detection

## Context
Steps 1–9 complete. Generators currently treat all field types as primitives. Step 10 adds:
- A `SpecRegistry` built before generation so field types can be looked up
- A `FieldKind` classification (`Primitive` / `MappedObject` / `MappedCollection`)
- Type substitution in `EntityGenerator` (e.g. `address: AddressEntity` instead of `Address`)
- Mapper expressions in `MapperGenerator` (`.toEntity()` / `.map { it.toEntity() }`)
- DFS cycle detection across the entity dependency graph
- `UnmappedNestedStrategy.FAIL / INLINE / EXCLUDE` logic

---

## Execution Checklist

- [ ] Create `FieldKind.kt`
- [ ] Create `SpecRegistry.kt`
- [ ] Create `CycleDetector.kt`
- [ ] Update `FieldModel.kt` — add `fieldKind` and `sourceExpression` with defaults
- [ ] Update `ClassResolver.kt` — add `registry` var, `resolveWithKinds()`, `classifyField()`, `extractCollectionElement()`
- [ ] Update `DomainMappingProcessorProvider.kt` — two-pass process()
- [ ] Update `EntityGenerator.kt` — use fieldKind for type substitution
- [ ] Update `MapperGenerator.kt` — use fieldKind for mapping expressions
- [ ] Create `app/Address.kt`
- [ ] Create `app/AddressEntitySpec.kt`
- [ ] Update `app/User.kt` — add `address: Address`
- [ ] Update `app/Main.kt` — exercise nested mapping
- [ ] Create `app/CycleTestSpecs.kt` (commented-out)
- [ ] Run `./gradlew :app:kspKotlin` — verify `AddressEntity.kt` generated, `UserEntity` has `address: AddressEntity`
- [ ] Verify `UserMappers.kt` contains `address = this.address.toEntity()` and `address = this.address.toDomain()`

---

## Files to Create

### `processor/src/main/kotlin/FieldKind.kt`
Sealed class with three variants:
```kotlin
sealed class FieldKind {
    object Primitive : FieldKind()
    data class MappedObject(val targetName: String, val targetClassName: ClassName) : FieldKind()
    data class MappedCollection(val targetName: String, val targetClassName: ClassName, val collectionFQN: String) : FieldKind()
}
```

### `processor/src/main/kotlin/SpecRegistry.kt`
Plain data class holding pre-built lookups:
```kotlin
data class SpecRegistry(
    val entityTargets: Map<String, String>,  // domainFQN -> "AddressEntity"
    val dtoTargets: Map<String, String>,
) {
    companion object { val EMPTY = SpecRegistry(emptyMap(), emptyMap()) }
}
```

### `processor/src/main/kotlin/CycleDetector.kt`
Stateless DFS object. Accepts `Map<String, Set<String>>` (domainFQN → deps), returns first cycle as `List<String>` or `null`.
- Uses grey/black colouring
- Cycle path reconstructed via parent map
- Caller joins with ` -> ` and reports: `Circular mapping detected: A -> B -> A. Use exclude = true to break the cycle.`

### `app/src/main/kotlin/Address.kt`
```kotlin
data class Address(val street: String, val city: String, val postCode: String)
```

### `app/src/main/kotlin/AddressEntitySpec.kt`
```kotlin
@EntitySpec(for_ = Address::class, table = "addresses")
object AddressEntitySpec
```

### `app/src/main/kotlin/CycleTestSpecs.kt`
Entirely commented-out block documenting how to trigger the cycle error for manual testing.

---

## Files to Modify

### `processor/src/main/kotlin/FieldModel.kt`
Add two fields with defaults (fully backwards-compatible):
- `fieldKind: FieldKind = FieldKind.Primitive`
- `sourceExpression: String? = null` (for INLINE flattened fields)

### `processor/src/main/kotlin/ClassResolver.kt`
- Add `var registry: SpecRegistry = SpecRegistry.EMPTY`
- Add `resolveWithKinds(cls, unmappedNestedStrategy)` that classifies each field
- Add `classifyField()` private helper
- Add `extractCollectionElement()` private helper

### `processor/src/main/kotlin/DomainMappingProcessorProvider.kt`
Two-pass process():
- Pass 1: build SpecRegistry + run CycleDetector
- Pass 2: set `classResolver.registry = specRegistry`, run generators

### `processor/src/main/kotlin/EntityGenerator.kt`
Use `fieldKind` for base type: `MappedObject → targetClassName`, `MappedCollection → List<targetClassName>`

### `processor/src/main/kotlin/MapperGenerator.kt`
Use `fieldKind` for expressions: `MappedObject → .toEntity()/.toDomain()`, `MappedCollection → .map { it.toEntity() }`

### `app/src/main/kotlin/User.kt`
Add `val address: Address`

### `app/src/main/kotlin/Main.kt`
Exercise nested mapping in main()

---

## Verification

```bash
./gradlew :app:kspKotlin
# AddressEntity.kt generated in build/generated/ksp/main/kotlin/
# UserEntity.kt has field: address: AddressEntity  (not Address)
# UserMappers.kt toEntity() contains: address = this.address.toEntity()
# UserMappers.kt toDomain() contains: address = this.address.toDomain()
```

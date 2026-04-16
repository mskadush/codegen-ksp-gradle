# Step 1 — Annotations Module (All Annotation & Enum Definitions)

## Context

Implementing Step 1 of `003-full-domain-mapping-processor.md`: populate the `annotations` module with every annotation class and enum required by the full domain mapping processor spec (`000-init-spec.md` §3.3 and §3.4). The module currently only contains `HelloWorld.kt`.

**Key constraint:** Several annotations reference `FieldTransformer<*, *>`, `NoOpTransformer`, `RequestValidator<*>`, and `NoOpValidator` as `KClass<*>` default values. These types cannot live in `runtime` (that would be circular — `runtime` depends on `annotations`), so they are defined directly in the `annotations` module.

---

## Files to Create

All under `annotations/src/main/kotlin/com/example/annotations/`:

| File | Contents |
|---|---|
| `Enums.kt` | All 8 enums: `NullableOverride`, `BundleMergeStrategy`, `UnmappedNestedStrategy`, `MissingRelationStrategy`, `ExcludedFieldStrategy`, `RelationType`, `CascadeType`, `FetchType` |
| `TransformerTypes.kt` | `FieldTransformer<Domain, Target>` interface + `NoOpTransformer`; `RequestValidator<T>` interface + `NoOpValidator` |
| `SupportingAnnotations.kt` | `DbAnnotation`, `AnnotationMember`, `Index`, `Relation`, `Rule` (with 12 nested annotation classes) |
| `EntityAnnotations.kt` | `EntitySpec`, `EntityField` (repeatable) |
| `DtoAnnotations.kt` | `DtoSpec`, `DtoField` (repeatable) |
| `RequestAnnotations.kt` | `RequestSpec`, `CreateSpec`, `UpdateSpec`, `CreateField`, `UpdateField` |
| `BundleAnnotations.kt` | `EntityBundle`, `DtoBundle`, `RequestBundle`, `IncludeBundles` |
| `TransformerAnnotations.kt` | `TransformerRegistry`, `RegisterTransformer` |

---

## Checklist

- [x] Create `Enums.kt`
- [x] Create `TransformerTypes.kt`
- [x] Create `SupportingAnnotations.kt`
- [x] Create `EntityAnnotations.kt`
- [x] Create `DtoAnnotations.kt`
- [x] Create `RequestAnnotations.kt`
- [x] Create `BundleAnnotations.kt`
- [x] Create `TransformerAnnotations.kt`
- [x] Verify: `./gradlew :annotations:compileKotlin` passes with no errors

---

## Verification

```bash
./gradlew :annotations:compileKotlin
```

Expected: BUILD SUCCESSFUL, no errors.

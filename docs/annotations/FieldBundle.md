# `@FieldBundle` and `@IncludeBundles`

## `@FieldBundle`

Marks a class as a **named, reusable bundle of field configurations** that can be shared across multiple [`@ClassSpec`](ClassSpec.md) specs.

A bundle class carries the same [`@ClassField`](ClassField.md) and [`@FieldSpec`](FieldSpec.md) annotations as any spec class — but without `@ClassSpec`. The processor merges the bundle's field configurations into each referencing spec according to the spec's `bundleMergeStrategy`.

> **Target**: `CLASS` · **Retention**: `SOURCE`

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `name` | `String` | _(required)_ | Identifier used to reference this bundle from `@ClassSpec.bundles`. |

---

## `@IncludeBundles`

An alternative to listing bundles inside `@ClassSpec.bundles` — useful when the bundle list is long or when the same set of bundles is shared across many spec classes.

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `names` | `Array<String>` | _(required)_ | Names of `@FieldBundle` classes to include. |

---

## How merging works

When `@ClassSpec.bundles = ["timestamps"]` is set, the processor:

1. Looks up the registered `@FieldBundle("timestamps")` class.
2. Reads all `@ClassField` and `@FieldSpec` annotations from that class.
3. Merges them into the spec's own field configurations according to `bundleMergeStrategy`:
   - `SPEC_WINS` _(default)_ — spec's own config takes precedence; bundle fills gaps.
   - `BUNDLE_WINS` — bundle's config takes precedence; spec fills gaps.
   - `MERGE_ADDITIVE` — both configs are combined; spec fills in anything the bundle left unset.

Bundles can include other bundles transitively — reference additional bundle names via `@IncludeBundles` on the bundle class itself.

---

## Example

### Defining a bundle

```kotlin
@FieldBundle("timestamps")
// Entity: snake_case column names + Jakarta persistence annotations
@FieldSpec(
    for_ = ["Entity"], property = "createdAt", column = "created_at",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"created_at\"", "nullable=false", "updatable=false"]
    )]
)
@FieldSpec(
    for_ = ["Entity"], property = "updatedAt", column = "updated_at",
    nullable = NullableOverride.YES,
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"updated_at\""]
    )]
)
// Requests: exclude audit fields (not user-supplied)
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
@FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "updatedAt", exclude = true)
object TimestampsBundle
```

### Referencing a bundle from a spec

```kotlin
@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    bundles = ["timestamps"],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE
)
@ClassSpec(for_ = User::class, suffix = "CreateRequest", bundles = ["timestamps"])
object UserSpec
```

`TimestampsBundle`'s `@FieldSpec` overrides are merged as if they were written directly on `UserSpec`.

### Transitive bundles

```kotlin
// A base bundle
@FieldBundle("auditFields")
@ClassField(property = "createdBy", exclude = false)
object AuditFieldsBundle

// A composite bundle that pulls in auditFields
@FieldBundle("fullAudit")
@IncludeBundles(names = ["auditFields"])
@FieldSpec(for_ = ["Entity"], property = "updatedBy", column = "updated_by")
object FullAuditBundle

// Now a spec referencing "fullAudit" also gets "auditFields" configs automatically
@ClassSpec(for_ = Order::class, suffix = "Entity", bundles = ["fullAudit"])
object OrderSpec
```

### Using `@IncludeBundles` on a spec instead of `@ClassSpec.bundles`

```kotlin
@ClassSpec(for_ = User::class, suffix = "Entity")
@IncludeBundles(names = ["timestamps", "userEntity"])
object UserSpec
```

This is equivalent to `@ClassSpec(..., bundles = ["timestamps", "userEntity"])`.

---

## Generated output

Given `TimestampsBundle` above and a `User` domain class with `createdAt: Instant` and `updatedAt: Instant?`:

**`UserEntity`** receives:
```kotlin
@Column(name = "created_at", nullable = false, updatable = false)
val createdAt: Instant,
@Column(name = "updated_at")
val updatedAt: Instant?,
```

**`UserCreateRequest`** — both fields are excluded (not present in the generated class).

---

## See also

- [`@ClassSpec.bundles`](ClassSpec.md) — where bundles are referenced
- [`BundleMergeStrategy`](SupportingTypes.md#bundlemergestrategy) — conflict resolution options
- [`@FieldSpec`](FieldSpec.md) — the annotation type used inside bundle classes

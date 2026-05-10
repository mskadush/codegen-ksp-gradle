# `@FieldBundle` and `@IncludeBundles`

## `@FieldBundle`

Marks a class as a **reusable bundle of field configurations** that can be shared across multiple [`@ClassSpec`](ClassSpec.md) specs.

A bundle class carries the same [`@FieldSpec`](FieldSpec.md) and [`@FieldOverride`](FieldOverride.md) annotations as any spec class — but without `@ClassSpec`. The processor merges the bundle's field configurations into each referencing spec according to the spec's `bundleMergeStrategy`.

The bundle class itself is its own identity — reference it by `KClass` in `@ClassSpec.bundles`.

> **Target**: `CLASS` · **Retention**: `SOURCE`

_(No properties — the class reference is all that's needed.)_

---

## `@IncludeBundles`

Declares **transitive bundle dependencies** for the annotated bundle class. When a spec pulls in a bundle annotated with `@IncludeBundles`, the processor also merges all listed bundles, in DFS pre-order (the outer bundle's own configs first, then each included bundle in order).

> **Target**: `CLASS` · **Retention**: `SOURCE`

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `bundles` | `Array<KClass<*>>` | _(required)_ | `@FieldBundle` classes to include transitively. |

---

## How merging works

When `@ClassSpec(bundles = [TimestampsBundle::class])` is set, the processor:

1. Looks up `TimestampsBundle` in the bundle registry by its fully-qualified class name.
2. Reads all `@FieldSpec` and `@FieldOverride` annotations from that bundle class.
3. Merges them into the spec's own field configurations according to `bundleMergeStrategy`:
   - `SPEC_WINS` _(default)_ — spec's own config takes precedence; bundle fills gaps.
   - `BUNDLE_WINS` — bundle's config takes precedence; spec fills gaps.
   - `MERGE_ADDITIVE` — both configs are combined; each side fills in what the other left unset.

Bundles can include other bundles transitively — reference additional bundle classes via `@IncludeBundles` on the bundle class itself.

---

## Example

### Defining a bundle

```kotlin
@FieldBundle
// Entity: Jakarta persistence annotations
@FieldOverride(
    for_ = ["Entity"], property = "createdAt",
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"created_at\"", "nullable=false", "updatable=false"]
    )]
)
@FieldOverride(
    for_ = ["Entity"], property = "updatedAt",
    nullable = NullableOverride.YES,
    annotations = [CustomAnnotation(
        annotation = jakarta.persistence.Column::class,
        members = ["name=\"updated_at\""]
    )]
)
// Requests: exclude audit fields (not user-supplied)
@FieldOverride(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
@FieldOverride(for_ = ["CreateRequest", "UpdateRequest"], property = "updatedAt", exclude = true)
object TimestampsBundle
```

### Referencing a bundle from a spec

```kotlin
@ClassSpec(
    for_ = User::class,
    suffix = "Entity",
    bundles = [TimestampsBundle::class, UserEntityBundle::class],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE
)
@ClassSpec(for_ = User::class, suffix = "CreateRequest", bundles = [TimestampsBundle::class])
object UserSpec
```

`TimestampsBundle`'s `@FieldOverride` overrides are merged as if they were written directly on `UserSpec`.

### Transitive bundles

```kotlin
// A leaf bundle
@FieldBundle
@FieldOverride(
    for_ = ["Entity"], property = "id",
    annotations = [CustomAnnotation(annotation = jakarta.persistence.Id::class)]
)
object OrderIdBundle

// A composite bundle that pulls in OrderIdBundle
@FieldBundle
@IncludeBundles([OrderIdBundle::class])
object OrderBaseBundle

// A spec referencing OrderBaseBundle also gets OrderIdBundle's configs automatically
@ClassSpec(for_ = Order::class, suffix = "Entity", bundles = [OrderBaseBundle::class])
object OrderSpec
```

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

- [`@ClassSpec.bundles`](ClassSpec.md) — where bundle classes are referenced
- [`BundleMergeStrategy`](SupportingTypes.md#bundlemergestrategy) — conflict resolution options
- [`@FieldOverride`](FieldOverride.md) — the annotation type used inside bundle classes

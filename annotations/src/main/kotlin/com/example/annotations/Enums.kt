package com.example.annotations

/** Controls whether a generated field's nullability is overridden relative to the domain model. */
enum class NullableOverride {
    /** Inherit nullability from the source domain class (default). */
    UNSET,
    /** Force the generated field to be nullable (`?`). */
    YES,
    /** Force the generated field to be non-nullable. */
    NO
}

/**
 * Determines which definition wins when a spec and an included [@FieldBundle] both configure the same field.
 *
 * Used by [ClassSpec.bundleMergeStrategy].
 */
enum class BundleMergeStrategy {
    /** The spec's own field configuration takes precedence over the bundle (default). */
    SPEC_WINS,
    /** The bundle's field configuration takes precedence over the spec. */
    BUNDLE_WINS,
    /** Both configurations are merged; the spec fills in gaps left by the bundle. */
    MERGE_ADDITIVE
}

/**
 * Defines what the processor does when it encounters a nested domain type that has no explicit mapping.
 *
 * Used by [ClassSpec.unmappedNestedStrategy].
 */
enum class UnmappedNestedStrategy {
    /** Abort generation with a compile-time error (default). */
    FAIL,
    /** Flatten the nested object's fields into the parent class with an optional prefix. */
    INLINE,
    /** Silently omit the nested field from the generated class. */
    EXCLUDE
}



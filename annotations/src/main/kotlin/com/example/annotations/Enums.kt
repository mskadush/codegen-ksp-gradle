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
 * Determines which definition wins when a spec and an included bundle both configure the same field.
 *
 * Used by [EntitySpec.bundleMergeStrategy] and [DtoSpec.bundleMergeStrategy].
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
 * Used by [EntitySpec.unmappedNestedStrategy] and [DtoSpec.unmappedNestedStrategy].
 */
enum class UnmappedNestedStrategy {
    /** Abort generation with a compile-time error (default). */
    FAIL,
    /** Flatten the nested object's fields into the parent class with an optional prefix. */
    INLINE,
    /** Silently omit the nested field from the generated class. */
    EXCLUDE
}

/**
 * Controls how the processor handles a domain field that maps to a related entity but has no explicit [Relation].
 *
 * Used by [EntitySpec.missingRelationStrategy].
 */
enum class MissingRelationStrategy {
    /** Abort generation with a compile-time error (default). */
    FAIL,
    /** Attempt to infer the relationship type from the field's type. */
    INFER
}

/**
 * Defines what happens to excluded fields in a generated DTO.
 *
 * Used by [DtoSpec.excludedFieldStrategy].
 */
enum class ExcludedFieldStrategy {
    /** Use the field's default value if one exists, otherwise omit it (default). */
    USE_DEFAULT,
    /** Require the caller to supply the field value manually in the mapping function. */
    REQUIRE_MANUAL,
    /** Override the field to nullable so it can be set to `null`. */
    NULLABLE_OVERRIDE
}

/** ORM relationship types supported by [Relation.type]. */
enum class RelationType {
    /** No relationship; the field is a plain column (default). */
    NONE,
    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_ONE,
    MANY_TO_MANY
}

/** JPA/ORM cascade operation types used in [Relation.cascade]. */
enum class CascadeType { ALL, PERSIST, MERGE, REMOVE, REFRESH }

/** ORM fetch strategy used in [Relation.fetch]. */
enum class FetchType {
    /** Load the association on demand (default). */
    LAZY,
    /** Load the association immediately with the parent. */
    EAGER
}

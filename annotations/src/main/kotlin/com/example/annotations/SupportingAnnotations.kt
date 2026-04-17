package com.example.annotations

/**
 * Represents an arbitrary annotation to be emitted on a generated class or field.
 *
 * Use this when you need the code generator to attach framework-specific annotations
 * (e.g. JPA, Jackson) that are not natively modelled by the DSL.
 *
 * @param fqn Fully-qualified name of the annotation class (e.g. `"jakarta.persistence.Table"`).
 * @param members Key-value pairs for the annotation's parameters.
 */
annotation class DbAnnotation(
    val fqn: String,
    val members: Array<AnnotationMember> = []
)

/**
 * A single named parameter inside a [DbAnnotation].
 *
 * @param name Parameter name as it appears in the annotation declaration.
 * @param value Parameter value expressed as a string literal; the generator emits it verbatim.
 */
annotation class AnnotationMember(
    val name: String,
    val value: String
)

/**
 * Declares a database index to be generated on the enclosing entity's table.
 *
 * Referenced from [EntitySpec.indexes].
 *
 * @param columns One or more column names that form the index key.
 * @param unique When `true`, the generated index enforces uniqueness.
 * @param name Optional explicit index name; the generator derives a name when left blank.
 */
annotation class Index(
    val columns: Array<String>,
    val unique: Boolean = false,
    val name: String = ""
)

/**
 * Configures the ORM relationship for a field in a generated entity.
 *
 * Referenced from [EntityField.relation]. When [type] is [RelationType.NONE] (the default),
 * the field is treated as a plain column and all other parameters are ignored.
 *
 * @param type The relationship cardinality.
 * @param mappedBy The name of the field in the related entity that owns the relationship.
 * @param cascade Cascade operations to propagate to the related entity.
 * @param fetch Whether to load the association eagerly or lazily (default: [FetchType.LAZY]).
 * @param joinColumn Name of the foreign-key column for `@OneToOne` / `@ManyToOne`.
 * @param joinTable Name of the join table for `@ManyToMany`.
 */
annotation class Relation(
    val type: RelationType,
    val mappedBy: String = "",
    val cascade: Array<CascadeType> = [],
    val fetch: FetchType = FetchType.LAZY,
    val joinColumn: String = "",
    val joinTable: String = ""
)

/**
 * Container for per-field validation rules used in [CreateField.rules] and [UpdateField.rules].
 *
 * Each nested annotation represents a single constraint that the generated validator will enforce.
 */
annotation class Rule {
    /** Field must be present and non-null. */
    annotation class Required
    /** Field must be a well-formed email address. */
    annotation class Email
    /** Field must not be blank (non-null and contains at least one non-whitespace character). */
    annotation class NotBlank
    /** Numeric field must be greater than zero. */
    annotation class Positive
    /** Date/time field must be in the past. */
    annotation class Past
    /** Date/time field must be in the future. */
    annotation class Future
    /** String field must have at least [value] characters. */
    annotation class MinLength(val value: Int)
    /** String field must have at most [value] characters. */
    annotation class MaxLength(val value: Int)
    /** Numeric field must be greater than or equal to [value]. */
    annotation class Min(val value: Double)
    /** Numeric field must be less than or equal to [value]. */
    annotation class Max(val value: Double)
    /** String field must match the given [regex]; an optional [message] overrides the default error. */
    annotation class Pattern(val regex: String, val message: String = "")
    /** Calls a custom validation function referenced by fully-qualified name in [fn]. */
    annotation class Custom(val fn: String)
}

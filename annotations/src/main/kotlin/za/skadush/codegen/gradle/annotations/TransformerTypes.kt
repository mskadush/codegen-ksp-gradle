package za.skadush.codegen.gradle.annotations

/**
 * Bidirectional converter between a domain type and a target (e.g. persistence) type.
 *
 * Implement this interface to transform field values when reading from or writing to
 * a generated entity or DTO. Reference the implementation class via [EntityField.transformer]
 * or [DtoField.transformer], or register it by name with [RegisterTransformer] and reference
 * it via [EntityField.transformerRef] / [DtoField.transformerRef].
 *
 * @param Domain The type used in the domain model.
 * @param Target The type used in the generated class (e.g. a database column type).
 */
interface FieldTransformer<Domain, Target> {
    /** Converts a [value] from the domain model into the target representation. */
    fun toTarget(value: Domain): Target
    /** Converts a [value] from the target representation back into the domain model. */
    fun toDomain(value: Target): Domain
}

/**
 * Default [FieldTransformer] that passes values through unchanged.
 *
 * Used as the default for [EntityField.transformer] and [DtoField.transformer] when
 * no transformation is needed.
 */
class NoOpTransformer : FieldTransformer<Any, Any> {
    override fun toTarget(value: Any) = value
    override fun toDomain(value: Any) = value
}


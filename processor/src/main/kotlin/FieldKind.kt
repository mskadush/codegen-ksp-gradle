import com.squareup.kotlinpoet.ClassName

/**
 * Describes how a field's domain type maps to its generated target type.
 *
 * Determined by [ClassResolver] after consulting [SpecRegistry].
 *
 * - [Primitive]: the type is a primitive, String, or has no spec mapping.
 * - [MappedObject]: the field's domain type is itself a mapped domain class.
 * - [MappedCollection]: the field is a List/Set whose element type is a mapped domain class.
 */
sealed class FieldKind {

    object Primitive : FieldKind()

    data class MappedObject(
        val targetName: String,
        val targetClassName: ClassName,
    ) : FieldKind()

    data class MappedCollection(
        val targetName: String,
        val targetClassName: ClassName,
        val collectionFQN: String,
    ) : FieldKind()
}

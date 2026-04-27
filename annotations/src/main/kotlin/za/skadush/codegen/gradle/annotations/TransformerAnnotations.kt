package za.skadush.codegen.gradle.annotations

/**
 * Registers a property within a [TransformerRegistry] class as a named transformer.
 *
 * The property must implement [FieldTransformer]. The [name] is the identifier used in
 * [EntityField.transformerRef] and [DtoField.transformerRef].
 *
 * @param name Unique identifier for this transformer within the registry.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class RegisterTransformer(val name: String)

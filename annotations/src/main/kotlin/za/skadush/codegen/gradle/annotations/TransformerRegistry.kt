package za.skadush.codegen.gradle.annotations

/**
 * Marks a class as a registry of named [FieldTransformer] instances.
 *
 * The processor scans classes annotated with this to build a lookup table of transformer
 * instances. Individual transformers within the class are registered via [RegisterTransformer]
 * on their properties, and referenced from [EntityField.transformerRef] or [DtoField.transformerRef].
 *
 * Example:
 * ```kotlin
 * @TransformerRegistry
 * object MyTransformers {
 *     @RegisterTransformer("uuidString")
 *     val uuidToString = UuidStringTransformer()
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class TransformerRegistry
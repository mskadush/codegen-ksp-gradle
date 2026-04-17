package com.example.annotations

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

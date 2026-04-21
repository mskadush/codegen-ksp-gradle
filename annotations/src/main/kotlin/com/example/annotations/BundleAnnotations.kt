package com.example.annotations

/**
 * Marks a class as a named bundle of field configurations that can be shared
 * across multiple [@ClassSpec] specs.
 *
 * A bundle class carries the same [@ClassField] and [@FieldSpec] annotations as a spec class,
 * but without [@ClassSpec]. Field overrides inside a bundle are scoped to specific output kinds
 * via [@FieldSpec.for_] exactly as they are in any spec class.
 *
 * Reference the bundle by [name] in [@ClassSpec.bundles]. The processor merges the bundle's
 * field configurations into the spec according to [@ClassSpec.bundleMergeStrategy].
 *
 * **Example**:
 * ```kotlin
 * @FieldBundle("timestamps")
 * @FieldSpec(for_ = ["Entity"], property = "createdAt", column = "created_at")
 * @FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
 * object TimestampsBundle
 * ```
 *
 * @param name Identifier used to reference this bundle from [@ClassSpec.bundles].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class FieldBundle(val name: String)

/**
 * Explicitly lists bundles to include in the annotated spec class.
 *
 * Alternative to listing bundles inside [@ClassSpec.bundles] when the list is long or shared
 * across spec classes.
 *
 * @param names Names of [@FieldBundle] classes to include.
 */
annotation class IncludeBundles(val names: Array<String>)

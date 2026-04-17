package com.example.annotations

/**
 * Marks a class as a named bundle of [EntityField] configurations that can be shared
 * across multiple [EntitySpec] specs.
 *
 * Reference the bundle by [name] in [EntitySpec.bundles]. The processor merges the bundle's
 * field configurations into the spec according to [EntitySpec.bundleMergeStrategy].
 *
 * @param name Identifier used to reference this bundle from [EntitySpec.bundles].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntityBundle(val name: String)

/**
 * Marks a class as a named bundle of [DtoField] configurations that can be shared
 * across multiple [DtoSpec] specs.
 *
 * Reference the bundle by [name] in [DtoSpec.bundles]. The processor merges the bundle's
 * field configurations into the spec according to [DtoSpec.bundleMergeStrategy].
 *
 * @param name Identifier used to reference this bundle from [DtoSpec.bundles].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DtoBundle(val name: String)

/**
 * Marks a class as a named bundle of request-field configurations that can be shared
 * across multiple [RequestSpec] specs.
 *
 * @param name Identifier used to reference this bundle from a request spec.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RequestBundle(val name: String)

/**
 * Explicitly lists bundles to include in the annotated spec class.
 *
 * Alternative to listing bundles inside [EntitySpec.bundles] / [DtoSpec.bundles] when the
 * list is long or shared across spec classes.
 *
 * @param names Names of bundle classes (matching [EntityBundle.name], [DtoBundle.name], or [RequestBundle.name]).
 */
annotation class IncludeBundles(val names: Array<String>)

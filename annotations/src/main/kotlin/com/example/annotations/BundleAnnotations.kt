package com.example.annotations

import kotlin.reflect.KClass

/**
 * Marks a class as a bundle of field configurations that can be shared
 * across multiple [@ClassSpec] specs.
 *
 * A bundle class carries the same [@ClassField] and [@FieldSpec] annotations as a spec class,
 * but without [@ClassSpec]. Field overrides inside a bundle are scoped to specific output kinds
 * via [@FieldSpec.for_] exactly as they are in any spec class.
 *
 * Reference the bundle by class in [@ClassSpec.bundles]:
 * ```kotlin
 * @ClassSpec(for_ = User::class, suffix = "Entity", bundles = [TimestampsBundle::class])
 * ```
 *
 * **Example**:
 * ```kotlin
 * @FieldBundle
 * @FieldSpec(for_ = ["Entity"], property = "createdAt", rename = "created_at")
 * @FieldSpec(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
 * object TimestampsBundle
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class FieldBundle

/**
 * Declares transitive bundle dependencies for the annotated bundle class.
 *
 * When a spec pulls in this bundle, the processor also merges all bundles listed here,
 * in DFS pre-order (this bundle's own field configs first, then each included bundle in order).
 *
 * **Example**:
 * ```kotlin
 * @FieldBundle
 * @IncludeBundles([OrderIdBundle::class])
 * object OrderBaseBundle
 * ```
 *
 * @param bundles Bundle classes to include transitively.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class IncludeBundles(val bundles: Array<KClass<*>>)

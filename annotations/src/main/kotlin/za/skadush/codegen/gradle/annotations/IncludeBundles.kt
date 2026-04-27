package za.skadush.codegen.gradle.annotations

import kotlin.reflect.KClass

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

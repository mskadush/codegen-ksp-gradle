package za.skadush.codegen.gradle.annotations

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
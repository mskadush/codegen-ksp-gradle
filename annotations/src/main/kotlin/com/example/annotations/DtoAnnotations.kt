package com.example.annotations

import kotlin.reflect.KClass

/**
 * Marks a spec class to drive generation of a Data Transfer Object from a domain class.
 *
 * The processor reads this annotation at compile time and generates a new `data class`
 * named `<prefix><DomainClass><suffix>` whose fields mirror the domain class's primary
 * constructor, subject to [DtoField] overrides on the same spec class.
 *
 * Example:
 * ```kotlin
 * @DtoSpec(for_ = User::class, suffix = "Response")
 * @DtoField(property = "passwordHash", exclude = true)
 * class UserResponseSpec
 * ```
 *
 * @param for_ The domain class to generate a DTO from.
 * @param suffix Appended to the domain class name to form the DTO class name (default: `"Dto"`).
 * @param prefix Prepended to the domain class name to form the DTO class name.
 * @param bundles Names of [DtoBundle] classes whose field configs are merged into this spec.
 * @param bundleMergeStrategy How to resolve conflicts between this spec's [DtoField]s and bundle fields.
 * @param unmappedNestedStrategy What to do when a nested domain type has no explicit field mapping.
 * @param excludedFieldStrategy How to treat fields that are excluded from the DTO.
 * @param annotations Extra annotations to forward to the generated DTO class (e.g. Jackson's `@JsonInclude`).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DtoSpec(
    val for_: KClass<*>,
    val suffix: String = "Dto",
    val prefix: String = "",
    val bundles: Array<String> = [],
    val bundleMergeStrategy: BundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    val unmappedNestedStrategy: UnmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    val excludedFieldStrategy: ExcludedFieldStrategy = ExcludedFieldStrategy.USE_DEFAULT,
    val annotations: Array<DbAnnotation> = []
)

/**
 * Overrides the mapping of a single field during DTO generation.
 *
 * Apply this annotation (repeatable) on the same spec class as [DtoSpec] to customise
 * how individual properties from the domain class appear in the generated DTO.
 *
 * @param property Name of the property in the domain class to configure.
 * @param rename Alternative name for the field in the generated DTO; uses the original name when blank.
 * @param exclude When `true`, the field is omitted from the generated DTO.
 * @param nullable Overrides the field's nullability; [NullableOverride.UNSET] preserves the source type.
 * @param transformer Class-reference to a [FieldTransformer] for value conversion.
 * @param transformerRef Name of a transformer registered via [RegisterTransformer]; takes precedence over [transformer].
 * @param annotations Extra annotations to forward to the generated field (e.g. `@JsonProperty`, `@JsonIgnore`).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class DtoField(
    val property: String,
    val rename: String = "",
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val transformerRef: String = "",
    val annotations: Array<DbAnnotation> = []
)

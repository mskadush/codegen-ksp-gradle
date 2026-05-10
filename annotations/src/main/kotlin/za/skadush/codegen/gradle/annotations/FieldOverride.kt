package za.skadush.codegen.gradle.annotations

import kotlin.reflect.KClass

/**
 * Per-output-class field override, scoped to one or more [ClassSpec] instances by [for_].
 *
 * [for_] lists the [ClassSpec.suffix] values this override applies to, allowing the same
 * validator set to be shared across `CreateRequest` and `UpdateRequest` without duplication.
 * [FieldOverride] params win over [FieldSpec] for the same [property] + suffix combination.
 *
 * **Flat design** — all params coexist; the processor applies whichever are relevant:
 * - [rename]      — renames the field in the generated class.
 * - [validators]  — emits `validate()` / `validateOrThrow()` on the generated class; each entry must be a singleton `object` implementing [FieldValidator].
 *
 * **Example**:
 * ```kotlin
 * @FieldOverride(for_ = ["Entity"],   property = "createdAt")
 * @FieldOverride(for_ = ["Response"], property = "createdAt", rename = "updatedAt")
 * @FieldOverride(for_ = ["CreateRequest", "UpdateRequest"],
 *                property = "email",
 *                validators = [EmailValidator::class, NotBlankValidator::class])
 * ```
 *
 * @param for_           One or more [ClassSpec.suffix] values this override applies to.
 * @param property       Name of the domain property to configure.
 * @param exclude        Omit this field from the named output class(es) only.
 * @param nullable       Nullability override for the named output class(es).
 * @param transformer    [FieldTransformer] class for value conversion.
 * @param annotations    Annotations forwarded to the generated field in the named output class(es).
 * @param rename         Alternative field name in the generated class.
 * @param validators     Validation rules; each must be a singleton `object` implementing [FieldValidator].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class FieldOverride(
    val for_: Array<String>,
    val property: String,
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val annotations: Array<CustomAnnotation> = [],
    val rename: String = "",
    val validators: Array<KClass<out FieldValidator<*>>> = []
)

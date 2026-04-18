package com.example.annotations

import kotlin.reflect.KClass

/**
 * Groups [CreateSpec] and [UpdateSpec] annotations under a single domain class reference.
 *
 * Place this on a spec class alongside [CreateSpec] and/or [UpdateSpec] to associate the
 * generated request classes with [for_].
 *
 * @param for_ The domain class these request specs are generated for.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RequestSpec(val for_: KClass<*>)

/**
 * Configures the generation of a create-request class for a domain type.
 *
 * Used together with [RequestSpec] on the same spec class. The processor generates a
 * `data class` named `<DomainClass><suffix>` containing all non-excluded domain fields,
 * each decorated with the specified [Rule] constraints.
 *
 * Example:
 * ```kotlin
 * @RequestSpec(for_ = User::class)
 * @CreateSpec(
 *     fields = [CreateField(property = "email", rules = [Rule.Email::class, Rule.Required::class])]
 * )
 * class UserRequestSpec
 * ```
 *
 * @param suffix Appended to the domain class name to form the create-request class name (default: `"CreateRequest"`).
 * @param validator A [RequestValidator] subclass invoked after the object is constructed.
 * @param fields Per-field configuration; fields not listed here are included with no rules.
 * @param annotations Extra annotations to forward to the generated create-request class.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class CreateSpec(
    val suffix: String = "CreateRequest",
    val validator: KClass<out RequestValidator<*>> = NoOpValidator::class,
    val fields: Array<CreateField> = [],
    val annotations: Array<DbAnnotation> = []
)

/**
 * Configures the generation of an update-request class for a domain type.
 *
 * Used together with [RequestSpec] on the same spec class. When [partial] is `true` all fields
 * are made nullable so callers can omit properties they do not wish to update.
 *
 * @param suffix Appended to the domain class name to form the update-request class name (default: `"UpdateRequest"`).
 * @param partial When `true`, every field in the generated class is nullable (default: `true`).
 * @param validator A [RequestValidator] subclass invoked after the object is constructed.
 * @param fields Per-field configuration; fields not listed here are included with no rules.
 * @param annotations Extra annotations to forward to the generated update-request class.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class UpdateSpec(
    val suffix: String = "UpdateRequest",
    val partial: Boolean = true,
    val validator: KClass<out RequestValidator<*>> = NoOpValidator::class,
    val fields: Array<UpdateField> = [],
    val annotations: Array<DbAnnotation> = []
)

/**
 * Per-field configuration for a field inside a [CreateSpec].
 *
 * @param property Name of the domain property to configure.
 * @param rules Simple (no-parameter) validation rules referenced as class literals,
 *   e.g. `[Rule.Email::class, Rule.NotBlank::class]`.
 * @param minLength When ≥ 0, emits `require(field.length >= minLength)` in the generated `init {}`.
 * @param maxLength When ≥ 0, emits `require(field.length <= maxLength)` in the generated `init {}`.
 * @param exclude When `true`, the field is omitted from the generated create-request class.
 * @param annotations Extra annotations to forward to the generated field (e.g. validation framework annotations).
 */
annotation class CreateField(
    val property: String,
    val rules: Array<kotlin.reflect.KClass<out Annotation>> = [],
    val minLength: Int = -1,
    val maxLength: Int = -1,
    val exclude: Boolean = false,
    val annotations: Array<DbAnnotation> = []
)

/**
 * Per-field configuration for a field inside an [UpdateSpec].
 *
 * @param property Name of the domain property to configure.
 * @param rules Simple (no-parameter) validation rules referenced as class literals,
 *   e.g. `[Rule.NotBlank::class]`.
 * @param minLength When ≥ 0, emits `require(field.length >= minLength)` in the generated `init {}`.
 * @param maxLength When ≥ 0, emits `require(field.length <= maxLength)` in the generated `init {}`.
 * @param exclude When `true`, the field is omitted from the generated update-request class.
 * @param annotations Extra annotations to forward to the generated field.
 */
annotation class UpdateField(
    val property: String,
    val rules: Array<kotlin.reflect.KClass<out Annotation>> = [],
    val minLength: Int = -1,
    val maxLength: Int = -1,
    val exclude: Boolean = false,
    val annotations: Array<DbAnnotation> = []
)

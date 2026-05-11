package za.skadush.codegen.gradle.runtime

/**
 * A runtime validation rule that spans multiple fields on a generated class.
 *
 * Use this when a single rule needs access to more than one property — for example
 * "either `fullName` or `firstName` + `lastName` must be present", or "`endDate` must
 * be after `startDate`". For single-field rules use
 * [za.skadush.codegen.gradle.annotations.FieldValidator] instead.
 *
 * Implement this interface as a singleton `object` and reference it in
 * `@ClassSpec.validators` via `KClass`. The processor emits a direct call in the
 * generated `validate()` method:
 * ```kotlin
 * NameRequiredValidator.validate(this, ctx)
 * ```
 *
 * Object validators run **after** all field validators, in `@ClassSpec.validators`
 * declaration order. Errors accumulate in the [ValidationContext]; the validator
 * decides which fields (if any) to attribute the failure to.
 *
 * ### Example — conditional per-field attribution
 *
 * ```kotlin
 * object NameRequiredValidator : ObjectValidator<UserCreateRequest> {
 *     override fun validate(value: UserCreateRequest, ctx: ValidationContext) {
 *         if (!value.fullName.isNullOrBlank()) return
 *         if (!value.firstName.isNullOrBlank() && !value.lastName.isNullOrBlank()) return
 *
 *         if (value.firstName.isNullOrBlank())
 *             ctx.error("firstName", "required when fullName is absent")
 *         if (value.lastName.isNullOrBlank())
 *             ctx.error("lastName",  "required when fullName is absent")
 *     }
 * }
 * ```
 *
 * ### Note on `partial = true` outputs
 *
 * When a `@ClassSpec` is generated with `partial = true`, every field is nullable.
 * Cross-field rules written for the non-partial output (e.g. `CreateRequest`) will
 * usually misbehave on the partial output (e.g. `UpdateRequest`) because every
 * field looks "missing". Either omit the validator from the partial spec, or make
 * the validator partial-aware (return early when no relevant fields are set).
 *
 * @param T The generated class this validator operates on.
 */
interface ObjectValidator<in T> {
    /**
     * Validates [value] and records any failures via [ctx].
     *
     * Implementations must be pure and side-effect-free aside from writing to [ctx] —
     * they are called inside the generated `validate()` method which may run on
     * construction when `@ClassSpec.validateOnConstruct` is enabled.
     */
    fun validate(value: T, ctx: ValidationContext)
}

package za.skadush.codegen.gradle.annotations

/**
 * A runtime validation rule for a single field value.
 *
 * Implement this interface as a singleton `object` and reference it in
 * [FieldSpec.validators] via `KClass`. The processor emits a delegation call
 * in the generated `validate()` method:
 * ```kotlin
 * MyValidator.let { v -> ctx.ensure(v.validate(fieldValue), FieldRef("field"), v.message) }
 * ```
 *
 * Validators are composable — one validator can delegate to others:
 * ```kotlin
 * object NonEmptyEmail : FieldValidator<String> {
 *     override val message = "must be a non-empty email address"
 *     override fun validate(value: String) =
 *         NotBlankValidator.validate(value) && EmailValidator.validate(value)
 * }
 * ```
 *
 * @param T The type of value this validator accepts.
 */
interface FieldValidator<in T> {
    /** Human-readable failure message recorded in [za.skadush.codegen.gradle.runtime.ValidationError.message] when validation fails. */
    val message: String

    /**
     * Returns `true` if [value] passes this rule, `false` otherwise.
     *
     * Implementations must be pure and side-effect-free — they are called inside
     * the generated `validate()` method which may run on construction.
     */
    fun validate(value: T): Boolean
}

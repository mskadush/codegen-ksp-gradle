package com.example.runtime

/**
 * Identifies a single field on a request object.
 *
 * Passed to [ValidationContext] methods so that each [ValidationError] carries a
 * precise field reference rather than a bare string name.
 *
 * @param name The name of the field as it appears in the generated request class.
 */
data class FieldRef(val name: String)

/**
 * A single validation failure on a specific field.
 *
 * @param field   The field that failed validation.
 * @param message Human-readable description of the failure (e.g. `"email failed rule Email"`).
 */
data class ValidationError(val field: FieldRef, val message: String)

/**
 * The outcome of validating a request object via [ValidationContext.build].
 *
 * Use a `when` expression to handle both cases:
 * ```kotlin
 * when (val result = request.validate()) {
 *     is ValidationResult.Valid   -> println("ok")
 *     is ValidationResult.Invalid -> result.errors.forEach { println(it) }
 * }
 * ```
 */
sealed class ValidationResult {
    /** Indicates that all validation rules passed. */
    data object Valid : ValidationResult()

    /**
     * Indicates that one or more validation rules failed.
     *
     * @param errors The complete list of failures collected during validation.
     *               Never empty — [Valid] is returned instead when there are no errors.
     */
    data class Invalid(val errors: List<ValidationError>) : ValidationResult()
}

/**
 * Thrown by generated `validateOrThrow()` methods when validation fails.
 *
 * The [message] summarises all failures in the form
 * `"Validation failed: fieldA: reason; fieldB: reason"`.
 *
 * @param errors The failures that caused validation to fail. Mirrors
 *               [ValidationResult.Invalid.errors] when thrown from generated code.
 */
class ValidationException(val errors: List<ValidationError>)
    : RuntimeException(
        "Validation failed: ${errors.joinToString("; ") { "${it.field.name}: ${it.message}" }}"
    )

/**
 * Accumulates validation results for a request object and produces a [ValidationResult].
 *
 * Generated `validate()` methods open a context, call `ensure*` for every applicable [com.example.annotations.FieldValidator],
 * then return [build]. Call the methods directly when writing hand-crafted validators.
 *
 * ```kotlin
 * val result = ValidationContext().apply {
 *     ensure(name.isNotBlank(), FieldRef("name"), "name must not be blank")
 *     ensure(email.contains("@"), FieldRef("email"), "email must contain @")
 * }.build()
 * ```
 *
 * All `ensure*` methods are non-throwing — they accumulate errors rather than failing fast,
 * so every rule is checked even when earlier ones have already failed.
 */
class ValidationContext {
    private val errors = mutableListOf<ValidationError>()

    /**
     * Records an error for [field] if [condition] is `false`.
     *
     * @param condition The boolean expression to check (e.g. `email.contains("@")`).
     * @param field     The field the check applies to.
     * @param message   Description of the failure, recorded verbatim in [ValidationError.message].
     */
    fun ensure(condition: Boolean, field: FieldRef, message: String) {
        if (!condition) errors += ValidationError(field, message)
    }

    /**
     * Records an error when none of [fields] are provided.
     *
     * Intended for cross-field "at least one of X, Y, Z must be set" constraints.
     * Pass the fields that are actually present (non-null / non-blank) as [fields];
     * an error is recorded against a synthetic `<group>` ref when the vararg is empty.
     *
     * @param fields  The fields that are considered present for this check.
     * @param message Description of the failure.
     */
    fun ensureAtLeastOne(vararg fields: FieldRef, message: String) {
        if (fields.isEmpty()) errors += ValidationError(FieldRef("<group>"), message)
    }

    /**
     * Records an error for each of [fields] when [present] is `false`.
     *
     * Intended for "all-or-none" constraints: either every field in the group must be
     * supplied, or none of them should be. Pass `true` when all are present, `false`
     * when the group is only partially filled.
     *
     * @param present Whether all fields in the group are considered present.
     * @param fields  The fields in the group. An error is recorded against each one on failure.
     * @param message Description of the failure, attached to every affected field.
     */
    fun ensureAllOrNone(present: Boolean, vararg fields: FieldRef, message: String) {
        if (!present) fields.forEach { errors += ValidationError(it, message) }
    }

    /**
     * Runs [block] only when [condition] is `true`.
     *
     * Use this to gate a group of checks behind a precondition without nesting manual
     * `if` statements throughout the caller:
     * ```kotlin
     * ensureIf(isUpdate) {
     *     ensure(id != null, FieldRef("id"), "id is required for updates")
     * }
     * ```
     *
     * @param condition When `false`, [block] is skipped entirely.
     * @param block     Additional validation logic executed within this context.
     */
    fun ensureIf(condition: Boolean, block: ValidationContext.() -> Unit) {
        if (condition) block()
    }

    /**
     * Finalises validation and returns the result.
     *
     * Returns [ValidationResult.Valid] when no errors were recorded, or
     * [ValidationResult.Invalid] containing all accumulated errors otherwise.
     */
    fun build(): ValidationResult =
        if (errors.isEmpty()) ValidationResult.Valid
        else ValidationResult.Invalid(errors.toList())
}

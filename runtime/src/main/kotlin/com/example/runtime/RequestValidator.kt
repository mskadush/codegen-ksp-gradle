package com.example.runtime

/**
 * Custom validation hook invoked after a request object is constructed.
 *
 * Implement this interface and reference the class via the spec's validator parameter
 * to run cross-field or business-rule validation that goes beyond single-field [com.example.annotations.FieldValidator] checks.
 *
 * @param T The generated request class being validated.
 */
interface RequestValidator<T> {
    /**
     * Validates [request], throwing an appropriate exception if validation fails.
     *
     * @param request The request object to validate.
     * @param context Caller-supplied context (e.g. authenticated user, tenant).
     */
    fun validate(request: T, context: Any)
}

/**
 * Default [RequestValidator] that performs no validation.
 */
class NoOpValidator<T> : RequestValidator<T> {
    override fun validate(request: T, context: Any) = Unit
}

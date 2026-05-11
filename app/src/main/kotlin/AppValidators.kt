import za.skadush.codegen.gradle.annotations.FieldValidator
import za.skadush.codegen.gradle.generated.UserCreateRequest
import za.skadush.codegen.gradle.runtime.ObjectValidator
import za.skadush.codegen.gradle.runtime.ValidationContext

object EmailValidator : FieldValidator<String> {
    override val message = "must be a valid email address"
    override fun validate(value: String) = value.contains("@")
}

object NotBlankValidator : FieldValidator<String> {
    override val message = "must not be blank"
    override fun validate(value: String) = value.isNotBlank()
}

/**
 * Cross-field rule: the email's local-part must not equal the user's name.
 *
 * Demonstrates [ObjectValidator] reading two fields and attributing the failure
 * to the specific field at fault.
 */
object EmailMatchesNameValidator : ObjectValidator<UserCreateRequest> {
    override fun validate(value: UserCreateRequest, ctx: ValidationContext) {
        val local = value.email.substringBefore('@')
        if (local.equals(value.name, ignoreCase = true)) {
            ctx.error("email", "local-part must not be the same as name")
        }
    }
}

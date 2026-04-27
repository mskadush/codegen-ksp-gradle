import za.skadush.codegen.gradle.annotations.FieldValidator

object EmailValidator : FieldValidator<String> {
    override val message = "must be a valid email address"
    override fun validate(value: String) = value.contains("@")
}

object NotBlankValidator : FieldValidator<String> {
    override val message = "must not be blank"
    override fun validate(value: String) = value.isNotBlank()
}

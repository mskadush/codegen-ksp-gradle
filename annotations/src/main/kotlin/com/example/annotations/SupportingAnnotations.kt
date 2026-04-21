package com.example.annotations

import kotlin.reflect.KClass
import org.intellij.lang.annotations.Language

/**
 * Marks an annotation class as a validation rule whose body is the given [expression].
 *
 * The processor reads this annotation from a rule class referenced in [FieldSpec.rules] and emits
 * a `require(...)` call in the generated class's `init {}` block.
 *
 * Placeholders in [expression]:
 * - `{field}` — replaced with the actual field name.
 * - `{paramName}` — replaced with the value of the rule annotation's own parameter of that name
 *   (e.g. `{value}` for [Rule.MinLength]).
 *
 * Example — consumer-defined rule:
 * ```kotlin
 * @RuleExpression("{field}.startsWith(\"ACM-\")")
 * annotation class AcmPrefix
 * ```
 * Processor emits: `require(code.startsWith("ACM-"))`
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class RuleExpression(val expression: String)

/**
 * Represents an arbitrary annotation to be emitted on a generated class or field.
 *
 * Use this when you need the code generator to attach framework-specific annotations
 * (e.g. JPA, Jackson) that are not natively modelled by the DSL.
 *
 * Each entry in [members] is a `"paramName=value"` string. Enum values may be expressed
 * as short names (e.g. `"fetch=LAZY"`); the processor resolves the fully-qualified enum
 * type from the annotation class's parameter declaration and adds the required import.
 *
 * Examples:
 * ```kotlin
 * CustomAnnotation(Table::class, members = ["name=\"users\"", "schema=\"public\""])
 * CustomAnnotation(OneToMany::class, members = ["fetch=LAZY", "cascade=ALL"])
 * ```
 *
 * @param annotation The annotation class to emit.
 * @param members Key-value pairs for the annotation's parameters, each as `"name=value"`.
 */
annotation class CustomAnnotation(
    val annotation: KClass<out Annotation>,
    @Language("kotlin") val members: Array<String> = []
)

/**
 * Declares a database index to be generated on the enclosing entity's table.
 *
 * Referenced from [ClassSpec.annotations] via a [CustomAnnotation] wrapping the framework's
 * index annotation, or directly in a future index-specific DSL.
 *
 * @param columns One or more column names that form the index key.
 * @param unique When `true`, the generated index enforces uniqueness.
 * @param name Optional explicit index name; the generator derives a name when left blank.
 */
annotation class Index(
    val columns: Array<String>,
    val unique: Boolean = false,
    val name: String = ""
)

/**
 * Built-in validation rules for use in [FieldSpec.rules].
 *
 * Each nested annotation is annotated with [@RuleExpression] so the processor can emit
 * the correct `require(...)` body. Consumer-defined rule annotations follow the same pattern —
 * annotate any annotation class with [@RuleExpression] and reference it in [FieldSpec.rules].
 */
annotation class Rule {
    @RuleExpression("{field} != null")
    /** Field must be present and non-null. */
    annotation class Required

    @RuleExpression("{field}.contains(\"@\")")
    /** Field must be a well-formed email address. */
    annotation class Email

    @RuleExpression("{field}.isNotBlank()")
    /** Field must not be blank (non-null and contains at least one non-whitespace character). */
    annotation class NotBlank

    @RuleExpression("{field} > 0")
    /** Numeric field must be greater than zero. */
    annotation class Positive

    @RuleExpression("{field}.isBefore(java.time.LocalDate.now())")
    /** Date/time field must be in the past. */
    annotation class Past

    @RuleExpression("{field}.isAfter(java.time.LocalDate.now())")
    /** Date/time field must be in the future. */
    annotation class Future

    @RuleExpression("{field}.length >= {value}")
    /** String field must have at least [value] characters. */
    annotation class MinLength(val value: Int)

    @RuleExpression("{field}.length <= {value}")
    /** String field must have at most [value] characters. */
    annotation class MaxLength(val value: Int)

    @RuleExpression("{field} >= {value}")
    /** Numeric field must be greater than or equal to [value]. */
    annotation class Min(val value: Double)

    @RuleExpression("{field} <= {value}")
    /** Numeric field must be less than or equal to [value]. */
    annotation class Max(val value: Double)

    @RuleExpression("Regex(\"{regex}\").matches({field})")
    /** String field must match the given [regex]; an optional [message] overrides the default error. */
    annotation class Pattern(val regex: String, val message: String = "")

    /** Calls a custom validation function referenced by fully-qualified name in [fn]. */
    annotation class Custom(val fn: String)
}

package za.skadush.codegen.gradle.annotations

import org.intellij.lang.annotations.Language
import kotlin.reflect.KClass

/**
 * Injects a synthetic field into one or more generated output classes; the field has no
 * counterpart in the domain class.
 *
 * Unlike [FieldOverride], which overrides fields that *originate from* the domain class, [AddField]
 * adds a brand-new field. Typical uses include persistence metadata (`@Version`,
 * `@CreationTimestamp`) or computed display fields that belong only to a specific output shape.
 *
 * **Mapper behaviour**: added fields are emitted as constructor parameters with a default value,
 * so the generated `to<Suffix>()` mappers omit them entirely and Kotlin fills the default
 * automatically.
 *
 * **Constraints**:
 * - [name] must not be blank.
 * - A non-partial, non-nullable field must have a non-blank [defaultValue] — without one the
 *   mapper cannot synthesise a value and the processor will report an error.
 * - [type] supports only simple, non-parameterised `KClass` references (e.g. `Long::class`,
 *   `java.time.Instant::class`). Parameterised types such as `List<String>` are not supported.
 *
 * **Example**:
 * ```kotlin
 * @AddField(
 *     for_ = ["Entity"],
 *     name = "version",
 *     type = Long::class,
 *     defaultValue = "0L",
 *     annotations = [CustomAnnotation(annotation = jakarta.persistence.Version::class)]
 * )
 * object UserSpec
 * ```
 *
 * @param for_         One or more [ClassSpec.suffix] values this field is added to.
 *                     Must match the `suffix` strings declared on `@ClassSpec`.
 * @param name         Field name in the generated class.
 * @param type         Field type. Use simple, non-parameterised types (e.g. `Long::class`,
 *                     `String::class`, `java.time.Instant::class`).
 * @param nullable     When `true`, the generated field type is nullable.
 * @param defaultValue Kotlin expression used as the constructor parameter default
 *                     (e.g. `"0L"`, `"\"\""`, `"java.time.Instant.now()"`).
 *                     Required for non-partial, non-nullable fields.
 * @param annotations  Annotations forwarded verbatim to the generated field.
 *
 * @see ClassSpec
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class AddField(
    val for_: Array<String>,
    val name: String,
    val type: KClass<*>,
    val nullable: Boolean = false,
    @Language("kotlin", prefix = "val a = ")
    val defaultValue: String = "",
    val annotations: Array<CustomAnnotation> = [],
)
package za.skadush.codegen.gradle.annotations

import org.intellij.lang.annotations.Language

/**
 * Default-value configuration for a generated field.
 *
 * Used as the `default` parameter on [FieldSpec], [FieldOverride], and [AddField]. The no-arg
 * sentinel `Default()` means "no default configured" — the field has no `= ...` initializer in
 * the generated constructor.
 *
 * **Examples**:
 * ```kotlin
 * @FieldSpec(property = "name",      default = Default(value = "\"anon\""))
 * @FieldSpec(property = "createdAt", default = Default(inherit = true))
 *
 * @FieldOverride(for_ = ["Entity"], property = "createdAt", default = Default(clearInherited = true))
 * ```
 *
 * **Mutual-exclusion rules** (enforced by the processor):
 * - [value] non-empty and [inherit] = `true` on the same `Default` is an error.
 * - [clearInherited] = `true` with [value] non-empty or [inherit] = `true` is an error.
 * - [clearInherited] = `true` is only valid on [FieldOverride]; setting it on [FieldSpec] or [AddField]
 *   is an error.
 *
 * **`value` is spliced verbatim** into the generated constructor as `= <value>`. String literals
 * must include their own quotes (`value = "\"hi\""`). The processor runs a light syntactic check
 * (balanced quotes/parens, no trailing `;`) and reports errors at the annotation site.
 *
 * **`inherit` reads the source property's default expression** via KSP `Location` offsets. If the
 * source property lives in a compiled dependency JAR (no readable source location), the
 * processor errors and suggests using an explicit [value] instead.
 *
 * @param value   Kotlin expression to emit as the constructor-parameter default. Mutually
 *                exclusive with [inherit] and [clearInherited].
 * @param inherit When `true`, copies the default expression from the source property.
 *                Mutually exclusive with [value] and [clearInherited].
 * @param clearInherited   When `true`, removes a default inherited from the class-level [FieldSpec] for
 *                the named output(s). Only valid on [FieldOverride]. Mutually exclusive with
 *                [value] and [inherit].
 */
annotation class Default(
    @Language("kotlin", prefix = "val a = ")
    val value: String = "",
    val inherit: Boolean = false,
    val clearInherited: Boolean = false,
)

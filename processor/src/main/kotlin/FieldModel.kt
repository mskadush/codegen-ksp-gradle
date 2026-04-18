import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeName

/**
 * Holds resolved metadata for a single field extracted from a domain class.
 *
 * Produced by [ClassResolver.resolve] / [ClassResolver.resolveWithKinds] and consumed by
 * generators to emit properties and mapping expressions in generated classes.
 *
 * @param originalName The property/parameter name as declared in the domain class.
 * @param originalType The KSP type reference for the field, used for further type inspection.
 * @param resolvedType The KotlinPoet [TypeName] of the *domain* type (unchanged from source).
 * @param fieldKind Classification of the field's type relative to the [SpecRegistry].
 * @param sourceExpression For INLINE-flattened fields: the dotted source path
 *   (e.g. `"this.address.street"`). `null` means use `"this.$originalName"` as normal.
 * @param targetConfigs Field-level override annotations (e.g. `@EntityField`) collected from the
 *   spec class. Reserved for use by the generators; currently not populated.
 */
data class FieldModel(
    val originalName: String,
    val originalType: KSType,
    val resolvedType: TypeName,
    val fieldKind: FieldKind = FieldKind.Primitive,
    val sourceExpression: String? = null,
    val targetConfigs: List<KSAnnotation> = emptyList(),
)

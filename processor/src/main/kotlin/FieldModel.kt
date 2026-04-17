import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeName

/**
 * Holds resolved metadata for a single field extracted from a domain class.
 *
 * Produced by [ClassResolver.resolve] and consumed by [EntityGenerator] (and future generators)
 * to emit properties in generated classes.
 *
 * @param originalName The property/parameter name as declared in the domain class.
 * @param originalType The KSP type reference for the field, used for further type inspection.
 * @param resolvedType The KotlinPoet [TypeName] representation used when writing generated code.
 * @param targetConfigs Field-level override annotations (e.g. `@EntityField`) collected from the
 *   spec class. Reserved for use by the generators; currently not populated.
 */
data class FieldModel(
    val originalName: String,
    val originalType: KSType,
    val resolvedType: TypeName,
    val targetConfigs: List<KSAnnotation> = emptyList(),
)

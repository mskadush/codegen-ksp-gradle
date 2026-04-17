import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeName

data class FieldModel(
    val originalName: String,
    val originalType: KSType,
    val resolvedType: TypeName,
    val targetConfigs: List<KSAnnotation> = emptyList(),
)

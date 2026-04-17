import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toTypeName

class ClassResolver(private val logger: KSPLogger) {
    fun resolve(cls: KSClassDeclaration): List<FieldModel>? {
        if (Modifier.DATA !in cls.modifiers) {
            logger.error("${cls.simpleName.asString()} is not a data class — @EntitySpec requires a data class")
            return null
        }
        val ctor = cls.primaryConstructor ?: run {
            logger.error("${cls.simpleName.asString()} has no primary constructor")
            return null
        }
        return ctor.parameters.map { param ->
            FieldModel(
                originalName = param.name!!.asString(),
                originalType = param.type.resolve(),
                resolvedType = param.type.toTypeName(),
            )
        }
    }
}

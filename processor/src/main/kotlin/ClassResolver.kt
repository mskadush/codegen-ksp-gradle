import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toTypeName

/**
 * Validates a domain class and extracts its primary constructor parameters as [FieldModel]s.
 *
 * Used by [EntityGenerator] (and future generators) to obtain the list of fields that should
 * appear in a generated class. Validation errors are reported through [KSPLogger] and cause
 * the method to return `null` so the caller can skip generation cleanly.
 *
 * @param logger KSP logger used to emit compile-time error messages.
 */
class ClassResolver(private val logger: KSPLogger) {

    /**
     * Resolves [cls] into a list of [FieldModel]s derived from its primary constructor parameters.
     *
     * Validation rules:
     * - [cls] must be a `data class` (has the [Modifier.DATA] modifier).
     * - [cls] must declare a primary constructor.
     *
     * @param cls The domain class declaration to resolve.
     * @return A list of [FieldModel]s, one per constructor parameter, or `null` if validation fails.
     */
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

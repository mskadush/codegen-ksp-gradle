import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a `data class` DTO from a spec class annotated with `@DtoSpec`.
 *
 * The generated class is named `<prefix><DomainClass><suffix>` and mirrors the primary constructor
 * of the domain class referenced by `@DtoSpec.for_`. Field-level overrides from `@DtoField` on
 * the spec are applied: excluded fields are omitted, renamed fields use the new name in the
 * generated class, and `NullableOverride` adjusts Kotlin nullability. Class-level and field-level
 * annotations are forwarded verbatim via [DbAnnotation] passthrough.
 *
 * @param codeGenerator KSP code generation API used to write output files.
 * @param logger KSP logger for compile-time diagnostics.
 * @param classResolver Resolves and validates the domain class's fields.
 */
class DtoGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
    /**
     * Generates a DTO class for the given [spec] class.
     *
     * @param spec The `@DtoSpec`-annotated class declaration from the compilation round.
     */
    fun generate(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "DtoSpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()

        val suffix = annotation.arguments.firstOrNull { it.name?.asString() == "suffix" }?.value as? String ?: "Dto"
        val prefix = annotation.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value as? String ?: ""
        val dtoName = "$prefix${domainName}$suffix"

        val fields = classResolver.resolve(domainClass) ?: return

        val overrideMap: Map<String, KSAnnotation> = spec.annotations
            .filter { it.shortName.asString() == "DtoField" }
            .associateBy { it.argString("property") }

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(dtoName)
            .addModifiers(KModifier.DATA)
        annotation.dbAnnotationSpecs().forEach { classBuilder.addAnnotation(it) }

        var emittedCount = 0
        for (field in fields) {
            val override = overrideMap[field.originalName]

            if (override?.argBool("exclude") == true) continue

            val finalType = when (override?.argEnumName("nullable") ?: "UNSET") {
                "YES" -> field.resolvedType.copy(nullable = true)
                "NO"  -> field.resolvedType.copy(nullable = false)
                else  -> field.resolvedType
            }

            val rename = override?.argString("rename") ?: ""
            val fieldName = if (rename.isNotBlank()) rename else field.originalName

            ctorBuilder.addParameter(fieldName, finalType)
            val propSpec = PropertySpec.builder(fieldName, finalType)
                .initializer(fieldName)
                .apply { override?.dbAnnotationSpecs()?.forEach { addAnnotation(it) } }
                .build()
            classBuilder.addProperty(propSpec)
            emittedCount++
        }
        classBuilder.primaryConstructor(ctorBuilder.build())

        FileSpec.builder("", dtoName)
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("DtoGenerator: generated $dtoName with $emittedCount field(s)")
    }
}

// --- KSAnnotation helpers ---

private fun KSAnnotation.argString(name: String): String =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

private fun KSAnnotation.argBool(name: String): Boolean =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean ?: false

private fun KSAnnotation.argEnumName(name: String): String {
    val raw = arguments.firstOrNull { it.name?.asString() == name }?.value ?: return "UNSET"
    return when (raw) {
        is KSType -> raw.declaration.simpleName.asString()
        is KSClassDeclaration -> raw.simpleName.asString()
        else -> raw.toString().substringAfterLast('.')
    }
}

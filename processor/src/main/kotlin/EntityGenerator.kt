import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a `data class` entity from a spec class annotated with `@EntitySpec`.
 *
 * The generated class is named `<DomainClass>Entity` and mirrors the primary constructor of
 * the domain class referenced by `@EntitySpec.for_`. Field-level overrides from `@EntityField`
 * on the spec are applied: excluded fields are omitted and `NullableOverride` adjusts Kotlin
 * nullability. Class-level and field-level annotations are forwarded verbatim via [DbAnnotation]
 * passthrough — the processor has no knowledge of specific frameworks.
 *
 * Nested domain class fields are substituted with their generated entity type (e.g.
 * `address: Address` becomes `address: AddressEntity`) when the nested type has its own spec.
 *
 * @param codeGenerator KSP code generation API used to write output files.
 * @param logger KSP logger for compile-time diagnostics.
 * @param classResolver Resolves and validates the domain class's fields.
 */
class EntityGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
    fun generate(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val entityName = "${domainName}Entity"

        val unmappedStrategy = annotation.argEnumName("unmappedNestedStrategy").let {
            if (it == "UNSET") "FAIL" else it
        }

        val overrideMap: Map<String, KSAnnotation> = spec.annotations
            .filter { it.shortName.asString() == "EntityField" }
            .associateBy { it.argString("property") }

        val fields = classResolver.resolveWithKinds(domainClass, unmappedStrategy, overrideMap) ?: return

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(entityName).addModifiers(KModifier.DATA)
        annotation.dbAnnotationSpecs().forEach { classBuilder.addAnnotation(it) }

        var emittedCount = 0
        for (field in fields) {
            val override = overrideMap[field.originalName]

            if (override?.argBool("exclude") == true) continue

            // Determine base type from FieldKind (substitutes nested types)
            val baseType: TypeName = when (val kind = field.fieldKind) {
                is FieldKind.MappedObject -> kind.targetClassName
                is FieldKind.MappedCollection -> {
                    ClassName.bestGuess(kind.collectionFQN).parameterizedBy(kind.targetClassName)
                }
                else -> field.resolvedType
            }

            val finalType = when (override?.argEnumName("nullable") ?: "UNSET") {
                "YES" -> baseType.copy(nullable = true)
                "NO"  -> baseType.copy(nullable = false)
                else  -> baseType
            }

            ctorBuilder.addParameter(field.originalName, finalType)

            val propBuilder = PropertySpec.builder(field.originalName, finalType)
                .initializer(field.originalName)
            override?.dbAnnotationSpecs()?.forEach { propBuilder.addAnnotation(it) }
            classBuilder.addProperty(propBuilder.build())
            emittedCount++
        }
        classBuilder.primaryConstructor(ctorBuilder.build())

        FileSpec.builder("", entityName)
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("EntityGenerator: generated $entityName with $emittedCount field(s)")
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

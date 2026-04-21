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
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a `data class` entity from a [ClassSpec] instance (non-partial, no request rules).
 *
 * Reads merged field overrides from [ClassField] + [FieldSpec] on the spec class and applies:
 * - `exclude = true` → field omitted
 * - `nullable` → overrides Kotlin nullability
 * - `annotations` → forwarded verbatim to the generated field
 * - Class-level annotations from [ClassSpec.annotations] forwarded to the generated class.
 */
class EntityGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
    fun generate(spec: KSClassDeclaration, classSpecAnn: KSAnnotation) {
        val domainClass = (classSpecAnn.arguments.first { it.name?.asString() == "for_" }.value as KSType)
            .declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val suffix = classSpecAnn.argString("suffix")
        val prefix = classSpecAnn.argString("prefix")
        val outputName = "$prefix$domainName$suffix"

        val unmappedStrategy = classSpecAnn.argEnumName("unmappedNestedStrategy")
            .let { if (it == "UNSET") "FAIL" else it }

        val overrides = spec.mergedFieldOverrides(suffix)
        val fields = classResolver.resolveWithKinds(domainClass, unmappedStrategy, overrides) ?: return

        val imports = mutableListOf<Pair<String, String>>()
        val addImport: (String, String) -> Unit = { pkg, name -> imports.add(pkg to name) }

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(outputName).addModifiers(KModifier.DATA)
        classSpecAnn.customAnnotationSpecs(addImport).forEach { classBuilder.addAnnotation(it) }

        var emittedCount = 0
        for (field in fields) {
            val override = overrides[field.originalName]

            if (override?.exclude == true) continue

            val baseType = when (val kind = field.fieldKind) {
                is FieldKind.MappedObject -> kind.targetClassName
                is FieldKind.MappedCollection ->
                    ClassName.bestGuess(kind.collectionFQN).parameterizedBy(kind.targetClassName)
                else -> field.resolvedType
            }

            val finalType = when (override?.nullable ?: "UNSET") {
                "YES" -> baseType.copy(nullable = true)
                "NO"  -> baseType.copy(nullable = false)
                else  -> baseType
            }

            ctorBuilder.addParameter(field.originalName, finalType)
            val propBuilder = PropertySpec.builder(field.originalName, finalType)
                .initializer(field.originalName)
            override?.allAnnotations?.forEach { raw ->
                raw.toAnnotationSpec(addImport)?.let { propBuilder.addAnnotation(it) }
            }
            classBuilder.addProperty(propBuilder.build())
            emittedCount++
        }
        classBuilder.primaryConstructor(ctorBuilder.build())

        val fileBuilder = FileSpec.builder("", outputName)
        imports.forEach { (pkg, name) -> fileBuilder.addImport(pkg, name) }
        fileBuilder.addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("EntityGenerator: generated $outputName with $emittedCount field(s)")
    }
}

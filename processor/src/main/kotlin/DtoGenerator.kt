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
 * Generates a `data class` DTO from a [ClassSpec] instance (non-partial, no request rules).
 *
 * Reads merged field overrides from [ClassField] + [FieldSpec] and applies:
 * - `exclude = true` → field omitted
 * - `rename` → alternative name in the generated class
 * - `nullable` → overrides Kotlin nullability
 * - `annotations` → forwarded verbatim to the generated field
 */
class DtoGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
    var bundleRegistry: BundleRegistry = BundleRegistry.EMPTY

    fun generate(spec: KSClassDeclaration, classSpecAnn: KSAnnotation) {
        val domainClass = (classSpecAnn.arguments.first { it.name?.asString() == "for_" }.value as KSType)
            .declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val suffix = classSpecAnn.argString("suffix")
        val prefix = classSpecAnn.argString("prefix")
        val outputName = "$prefix$domainName$suffix"

        val bundleNames = classSpecAnn.argStringList("bundles")
        val mergeStrategy = classSpecAnn.argEnumName("bundleMergeStrategy")
        val overrides = spec.resolveWithBundles(suffix, bundleNames, mergeStrategy, bundleRegistry, logger)
        val fields = classResolver.resolve(domainClass) ?: return

        val imports = mutableListOf<Pair<String, String>>()
        val addImport: (String, String) -> Unit = { pkg, name -> imports.add(pkg to name) }

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(outputName).addModifiers(KModifier.DATA)
        classSpecAnn.customAnnotationSpecs(addImport).forEach { classBuilder.addAnnotation(it) }

        var emittedCount = 0
        for (field in fields) {
            val override = overrides[field.originalName]
            if (override?.exclude == true) continue

            val finalType = when (override?.nullable ?: "UNSET") {
                "YES" -> field.resolvedType.copy(nullable = true)
                "NO"  -> field.resolvedType.copy(nullable = false)
                else  -> field.resolvedType
            }

            val fieldName = override?.rename?.takeIf { it.isNotBlank() } ?: field.originalName

            ctorBuilder.addParameter(fieldName, finalType)
            val propBuilder = PropertySpec.builder(fieldName, finalType)
                .initializer(fieldName)
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

        logger.info("DtoGenerator: generated $outputName with $emittedCount field(s)")
    }
}

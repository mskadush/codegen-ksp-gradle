import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a `data class` create-request from a spec class annotated with `@RequestSpec` + `@CreateSpec`.
 *
 * The generated class is named `<DomainClass><suffix>` (default suffix: `"CreateRequest"`) and
 * mirrors the primary constructor of the domain class. An `init {}` block is emitted containing
 * `require()` calls for each validation rule declared in `@CreateField.rules`, `minLength`, and
 * `maxLength`.
 *
 * @param codeGenerator KSP code generation API used to write output files.
 * @param logger KSP logger for compile-time diagnostics.
 * @param classResolver Resolves and validates the domain class's fields.
 */
class RequestGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {

    /**
     * Generates the create-request class for the given [spec] class.
     *
     * @param spec The `@RequestSpec`-annotated class declaration from the compilation round.
     */
    fun generate(spec: KSClassDeclaration) {
        val requestSpecAnnotation = spec.annotations.firstOrNull { it.shortName.asString() == "RequestSpec" }
            ?: return
        val createSpecAnnotation = spec.annotations.firstOrNull { it.shortName.asString() == "CreateSpec" }
            ?: return

        val forArg = requestSpecAnnotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()

        val suffix = createSpecAnnotation.arguments.firstOrNull { it.name?.asString() == "suffix" }?.value as? String
            ?: "CreateRequest"
        val requestName = "$domainName$suffix"

        val fields = classResolver.resolve(domainClass) ?: return

        // Build override map from the @CreateSpec.fields array
        val fieldOverrides = buildFieldOverrideMap(createSpecAnnotation)

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(requestName)
            .addModifiers(KModifier.DATA)

        val requireStatements = mutableListOf<String>()

        for (field in fields) {
            val override = fieldOverrides[field.originalName]

            if (override?.argBool("exclude") == true) continue

            ctorBuilder.addParameter(field.originalName, field.resolvedType)
            classBuilder.addProperty(
                PropertySpec.builder(field.originalName, field.resolvedType)
                    .initializer(field.originalName)
                    .build()
            )

            // Collect require() calls for this field
            if (override != null) {
                requireStatements += buildRequireStatements(field.originalName, override)
            }
        }

        classBuilder.primaryConstructor(ctorBuilder.build())

        if (requireStatements.isNotEmpty()) {
            val initBlock = CodeBlock.builder()
            for (stmt in requireStatements) {
                initBlock.addStatement(stmt)
            }
            classBuilder.addInitializerBlock(initBlock.build())
        }

        FileSpec.builder("", requestName)
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("RequestGenerator: generated $requestName with ${requireStatements.size} require() call(s)")
    }

    /**
     * Reads the `fields` array from a `@CreateSpec` annotation and returns a map from property
     * name to its `@CreateField` annotation.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildFieldOverrideMap(createSpec: KSAnnotation): Map<String, KSAnnotation> {
        val fieldsArg = createSpec.arguments.firstOrNull { it.name?.asString() == "fields" }
            ?: return emptyMap()
        val fieldAnnotations = fieldsArg.value as? List<KSAnnotation> ?: return emptyMap()
        return fieldAnnotations.associateBy { it.argString("property") }
    }

    /**
     * Maps each rule declared in a `@CreateField` annotation to a `require()` statement string.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildRequireStatements(fieldName: String, createField: KSAnnotation): List<String> {
        val statements = mutableListOf<String>()

        // Simple rules: Array<KClass<out Annotation>> — each element is a KSType
        val rulesArg = createField.arguments.firstOrNull { it.name?.asString() == "rules" }
        val ruleTypes = rulesArg?.value as? List<KSType> ?: emptyList()

        for (ruleType in ruleTypes) {
            val ruleName = ruleType.declaration.simpleName.asString()
            val stmt = ruleToRequireStatement(fieldName, ruleName)
            if (stmt != null) statements += stmt
        }

        // Parameterised rules via dedicated fields
        val minLength = createField.arguments.firstOrNull { it.name?.asString() == "minLength" }?.value as? Int ?: -1
        val maxLength = createField.arguments.firstOrNull { it.name?.asString() == "maxLength" }?.value as? Int ?: -1

        if (minLength >= 0) {
            statements += """require($fieldName.length >= $minLength) { "$fieldName must be at least $minLength characters" }"""
        }
        if (maxLength >= 0) {
            statements += """require($fieldName.length <= $maxLength) { "$fieldName must be at most $maxLength characters" }"""
        }

        return statements
    }

    private fun ruleToRequireStatement(fieldName: String, ruleName: String): String? = when (ruleName) {
        "Email"    -> """require($fieldName.contains("@")) { "$fieldName must be a valid email" }"""
        "NotBlank" -> """require($fieldName.isNotBlank()) { "$fieldName must not be blank" }"""
        "Required" -> """require($fieldName != null) { "$fieldName is required" }"""
        "Positive" -> """require($fieldName > 0) { "$fieldName must be positive" }"""
        "Past"     -> """require($fieldName.isBefore(java.time.LocalDate.now())) { "$fieldName must be in the past" }"""
        "Future"   -> """require($fieldName.isAfter(java.time.LocalDate.now())) { "$fieldName must be in the future" }"""
        else       -> { logger.warn("RequestGenerator: unknown rule '$ruleName' on field '$fieldName' — skipped"); null }
    }
}

// --- KSAnnotation helpers (local to this file) ---

private fun KSAnnotation.argString(name: String): String =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

private fun KSAnnotation.argBool(name: String): Boolean =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean ?: false

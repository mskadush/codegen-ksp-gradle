import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates `data class` request objects from spec classes annotated with `@RequestSpec`.
 *
 * - `@CreateSpec` → `<DomainClass>CreateRequest` with `require()` validation in `init {}`.
 * - `@UpdateSpec` → `<DomainClass>UpdateRequest`; when `partial = true` every field is nullable
 *   with a `= null` default and `require()` calls are wrapped in null-checks.
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
     * Entry point — processes both `@CreateSpec` and `@UpdateSpec` on the same spec class.
     *
     * @param spec The `@RequestSpec`-annotated class declaration from the compilation round.
     */
    fun generate(spec: KSClassDeclaration) {
        val requestSpecAnnotation = spec.annotations.firstOrNull { it.shortName.asString() == "RequestSpec" }
            ?: return

        val forArg = requestSpecAnnotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration

        spec.annotations.firstOrNull { it.shortName.asString() == "CreateSpec" }
            ?.let { generateCreate(domainClass, it) }

        spec.annotations.firstOrNull { it.shortName.asString() == "UpdateSpec" }
            ?.let { generateUpdate(domainClass, it) }
    }

    // --- CreateSpec ---

    private fun generateCreate(domainClass: KSClassDeclaration, createSpec: KSAnnotation) {
        val domainName = domainClass.simpleName.asString()
        val suffix = createSpec.arguments.firstOrNull { it.name?.asString() == "suffix" }?.value as? String
            ?: "CreateRequest"
        val requestName = "$domainName$suffix"

        val fields = classResolver.resolve(domainClass) ?: return
        val fieldOverrides = buildFieldOverrideMap(createSpec)

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(requestName).addModifiers(KModifier.DATA)
        createSpec.dbAnnotationSpecs().forEach { classBuilder.addAnnotation(it) }
        val requireStatements = mutableListOf<String>()

        for (field in fields) {
            val override = fieldOverrides[field.originalName]
            if (override?.argBool("exclude") == true) continue

            ctorBuilder.addParameter(field.originalName, field.resolvedType)
            val propSpec = PropertySpec.builder(field.originalName, field.resolvedType)
                .initializer(field.originalName)
                .apply { override?.dbAnnotationSpecs()?.forEach { addAnnotation(it) } }
                .build()
            classBuilder.addProperty(propSpec)

            if (override != null) {
                requireStatements += buildRequireStatements(field.originalName, override)
            }
        }

        classBuilder.primaryConstructor(ctorBuilder.build())

        if (requireStatements.isNotEmpty()) {
            val initBlock = CodeBlock.builder()
            for (stmt in requireStatements) initBlock.addStatement(stmt)
            classBuilder.addInitializerBlock(initBlock.build())
        }

        FileSpec.builder("", requestName)
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("RequestGenerator: generated $requestName with ${requireStatements.size} require() call(s)")
    }

    // --- UpdateSpec ---

    private fun generateUpdate(domainClass: KSClassDeclaration, updateSpec: KSAnnotation) {
        val domainName = domainClass.simpleName.asString()
        val suffix = updateSpec.arguments.firstOrNull { it.name?.asString() == "suffix" }?.value as? String
            ?: "UpdateRequest"
        val partial = updateSpec.arguments.firstOrNull { it.name?.asString() == "partial" }?.value as? Boolean
            ?: true
        val requestName = "$domainName$suffix"

        val fields = classResolver.resolve(domainClass) ?: return
        val fieldOverrides = buildFieldOverrideMap(updateSpec)

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(requestName).addModifiers(KModifier.DATA)
        updateSpec.dbAnnotationSpecs().forEach { classBuilder.addAnnotation(it) }
        val initBlock = CodeBlock.builder()
        var hasValidation = false

        for (field in fields) {
            val override = fieldOverrides[field.originalName]
            if (override?.argBool("exclude") == true) continue

            if (partial) {
                val nullableType = field.resolvedType.copy(nullable = true)
                val param = ParameterSpec.builder(field.originalName, nullableType)
                    .defaultValue("null")
                    .build()
                ctorBuilder.addParameter(param)
                val partialPropSpec = PropertySpec.builder(field.originalName, nullableType)
                    .initializer(field.originalName)
                    .apply { override?.dbAnnotationSpecs()?.forEach { addAnnotation(it) } }
                    .build()
                classBuilder.addProperty(partialPropSpec)

                if (override != null) {
                    val stmts = buildRequireStatements(field.originalName, override)
                    if (stmts.isNotEmpty()) {
                        initBlock.beginControlFlow("if (%N != null)", field.originalName)
                        for (stmt in stmts) initBlock.addStatement(stmt)
                        initBlock.endControlFlow()
                        hasValidation = true
                    }
                }
            } else {
                ctorBuilder.addParameter(field.originalName, field.resolvedType)
                val nonPartialPropSpec = PropertySpec.builder(field.originalName, field.resolvedType)
                    .initializer(field.originalName)
                    .apply { override?.dbAnnotationSpecs()?.forEach { addAnnotation(it) } }
                    .build()
                classBuilder.addProperty(nonPartialPropSpec)

                if (override != null) {
                    val stmts = buildRequireStatements(field.originalName, override)
                    for (stmt in stmts) initBlock.addStatement(stmt)
                    if (stmts.isNotEmpty()) hasValidation = true
                }
            }
        }

        classBuilder.primaryConstructor(ctorBuilder.build())
        if (hasValidation) classBuilder.addInitializerBlock(initBlock.build())

        FileSpec.builder("", requestName)
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("RequestGenerator: generated $requestName (partial=$partial)")
    }

    // --- Helpers ---

    /**
     * Reads the `fields` array from a `@CreateSpec` or `@UpdateSpec` annotation and returns a map
     * from property name to its field annotation.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildFieldOverrideMap(spec: KSAnnotation): Map<String, KSAnnotation> {
        val fieldsArg = spec.arguments.firstOrNull { it.name?.asString() == "fields" }
            ?: return emptyMap()
        val fieldAnnotations = fieldsArg.value as? List<KSAnnotation> ?: return emptyMap()
        return fieldAnnotations.associateBy { it.argString("property") }
    }

    /**
     * Maps each rule declared in a `@CreateField` or `@UpdateField` annotation to a `require()` statement string.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildRequireStatements(fieldName: String, fieldAnnotation: KSAnnotation): List<String> {
        val statements = mutableListOf<String>()

        val rulesArg = fieldAnnotation.arguments.firstOrNull { it.name?.asString() == "rules" }
        val ruleTypes = rulesArg?.value as? List<KSType> ?: emptyList()

        for (ruleType in ruleTypes) {
            val ruleName = ruleType.declaration.simpleName.asString()
            val stmt = ruleToRequireStatement(fieldName, ruleName)
            if (stmt != null) statements += stmt
        }

        val minLength = fieldAnnotation.arguments.firstOrNull { it.name?.asString() == "minLength" }?.value as? Int ?: -1
        val maxLength = fieldAnnotation.arguments.firstOrNull { it.name?.asString() == "maxLength" }?.value as? Int ?: -1

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

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
 * Generates `data class` request objects from [ClassSpec] instances that contain validation rules.
 *
 * Output-kind is determined by the caller ([DomainMappingProcessorProvider]):
 * - `partial = false` + rules → create-request style (`init {}` with non-null fields)
 * - `partial = true`         → update-request style (all fields nullable + `= null` + `init {}`)
 *
 * Validation rules are driven by [@RuleExpression] on each rule annotation class.
 * The placeholder `{field}` is substituted with the actual field name; parameterised rules
 * (e.g. [Rule.MinLength]) substitute `{value}`, `{regex}`, etc. from the rule annotation's
 * own arguments at the call site.
 */
class RequestGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {

    fun generate(spec: KSClassDeclaration, classSpecAnn: KSAnnotation) {
        val domainClass = (classSpecAnn.arguments.first { it.name?.asString() == "for_" }.value as KSType)
            .declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val suffix  = classSpecAnn.argString("suffix")
        val prefix  = classSpecAnn.argString("prefix")
        val partial = classSpecAnn.argBool("partial")
        val requestName = "$prefix$domainName$suffix"

        val fields   = classResolver.resolve(domainClass) ?: return
        val overrides = spec.mergedFieldOverrides(suffix)

        val imports = mutableListOf<Pair<String, String>>()
        val addImport: (String, String) -> Unit = { pkg, name -> imports.add(pkg to name) }

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(requestName).addModifiers(KModifier.DATA)
        classSpecAnn.customAnnotationSpecs(addImport).forEach { classBuilder.addAnnotation(it) }

        val initBlock = CodeBlock.builder()
        var hasValidation = false

        for (field in fields) {
            val override = overrides[field.originalName]
            if (override?.exclude == true) continue

            val requireStmts = override?.let { buildRequireStatements(field.originalName, it) }
                ?: emptyList()

            if (partial) {
                val nullableType = field.resolvedType.copy(nullable = true)
                val param = ParameterSpec.builder(field.originalName, nullableType)
                    .defaultValue("null")
                    .build()
                ctorBuilder.addParameter(param)
                val propSpec = PropertySpec.builder(field.originalName, nullableType)
                    .initializer(field.originalName)
                    .apply {
                        override?.allAnnotations?.forEach { raw ->
                            raw.toAnnotationSpec(addImport)?.let { addAnnotation(it) }
                        }
                    }
                    .build()
                classBuilder.addProperty(propSpec)

                if (requireStmts.isNotEmpty()) {
                    initBlock.beginControlFlow("if (%N != null)", field.originalName)
                    requireStmts.forEach { initBlock.addStatement(it) }
                    initBlock.endControlFlow()
                    hasValidation = true
                }
            } else {
                ctorBuilder.addParameter(field.originalName, field.resolvedType)
                val propSpec = PropertySpec.builder(field.originalName, field.resolvedType)
                    .initializer(field.originalName)
                    .apply {
                        override?.allAnnotations?.forEach { raw ->
                            raw.toAnnotationSpec(addImport)?.let { addAnnotation(it) }
                        }
                    }
                    .build()
                classBuilder.addProperty(propSpec)

                if (requireStmts.isNotEmpty()) {
                    requireStmts.forEach { initBlock.addStatement(it) }
                    hasValidation = true
                }
            }
        }

        classBuilder.primaryConstructor(ctorBuilder.build())
        if (hasValidation) classBuilder.addInitializerBlock(initBlock.build())

        val fileBuilder = FileSpec.builder("", requestName)
        imports.forEach { (pkg, name) -> fileBuilder.addImport(pkg, name) }
        fileBuilder.addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("RequestGenerator: generated $requestName (partial=$partial, ${if (hasValidation) "with" else "no"} validation)")
    }

    // ---------------------------------------------------------------------------
    // Rule → require() statement generation via @RuleExpression
    // ---------------------------------------------------------------------------

    private fun buildRequireStatements(fieldName: String, override: MergedOverride): List<String> {
        val statements = mutableListOf<String>()
        for (ruleType in override.rules) {
            val ruleDecl = ruleType.declaration as? KSClassDeclaration ?: continue
            val ruleName = ruleDecl.simpleName.asString()

            // Find @RuleExpression on the rule annotation class
            val exprTemplate = ruleDecl.annotations
                .firstOrNull { it.shortName.asString() == "RuleExpression" }
                ?.argString("expression")

            if (exprTemplate == null) {
                logger.warn("RequestGenerator: rule '$ruleName' has no @RuleExpression — skipped")
                continue
            }

            // Substitute {field} with the actual field name
            var expr = exprTemplate.replace("{field}", fieldName)

            // If the expression still contains {placeholder} tokens, the rule has required
            // parameters (e.g. Rule.MinLength needs a value). Since rules are referenced as
            // KClass<*> (not instances), parameter values cannot be inferred at compile time.
            // Emit a warning and skip — define a custom single-value rule class instead:
            //   @RuleExpression("{field}.length >= 5")
            //   annotation class AtLeast5Chars
            val unresolved = Regex("\\{\\w+\\}").find(expr)
            if (unresolved != null) {
                logger.warn(
                    "RequestGenerator: rule '$ruleName' on '$fieldName' contains unresolved " +
                    "placeholder '${unresolved.value}'. Rules with parameters must be wrapped " +
                    "in a concrete annotation class annotated with @RuleExpression. Skipped."
                )
                continue
            }

            statements += """require($expr) { "$fieldName failed rule $ruleName" }"""
        }
        return statements
    }
}

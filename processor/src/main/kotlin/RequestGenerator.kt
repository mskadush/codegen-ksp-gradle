import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

private val VALIDATION_CONTEXT  = ClassName("com.example.runtime", "ValidationContext")
private val VALIDATION_RESULT   = ClassName("com.example.runtime", "ValidationResult")
private val FIELD_REF           = ClassName("com.example.runtime", "FieldRef")
private val VALIDATION_EXCEPTION = ClassName("com.example.runtime", "ValidationException")

/**
 * Generates `data class` request objects from [ClassSpec] instances that contain validation rules.
 *
 * Output-kind is determined by the caller ([DomainMappingProcessorProvider]):
 * - `partial = false` + rules → create-request style (non-null fields)
 * - `partial = true`         → update-request style (all fields nullable + `= null`)
 *
 * Every generated request class receives:
 * - `fun validate(): ValidationResult` — collects all rule failures into a structured result.
 * - `fun validateOrThrow()` — throws [ValidationException] on the first invalid result.
 * - `init { validateOrThrow() }` (only when `validateOnConstruct = true` on the spec).
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
    var bundleRegistry: BundleRegistry = BundleRegistry.EMPTY

    fun generate(spec: KSClassDeclaration, classSpecAnn: KSAnnotation) {
        val domainClass = (classSpecAnn.arguments.first { it.name?.asString() == "for_" }.value as KSType)
            .declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val suffix  = classSpecAnn.argString("suffix")
        val prefix  = classSpecAnn.argString("prefix")
        val partial = classSpecAnn.argBool("partial")
        val validateOnConstruct = classSpecAnn.argBool("validateOnConstruct")
        val requestName = "$prefix$domainName$suffix"

        val fields   = classResolver.resolve(domainClass) ?: return
        val bundleNames = classSpecAnn.argStringList("bundles")
        val mergeStrategy = classSpecAnn.argEnumName("bundleMergeStrategy")
        val overrides = spec.resolveWithBundles(suffix, bundleNames, mergeStrategy, bundleRegistry, logger)

        val imports = mutableListOf<Pair<String, String>>()
        val addImport: (String, String) -> Unit = { pkg, name -> imports.add(pkg to name) }

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(requestName).addModifiers(KModifier.DATA)
        classSpecAnn.customAnnotationSpecs(addImport).forEach { classBuilder.addAnnotation(it) }

        // Per-field rule statements collected for validate()
        val fieldRulesList = mutableListOf<FieldRules>()

        for (field in fields) {
            val override = overrides[field.originalName]
            if (override?.exclude == true) continue

            val requireStmts = override?.let { buildEnsureStatements(field.originalName, it) }
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
                fieldRulesList += FieldRules(field.originalName, isNullable = true, requireStmts)
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
                fieldRulesList += FieldRules(field.originalName, isNullable = false, requireStmts)
            }
        }

        classBuilder.primaryConstructor(ctorBuilder.build())

        val hasValidation = fieldRulesList.any { it.stmts.isNotEmpty() }
        if (hasValidation) {
            classBuilder.addFunction(buildValidateFun(fieldRulesList))
            classBuilder.addFunction(buildValidateOrThrowFun())
            if (validateOnConstruct) {
                classBuilder.addInitializerBlock(
                    CodeBlock.builder().addStatement("validateOrThrow()").build()
                )
            }

            // Ensure runtime imports are present
            imports += "com.example.runtime" to "ValidationContext"
            imports += "com.example.runtime" to "ValidationResult"
            imports += "com.example.runtime" to "FieldRef"
            imports += "com.example.runtime" to "ValidationException"
        }

        val fileBuilder = FileSpec.builder("", requestName)
        imports.forEach { (pkg, name) -> fileBuilder.addImport(pkg, name) }
        fileBuilder.addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("RequestGenerator: generated $requestName (partial=$partial, validateOnConstruct=$validateOnConstruct, ${if (hasValidation) "with" else "no"} validation)")
    }

    // ---------------------------------------------------------------------------
    // validate() and validateOrThrow() FunSpec builders
    // ---------------------------------------------------------------------------

    private data class FieldRules(val fieldName: String, val isNullable: Boolean, val stmts: List<String>)

    private fun buildValidateFun(fieldRulesList: List<FieldRules>): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("val ctx = %T()", VALIDATION_CONTEXT)
        for ((fieldName, isNullable, stmts) in fieldRulesList) {
            if (stmts.isEmpty()) continue
            if (isNullable) {
                body.beginControlFlow("if (%N != null)", fieldName)
                stmts.forEach { body.addStatement(it) }
                body.endControlFlow()
            } else {
                stmts.forEach { body.addStatement(it) }
            }
        }
        body.addStatement("return ctx.build()")
        return FunSpec.builder("validate")
            .returns(VALIDATION_RESULT)
            .addCode(body.build())
            .build()
    }

    private fun buildValidateOrThrowFun(): FunSpec =
        FunSpec.builder("validateOrThrow")
            .addCode(
                CodeBlock.builder()
                    .addStatement("val result = validate()")
                    .beginControlFlow("if (result is %T.Invalid)", VALIDATION_RESULT)
                    .addStatement("throw %T(result.errors)", VALIDATION_EXCEPTION)
                    .endControlFlow()
                    .build()
            )
            .build()

    // ---------------------------------------------------------------------------
    // Rule → ensure() statement generation via @RuleExpression
    // ---------------------------------------------------------------------------

    private fun buildEnsureStatements(fieldName: String, override: MergedOverride): List<String> {
        val statements = mutableListOf<String>()
        for (ruleType in override.rules) {
            val ruleDecl = ruleType.declaration as? KSClassDeclaration ?: continue
            val ruleName = ruleDecl.simpleName.asString()

            val exprTemplate = ruleDecl.annotations
                .firstOrNull { it.shortName.asString() == "RuleExpression" }
                ?.argString("expression")

            if (exprTemplate == null) {
                logger.warn("RequestGenerator: rule '$ruleName' has no @RuleExpression — skipped")
                continue
            }

            var expr = exprTemplate.replace("{field}", fieldName)

            val unresolved = Regex("\\{\\w+\\}").find(expr)
            if (unresolved != null) {
                logger.warn(
                    "RequestGenerator: rule '$ruleName' on '$fieldName' contains unresolved " +
                    "placeholder '${unresolved.value}'. Rules with parameters must be wrapped " +
                    "in a concrete annotation class annotated with @RuleExpression. Skipped."
                )
                continue
            }

            statements += """ctx.ensure($expr, FieldRef("$fieldName"), "$fieldName failed rule $ruleName")"""
        }
        return statements
    }
}

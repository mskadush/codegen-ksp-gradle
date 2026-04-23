import com.example.runtime.FieldRef
import com.example.runtime.ValidationContext
import com.example.runtime.ValidationException
import com.example.runtime.ValidationResult
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.example.annotations.NullableOverride
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.writeTo

private val VALIDATION_CONTEXT   = ValidationContext::class.asClassName()
private val VALIDATION_RESULT    = ValidationResult::class.asClassName()
private val FIELD_REF            = FieldRef::class.asClassName()
private val VALIDATION_EXCEPTION = ValidationException::class.asClassName()

/**
 * Generates a `data class` from any [ClassSpec] instance.
 *
 * All output kinds — persistence entities, DTOs, request objects — are handled by this single
 * generator. Output shape is driven entirely by the spec:
 *
 * - `partial = true`            → every field is nullable with `= null` (update-request style).
 * - Any field has [FieldSpec.rules] → a `validate()` + `validateOrThrow()` function pair is
 *   emitted on the class. `validateOnConstruct = true` additionally emits `init { validateOrThrow() }`.
 * - [FieldSpec.rename]          → field name in the generated class differs from the domain name.
 * - Nested domain types that have their own spec for the same suffix are replaced with their
 *   generated equivalents (e.g. `Address` → `AddressEntity` when suffix = `"Entity"`).
 */
class ClassGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
    var bundleRegistry: BundleRegistry = BundleRegistry.EMPTY

    fun generate(spec: KSClassDeclaration, model: ClassSpecModel) {
        val outputName = model.outputName
        val overrides  = spec.resolveWithBundles(
            model.suffix, model.bundleNames, model.mergeStrategy, bundleRegistry,
        )
        val fields     = classResolver.resolveWithKinds(
            model.domainClass, model.unmappedStrategy, model.suffix,
        ) ?: return

        val imports    = mutableListOf<Pair<String, String>>()
        val addImport: (String, String) -> Unit = { pkg, name -> imports.add(pkg to name) }

        val ctorBuilder  = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(outputName).addModifiers(KModifier.DATA)
        model.classAnnotations.mapNotNull { it.toAnnotationSpec(addImport) }
            .forEach { classBuilder.addAnnotation(it) }

        val fieldRulesList = mutableListOf<FieldRules>()

        for (field in fields) {
            val override  = overrides[field.originalName]
            if (override?.exclude == true) continue

            val baseType = when (val kind = field.fieldKind) {
                is FieldKind.MappedObject     -> kind.targetClassName
                is FieldKind.MappedCollection ->
                    ClassName.bestGuess(kind.collectionFQN).parameterizedBy(kind.targetClassName)
                else -> field.resolvedType
            }

            val fieldName  = override?.rename?.takeIf { it.isNotBlank() } ?: field.originalName
            val rules      = override?.rules ?: emptyList()
            val ensureStmts = buildEnsureStatements(field.originalName, rules)

            if (model.partial) {
                val nullableType = baseType.copy(nullable = true)
                ctorBuilder.addParameter(
                    ParameterSpec.builder(fieldName, nullableType).defaultValue("null").build()
                )
                val propSpec = PropertySpec.builder(fieldName, nullableType)
                    .initializer(fieldName)
                    .apply { override?.allAnnotations?.forEach { raw -> raw.toAnnotationSpec(addImport)?.let { addAnnotation(it) } } }
                    .build()
                classBuilder.addProperty(propSpec)
                fieldRulesList += FieldRules(fieldName, isNullable = true, ensureStmts)
            } else {
                val finalType = when (override?.nullable ?: NullableOverride.UNSET) {
                    NullableOverride.YES  -> baseType.copy(nullable = true)
                    NullableOverride.NO   -> baseType.copy(nullable = false)
                    NullableOverride.UNSET -> baseType
                }
                ctorBuilder.addParameter(fieldName, finalType)
                val propSpec = PropertySpec.builder(fieldName, finalType)
                    .initializer(fieldName)
                    .apply { override?.allAnnotations?.forEach { raw -> raw.toAnnotationSpec(addImport)?.let { addAnnotation(it) } } }
                    .build()
                classBuilder.addProperty(propSpec)
                fieldRulesList += FieldRules(fieldName, isNullable = false, ensureStmts)
            }
        }

        classBuilder.primaryConstructor(ctorBuilder.build())

        val hasValidation = fieldRulesList.any { it.stmts.isNotEmpty() }
        if (hasValidation) {
            classBuilder.addFunction(buildValidateFun(fieldRulesList))
            classBuilder.addFunction(buildValidateOrThrowFun())
            if (model.validateOnConstruct) {
                classBuilder.addInitializerBlock(
                    CodeBlock.builder().addStatement("validateOrThrow()").build()
                )
            }
            imports += VALIDATION_CONTEXT.packageName   to VALIDATION_CONTEXT.simpleName
            imports += VALIDATION_RESULT.packageName    to VALIDATION_RESULT.simpleName
            imports += FIELD_REF.packageName            to FIELD_REF.simpleName
            imports += VALIDATION_EXCEPTION.packageName to VALIDATION_EXCEPTION.simpleName
        }

        val fileBuilder = FileSpec.builder("", outputName)
        imports.forEach { (pkg, name) -> fileBuilder.addImport(pkg, name) }
        fileBuilder.addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("ClassGenerator: generated $outputName (partial=${model.partial}${if (hasValidation) ", with validation" else ""})")
    }

    // ---------------------------------------------------------------------------
    // Validation function builders
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

    private fun buildEnsureStatements(fieldName: String, rules: List<KSType>): List<String> {
        val statements = mutableListOf<String>()
        for (ruleType in rules) {
            val ruleDecl = ruleType.declaration as? KSClassDeclaration ?: continue
            val ruleName = ruleDecl.simpleName.asString()

            val exprTemplate = ruleDecl.annotations
                .firstOrNull { it.shortName.asString() == AN_RULE_EXPRESSION }
                ?.argString(PROP_EXPRESSION)

            if (exprTemplate == null) {
                logger.warn("ClassGenerator: rule '$ruleName' has no @RuleExpression — skipped")
                continue
            }

            var expr = exprTemplate.replace("{field}", fieldName)

            val unresolved = Regex("\\{\\w+\\}").find(expr)
            if (unresolved != null) {
                logger.warn(
                    "ClassGenerator: rule '$ruleName' on '$fieldName' contains unresolved " +
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

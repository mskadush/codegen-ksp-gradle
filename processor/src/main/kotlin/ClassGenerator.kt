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
 * - Any field has [FieldSpec.validators] → a `validate()` + `validateOrThrow()` pair is emitted
 *   on the class. `validateOnConstruct = true` additionally emits `init { validateOrThrow() }`.
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
            model.suffix, model.bundleFQNs, model.mergeStrategy, bundleRegistry,
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

            val fieldName   = override?.rename?.takeIf { it.isNotBlank() } ?: field.originalName
            val validators  = override?.validators ?: emptyList()
            val ensureStmts = buildValidatorCalls(field.originalName, validators, addImport)

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

        // ---- add fields (not derived from domain) ----
        for (extraAnn in spec.addFieldAnnotations()) {
            val forSuffixes = extraAnn.argStringList(PROP_FOR)
            if (model.suffix !in forSuffixes) continue

            val fieldName    = extraAnn.argString(PROP_ADD_NAME)
            val isNullable   = extraAnn.argBool(PROP_ADD_NULLABLE)
            val defaultExpr  = extraAnn.argString(PROP_ADD_DEFAULT)

            val typeFQN = (extraAnn.arguments.firstOrNull { it.name?.asString() == PROP_ADD_TYPE }
                ?.value as? KSType)?.declaration?.qualifiedName?.asString() ?: continue
            val dotIdx  = typeFQN.lastIndexOf('.')
            val baseType = if (dotIdx >= 0) {
                ClassName(typeFQN.substring(0, dotIdx), typeFQN.substring(dotIdx + 1))
            } else {
                ClassName("", typeFQN)
            }

            val effectiveNullable = isNullable || model.partial
            val finalType = baseType.copy(nullable = effectiveNullable)

            val paramBuilder = ParameterSpec.builder(fieldName, finalType)
            when {
                model.partial        -> paramBuilder.defaultValue("null")
                effectiveNullable && defaultExpr.isBlank() -> paramBuilder.defaultValue("null")
                defaultExpr.isNotBlank() -> paramBuilder.defaultValue(defaultExpr)
            }
            ctorBuilder.addParameter(paramBuilder.build())

            val propSpec = PropertySpec.builder(fieldName, finalType)
                .initializer(fieldName)
                .apply {
                    extraAnn.argAnnotationList().forEach { raw ->
                        raw.toAnnotationSpec(addImport)?.let { addAnnotation(it) }
                    }
                }
                .build()
            classBuilder.addProperty(propSpec)
            fieldRulesList += FieldRules(fieldName, isNullable = effectiveNullable, emptyList())
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
    // Validator → ensure() statement generation via FieldValidator KClass refs
    // ---------------------------------------------------------------------------

    private fun buildValidatorCalls(
        fieldName: String,
        validators: List<KSType>,
        addImport: (String, String) -> Unit,
    ): List<String> {
        val statements = mutableListOf<String>()
        for (validatorType in validators) {
            val decl = validatorType.declaration as? KSClassDeclaration ?: continue
            if (decl.qualifiedName == null) {
                logger.warn("ClassGenerator: validator on '$fieldName' has no qualified name — skipped")
                continue
            }
            val pkg  = decl.packageName.asString()
            val name = decl.simpleName.asString()
            addImport(pkg, name)
            statements += """$name.let { v -> ctx.ensure(v.validate($fieldName), FieldRef("$fieldName"), v.message) }"""
        }
        return statements
    }
}

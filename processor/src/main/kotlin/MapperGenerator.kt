import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import za.skadush.codegen.gradle.annotations.NullableOverride
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates bidirectional mapper extension functions for every non-partial [ClassSpec] output.
 *
 * Emits a `<OutputName>Mappers.kt` file containing:
 * - `fun <DomainClass>.to<Suffix>(): <OutputName>` — maps domain → output
 * - `fun <OutputName>.toDomain(): <DomainClass>` — maps output → domain
 *
 * **Nested types**: when a domain field's type has a registered output for the same suffix,
 * the mapper emits `.to<Suffix>()` / `.toDomain()` calls for those fields.
 * Collection fields are mapped via `.map { }`.
 *
 * **Transformers**: fields with a `transformer` override apply the transformer
 * in the forward and reverse directions respectively.
 */
class MapperGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
    var bundleRegistry: BundleRegistry = BundleRegistry.EMPTY

    private companion object {
        val NO_OP_TRANSFORMER = FQN_NO_OP_TRANSFORMER
    }

    fun generate(
        spec: KSClassDeclaration,
        model: ClassSpecModel,
    ) {
        val outputName   = model.outputName
        val overrides    = spec.resolveWithBundles(
            model.suffix, model.bundleFQNs, model.mergeStrategy, bundleRegistry,
        )
        val specRegistry = classResolver.registry

        val domainClassName  = ClassName(model.domainPackage, model.domainName)
        val outputClassName  = ClassName(model.resolvedOutputPackage, outputName)
        // Mapper method name derived from suffix: "Entity" → "toEntity()", "Response" → "toResponse()"
        val toOutputFunName  = "to${model.suffix}"

        // ---- forward mapper: domain → output ----
        val expandedFields = classResolver.resolveWithKinds(
            model.domainClass, model.unmappedStrategy, model.suffix,
        ) ?: return
        val excludeSet = model.excludeNames.toSet()
        val includedFields = expandedFields.filter {
            overrides[it.originalName]?.exclude != true && it.originalName !in excludeSet
        }

        val toOutputArgs = includedFields.joinToString(", ") { field ->
            val src = field.sourceExpression ?: "this.${field.originalName}"
            // Parameter name in the output constructor may differ due to rename
            val override = overrides[field.originalName]
            val paramName = override?.rename?.takeIf { it.isNotBlank() } ?: field.originalName
            when (field.fieldKind) {
                is FieldKind.MappedObject -> {
                    val nestedDomain = field.originalType.declaration.simpleName.asString()
                    val targetSuffix = field.fieldKind.targetName.removePrefix(nestedDomain)
                    "$paramName = $src.to$targetSuffix()"
                }
                is FieldKind.MappedCollection -> {
                    val nestedDomain = extractCollectionElement(field.originalType)
                        ?.declaration?.simpleName?.asString() ?: field.originalName
                    val targetSuffix = field.fieldKind.targetName.removePrefix(nestedDomain)
                    "$paramName = $src.map { it.to$targetSuffix() }"
                }
                else -> {
                    if (field.sourceExpression != null) {
                        "$paramName = ${field.sourceExpression}"
                    } else {
                        val expr = forwardExpr(field.originalName, overrides[field.originalName])
                        "$paramName = $expr"
                    }
                }
            }
        }
        val toOutputFun = FunSpec.builder(toOutputFunName)
            .receiver(domainClassName)
            .returns(outputClassName)
            .addStatement("return %T($toOutputArgs)", outputClassName)
            .build()

        // ---- reverse mapper: output → domain ----
        val originalFields = classResolver.resolve(model.domainClass) ?: return

        val toDomainArgs = originalFields.joinToString(", ") { field ->
            val override = overrides[field.originalName]
            val rename   = override?.rename?.takeIf { it.isNotBlank() }
            val srcName  = rename ?: field.originalName
            when {
                override?.exclude == true || field.originalName in excludeSet ->
                    "${field.originalName} = ${defaultValueLiteral(field.resolvedType)}"

                isMappedObject(field.originalType, specRegistry, model.suffix) ->
                    "${field.originalName} = this.$srcName.toDomain()"

                isMappedCollection(field.originalType, specRegistry, model.suffix) ->
                    "${field.originalName} = this.$srcName.map { it.toDomain() }"

                else -> {
                    val baseExpr = reverseExpr(srcName, override)
                    val needsBang = override?.nullable == NullableOverride.YES && !field.resolvedType.isNullable
                    "${field.originalName} = $baseExpr${if (needsBang) "!!" else ""}"
                }
            }
        }
        val toDomainFun = FunSpec.builder("toDomain")
            .receiver(outputClassName)
            .returns(domainClassName)
            .addStatement("return %T($toDomainArgs)", domainClassName)
            .build()

        val fileName = "${outputName}Mappers"
        FileSpec.builder(model.resolvedOutputPackage, fileName)
            .addFunction(toOutputFun)
            .addFunction(toDomainFun)
            .build()
            .writeTo(codeGenerator, aggregating = false, originatingKSFiles = listOfNotNull(spec.containingFile))

        logger.info("MapperGenerator: generated $fileName.kt with $toOutputFunName() and toDomain()")
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun isMappedObject(type: KSType, registry: SpecRegistry, suffix: String): Boolean {
        val fqn = type.declaration.qualifiedName?.asString() ?: return false
        if (fqn.startsWith("kotlin.") || fqn.startsWith("java.")) return false
        return registry.lookupNested(fqn, suffix) != null
    }

    private fun isMappedCollection(type: KSType, registry: SpecRegistry, suffix: String): Boolean {
        val elemFqn = extractCollectionElement(type)?.declaration?.qualifiedName?.asString() ?: return false
        return registry.lookupNested(elemFqn, suffix) != null
    }

    private fun extractCollectionElement(type: KSType): KSType? {
        val fqn = type.declaration.qualifiedName?.asString() ?: return null
        if (fqn != "kotlin.collections.List" && fqn != "kotlin.collections.Set") return null
        return type.arguments.firstOrNull()?.type?.resolve()
    }

    private fun forwardExpr(fieldName: String, override: MergedOverride?): String {
        val fqn = override?.transformerFQN
        return if (fqn != null && fqn != NO_OP_TRANSFORMER) {
            "$fqn().toTarget(this.$fieldName)"
        } else {
            "this.$fieldName"
        }
    }

    private fun reverseExpr(sourceName: String, override: MergedOverride?): String {
        val fqn = override?.transformerFQN
        return if (fqn != null && fqn != NO_OP_TRANSFORMER) {
            "$fqn().toDomain(this.$sourceName)"
        } else {
            "this.$sourceName"
        }
    }

    private fun defaultValueLiteral(type: TypeName): String {
        if (type.isNullable) return "null"
        return when (type.toString()) {
            "kotlin.String"  -> "\"\""
            "kotlin.Long"    -> "0L"
            "kotlin.Int"     -> "0"
            "kotlin.Boolean" -> "false"
            "kotlin.Double"  -> "0.0"
            "kotlin.Float"   -> "0.0f"
            else -> "TODO(\"no default for excluded field of type $type\")"
        }
    }
}

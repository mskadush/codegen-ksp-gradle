import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates bidirectional mapper extension functions for an entity/domain pair.
 *
 * For each `@EntitySpec`-annotated spec, emits a `<DomainClass>Mappers.kt` file containing:
 * - `fun <DomainClass>.toEntity(): <DomainClass>Entity` — maps domain → entity
 * - `fun <DomainClass>Entity.toDomain(): <DomainClass>` — maps entity → domain
 *
 * **Nested types**: when a domain field's type itself has a spec, the generated mapper emits
 * `.toEntity()` / `.toDomain()` calls for those fields rather than a direct assignment.
 * Collection fields (`List<T>`, `Set<T>`) are mapped via `.map { it.toEntity() }`.
 *
 * **Transformers**: fields with `transformer = XClass::class` emit `XClass().toTarget(this.field)`
 * (forward) and `XClass().toDomain(this.field)` (reverse). `transformerRef = "name"` resolves
 * the transformer from [transformerRegistry]; unknown refs log `logger.error`.
 *
 * **INLINE strategy**: for `toEntity()` the inlined field's `sourceExpression` is used directly.
 * Automatic `toDomain()` for INLINE-expanded fields is not supported — those fields are
 * reconstructed from the original domain class structure.
 */
class MapperGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {

    private companion object {
        const val NO_OP_TRANSFORMER = "com.example.annotations.NoOpTransformer"
    }

    fun generate(spec: KSClassDeclaration, transformerRegistry: Map<String, String> = emptyMap()) {
        val annotation = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val domainPackage = domainClass.packageName.asString()
        val entityName = "${domainName}Entity"
        val specName = spec.simpleName.asString()

        val unmappedStrategy = annotation.argEnumName("unmappedNestedStrategy").let {
            if (it == "UNSET") "FAIL" else it
        }

        val overrideMap: Map<String, KSAnnotation> = spec.annotations
            .filter { it.shortName.asString() == "EntityField" }
            .associateBy { it.argString("property") }

        val domainClassName = ClassName(domainPackage, domainName)
        val entityClassName = ClassName("", entityName)
        val specRegistry = classResolver.registry

        // ---- toEntity(): use resolveWithKinds so nested/INLINE fields are handled ----
        val expandedFields = classResolver.resolveWithKinds(domainClass, unmappedStrategy, overrideMap) ?: return
        val includedFields = expandedFields.filter { overrideMap[it.originalName]?.argBool("exclude") != true }

        val toEntityArgs = includedFields.joinToString(", ") { field ->
            val src = field.sourceExpression ?: "this.${field.originalName}"
            when (field.fieldKind) {
                is FieldKind.MappedObject -> "${field.originalName} = $src.toEntity()"
                is FieldKind.MappedCollection -> "${field.originalName} = $src.map { it.toEntity() }"
                else -> {
                    // Primitive — if INLINE (has sourceExpression), use it directly; otherwise apply transformer logic
                    if (field.sourceExpression != null) {
                        "${field.originalName} = ${field.sourceExpression}"
                    } else {
                        val expr = forwardTransformedExpr(field.originalName, overrideMap[field.originalName], specName, transformerRegistry)
                        "${field.originalName} = $expr"
                    }
                }
            }
        }
        val toEntity = FunSpec.builder("toEntity")
            .receiver(domainClassName)
            .returns(entityClassName)
            .addStatement("return %T($toEntityArgs)", entityClassName)
            .build()

        // ---- toDomain(): iterate original domain fields; check SpecRegistry for nested types ----
        // We use resolve() (not resolveWithKinds) so that INLINE-expanded field names don't
        // contaminate the domain constructor argument list.
        val originalFields = classResolver.resolve(domainClass) ?: return

        val toDomainArgs = originalFields.joinToString(", ") { field ->
            val override = overrideMap[field.originalName]
            when {
                override?.argBool("exclude") == true ->
                    "${field.originalName} = ${defaultValueLiteral(field.resolvedType)}"

                isMappedObject(field.originalType, specRegistry.entityTargets) ->
                    "${field.originalName} = this.${field.originalName}.toDomain()"

                isMappedCollection(field.originalType, specRegistry.entityTargets) ->
                    "${field.originalName} = this.${field.originalName}.map { it.toDomain() }"

                else -> {
                    val baseExpr = reverseTransformedExpr(field.originalName, override, specName, transformerRegistry)
                    val needsBang = override?.argEnumName("nullable") == "YES" && !field.resolvedType.isNullable
                    "${field.originalName} = $baseExpr${if (needsBang) "!!" else ""}"
                }
            }
        }
        val toDomain = FunSpec.builder("toDomain")
            .receiver(entityClassName)
            .returns(domainClassName)
            .addStatement("return %T($toDomainArgs)", domainClassName)
            .build()

        val fileName = "${domainName}Mappers"
        FileSpec.builder(domainPackage, fileName)
            .addFunction(toEntity)
            .addFunction(toDomain)
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("MapperGenerator: generated $fileName.kt with toEntity() and toDomain()")
    }

    fun generateDtoMappers(spec: KSClassDeclaration, transformerRegistry: Map<String, String> = emptyMap()) {
        val annotation = spec.annotations.first { it.shortName.asString() == "DtoSpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val domainPackage = domainClass.packageName.asString()
        val specName = spec.simpleName.asString()

        val suffix = annotation.arguments.firstOrNull { it.name?.asString() == "suffix" }?.value as? String ?: "Dto"
        val prefix = annotation.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value as? String ?: ""
        val dtoName = "$prefix${domainName}$suffix"

        val excludedFieldStrategy = annotation.argEnumName("excludedFieldStrategy")

        val fields = classResolver.resolve(domainClass) ?: return

        val overrideMap: Map<String, KSAnnotation> = spec.annotations
            .filter { it.shortName.asString() == "DtoField" }
            .associateBy { it.argString("property") }

        val domainClassName = ClassName(domainPackage, domainName)
        val dtoClassName = ClassName("", dtoName)
        val specRegistry = classResolver.registry

        // Fields included in the DTO (not excluded), with their renamed names
        data class DtoFieldInfo(val originalName: String, val dtoName: String, val resolvedType: TypeName)
        val includedFields = fields.mapNotNull { field ->
            val override = overrideMap[field.originalName]
            if (override?.argBool("exclude") == true) return@mapNotNull null
            val rename = override?.argString("rename") ?: ""
            val dtoFieldName = if (rename.isNotBlank()) rename else field.originalName
            DtoFieldInfo(field.originalName, dtoFieldName, field.resolvedType)
        }

        // fun DomainClass.toDto(): DtoClass
        val toDtoArgs = includedFields.joinToString(", ") { info ->
            when {
                isMappedObject(fields.first { it.originalName == info.originalName }.originalType, specRegistry.dtoTargets) ->
                    "${info.dtoName} = this.${info.originalName}.toDto()"
                isMappedCollection(fields.first { it.originalName == info.originalName }.originalType, specRegistry.dtoTargets) ->
                    "${info.dtoName} = this.${info.originalName}.map { it.toDto() }"
                else -> {
                    val expr = forwardTransformedExpr(info.originalName, overrideMap[info.originalName], specName, transformerRegistry)
                    "${info.dtoName} = $expr"
                }
            }
        }
        val toDto = FunSpec.builder("toDto")
            .receiver(domainClassName)
            .returns(dtoClassName)
            .addStatement("return %T($toDtoArgs)", dtoClassName)
            .build()

        // fun DtoClass.toDomain(): DomainClass
        val toDomainArgs = fields.joinToString(", ") { field ->
            val override = overrideMap[field.originalName]
            when {
                override?.argBool("exclude") == true -> when (excludedFieldStrategy) {
                    "REQUIRE_MANUAL" -> "${field.originalName} = TODO(\"manual mapping required for ${field.originalName}\")"
                    else -> "${field.originalName} = ${defaultValueLiteral(field.resolvedType)}"
                }
                isMappedObject(field.originalType, specRegistry.dtoTargets) ->
                    "${field.originalName} = this.${overrideMap[field.originalName]?.argString("rename")?.takeIf { it.isNotBlank() } ?: field.originalName}.toDomain()"
                isMappedCollection(field.originalType, specRegistry.dtoTargets) ->
                    "${field.originalName} = this.${overrideMap[field.originalName]?.argString("rename")?.takeIf { it.isNotBlank() } ?: field.originalName}.map { it.toDomain() }"
                else -> {
                    val rename = override?.argString("rename") ?: ""
                    val dtoFieldName = if (rename.isNotBlank()) rename else field.originalName
                    val baseExpr = reverseTransformedExprFrom(dtoFieldName, field.originalName, override, specName, transformerRegistry)
                    val needsBang = override?.argEnumName("nullable") == "YES" && !field.resolvedType.isNullable
                    "${field.originalName} = $baseExpr${if (needsBang) "!!" else ""}"
                }
            }
        }
        val toDomain = FunSpec.builder("toDomain")
            .receiver(dtoClassName)
            .returns(domainClassName)
            .addStatement("return %T($toDomainArgs)", domainClassName)
            .build()

        val fileName = "${dtoName}Mappers"
        FileSpec.builder(domainPackage, fileName)
            .addFunction(toDto)
            .addFunction(toDomain)
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("MapperGenerator: generated $fileName.kt with toDto() and toDomain()")
    }

    // --- Nested type helpers ---

    /** Returns true when [type] is a domain class that has an entry in [targetMap]. */
    private fun isMappedObject(type: KSType, targetMap: Map<String, String>): Boolean {
        val fqn = type.declaration.qualifiedName?.asString() ?: return false
        if (fqn.startsWith("kotlin.") || fqn.startsWith("java.")) return false
        return targetMap.containsKey(fqn)
    }

    /** Returns true when [type] is a `List<T>` / `Set<T>` whose element has an entry in [targetMap]. */
    private fun isMappedCollection(type: KSType, targetMap: Map<String, String>): Boolean {
        val elemFqn = extractCollectionElement(type)?.declaration?.qualifiedName?.asString() ?: return false
        return targetMap.containsKey(elemFqn)
    }

    private fun extractCollectionElement(type: KSType): KSType? {
        val fqn = type.declaration.qualifiedName?.asString() ?: return null
        if (fqn != "kotlin.collections.List" && fqn != "kotlin.collections.Set") return null
        return type.arguments.firstOrNull()?.type?.resolve()
    }

    // --- Transformer helpers ---

    /** Returns the forward (domain → target) value expression, applying transformer if configured. */
    private fun forwardTransformedExpr(
        fieldName: String,
        override: KSAnnotation?,
        specName: String,
        transformerRegistry: Map<String, String>,
    ): String {
        val ref = override?.argString("transformerRef") ?: ""
        val transformerFQN = override?.argKClassFQN("transformer")

        return when {
            ref.isNotBlank() -> {
                val registryRef = transformerRegistry[ref]
                if (registryRef == null) {
                    logger.error("Unknown transformer '$ref' on $specName.$fieldName")
                    "this.$fieldName"
                } else {
                    "$registryRef.toTarget(this.$fieldName)"
                }
            }
            transformerFQN != null && transformerFQN != NO_OP_TRANSFORMER ->
                "$transformerFQN().toTarget(this.$fieldName)"
            else -> "this.$fieldName"
        }
    }

    /** Returns the reverse (target → domain) value expression for an entity field. */
    private fun reverseTransformedExpr(
        fieldName: String,
        override: KSAnnotation?,
        specName: String,
        transformerRegistry: Map<String, String>,
    ): String = reverseTransformedExprFrom(fieldName, fieldName, override, specName, transformerRegistry)

    /** Reverse expression when the receiver field name ([sourceName]) differs from the domain field name. */
    private fun reverseTransformedExprFrom(
        sourceName: String,
        originalName: String,
        override: KSAnnotation?,
        specName: String,
        transformerRegistry: Map<String, String>,
    ): String {
        val ref = override?.argString("transformerRef") ?: ""
        val transformerFQN = override?.argKClassFQN("transformer")

        return when {
            ref.isNotBlank() -> {
                val registryRef = transformerRegistry[ref]
                if (registryRef == null) {
                    logger.error("Unknown transformer '$ref' on $specName.$originalName")
                    "this.$sourceName"
                } else {
                    "$registryRef.toDomain(this.$sourceName)"
                }
            }
            transformerFQN != null && transformerFQN != NO_OP_TRANSFORMER ->
                "$transformerFQN().toDomain(this.$sourceName)"
            else -> "this.$sourceName"
        }
    }

    private fun defaultValueLiteral(type: TypeName): String {
        if (type.isNullable) return "null"
        return when (type.toString()) {
            "kotlin.String" -> "\"\""
            "kotlin.Long" -> "0L"
            "kotlin.Int" -> "0"
            "kotlin.Boolean" -> "false"
            "kotlin.Double" -> "0.0"
            "kotlin.Float" -> "0.0f"
            else -> "TODO(\"no default for excluded field of type $type\")"
        }
    }
}

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

private fun KSAnnotation.argKClassFQN(name: String): String? =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? KSType)
        ?.declaration?.qualifiedName?.asString()

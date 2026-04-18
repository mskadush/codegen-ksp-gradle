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
 * Excluded fields are omitted from `toEntity()` and receive a zero/null default in `toDomain()`.
 * Fields made nullable via `NullableOverride.YES` use `!!` when mapping back to a non-nullable
 * domain field.
 */
class MapperGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {

    fun generate(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val domainPackage = domainClass.packageName.asString()
        val entityName = "${domainName}Entity"

        val fields = classResolver.resolve(domainClass) ?: return

        val overrideMap: Map<String, KSAnnotation> = spec.annotations
            .filter { it.shortName.asString() == "EntityField" }
            .associateBy { it.argString("property") }

        val domainClassName = ClassName(domainPackage, domainName)
        val entityClassName = ClassName("", entityName)

        // Only fields that are not excluded (these exist in the entity constructor)
        val includedFields = fields.filter { overrideMap[it.originalName]?.argBool("exclude") != true }

        // fun DomainClass.toEntity(): DomainClassEntity
        val toEntityArgs = includedFields.joinToString(", ") { "${it.originalName} = this.${it.originalName}" }
        val toEntity = FunSpec.builder("toEntity")
            .receiver(domainClassName)
            .returns(entityClassName)
            .addStatement("return %T($toEntityArgs)", entityClassName)
            .build()

        // fun DomainClassEntity.toDomain(): DomainClass
        // - excluded fields → zero/null default
        // - NullableOverride.YES fields → !! if domain type is non-nullable
        val toDomainArgs = fields.joinToString(", ") { field ->
            val override = overrideMap[field.originalName]
            when {
                override?.argBool("exclude") == true ->
                    "${field.originalName} = ${defaultValueLiteral(field.resolvedType)}"
                override?.argEnumName("nullable") == "YES" && !field.resolvedType.isNullable ->
                    "${field.originalName} = this.${field.originalName}!!"
                else ->
                    "${field.originalName} = this.${field.originalName}"
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

    fun generateDtoMappers(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "DtoSpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val domainPackage = domainClass.packageName.asString()

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
        val toDtoArgs = includedFields.joinToString(", ") { "${it.dtoName} = this.${it.originalName}" }
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
                else -> {
                    val rename = override?.argString("rename") ?: ""
                    val dtoFieldName = if (rename.isNotBlank()) rename else field.originalName
                    val nullable = override?.argEnumName("nullable") ?: "UNSET"
                    if (nullable == "YES" && !field.resolvedType.isNullable) {
                        "${field.originalName} = this.$dtoFieldName!!"
                    } else {
                        "${field.originalName} = this.$dtoFieldName"
                    }
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

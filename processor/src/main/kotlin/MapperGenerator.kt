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

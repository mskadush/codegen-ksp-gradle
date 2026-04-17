import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a `data class` entity from a spec class annotated with `@EntitySpec`.
 *
 * The generated class is named `<DomainClass>Entity` and mirrors the primary constructor of
 * the domain class referenced by `@EntitySpec.for_`. Field-level overrides from `@EntityField`
 * on the spec are applied: excluded fields are omitted, column renames produce `@Column(name)`
 * annotations, and `NullableOverride` adjusts Kotlin nullability. The class-level
 * `@Table(name, schema)` is emitted when `EntitySpec.table` is non-blank.
 *
 * @param codeGenerator KSP code generation API used to write output files.
 * @param logger KSP logger for compile-time diagnostics.
 * @param classResolver Resolves and validates the domain class's fields.
 */
class EntityGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
    /**
     * Generates an entity class for the given [spec] class.
     *
     * @param spec The `@EntitySpec`-annotated class declaration from the compilation round.
     */
    fun generate(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val entityName = "${domainName}Entity"

        val tableArg  = annotation.arguments.firstOrNull { it.name?.asString() == "table"  }?.value as? String ?: ""
        val schemaArg = annotation.arguments.firstOrNull { it.name?.asString() == "schema" }?.value as? String ?: ""

        val fields = classResolver.resolve(domainClass) ?: return

        // Build override map from @EntityField annotations on the spec object.
        // Kotlin @Repeatable: KSP returns each instance separately in spec.annotations.
        val overrideMap: Map<String, KSAnnotation> = spec.annotations
            .filter { it.shortName.asString() == "EntityField" }
            .associateBy { it.argString("property") }

        // @Table on class
        val tableAnnotation = AnnotationSpec.builder(ClassName("jakarta.persistence", "Table")).apply {
            if (tableArg.isNotBlank()) addMember("name = %S", tableArg)
            if (schemaArg.isNotBlank()) addMember("schema = %S", schemaArg)
        }.build()

        val ctorBuilder = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(entityName)
            .addModifiers(KModifier.DATA)
            .addAnnotation(tableAnnotation)

        val columnClassName = ClassName("jakarta.persistence", "Column")
        var emittedCount = 0
        for (field in fields) {
            val override = overrideMap[field.originalName]

            if (override?.argBool("exclude") == true) continue

            val finalType = when (override?.argEnumName("nullable") ?: "UNSET") {
                "YES" -> field.resolvedType.copy(nullable = true)
                "NO"  -> field.resolvedType.copy(nullable = false)
                else  -> field.resolvedType
            }

            ctorBuilder.addParameter(field.originalName, finalType)

            val propBuilder = PropertySpec.builder(field.originalName, finalType)
                .initializer(field.originalName)
            val col = override?.argString("column") ?: ""
            if (col.isNotBlank()) {
                propBuilder.addAnnotation(
                    AnnotationSpec.builder(columnClassName).addMember("name = %S", col).build()
                )
            }
            classBuilder.addProperty(propBuilder.build())
            emittedCount++
        }
        classBuilder.primaryConstructor(ctorBuilder.build())

        FileSpec.builder("", entityName)
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("EntityGenerator: generated $entityName with $emittedCount field(s)")
    }
}

// --- KSAnnotation helpers ---

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

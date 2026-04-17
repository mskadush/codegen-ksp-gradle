import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
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
 * the domain class referenced by `@EntitySpec.for_`. The output file is written via KSP's
 * [CodeGenerator] so it appears in the build's generated-sources directory.
 *
 * Invoked by [DomainMappingProcessorProvider] for each `@EntitySpec`-annotated class found
 * during a compilation round.
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
     * Steps:
     * 1. Reads the `@EntitySpec` annotation and extracts the `for_` domain class reference.
     * 2. Delegates to [ClassResolver.resolve] to validate the domain class and obtain its fields.
     * 3. Builds a KotlinPoet `data class` with a matching primary constructor and properties.
     * 4. Writes the file to the KSP-managed generated-sources directory.
     *
     * Returns early (with no output) if [ClassResolver.resolve] reports a validation error.
     *
     * @param spec The `@EntitySpec`-annotated class declaration from the compilation round.
     */
    fun generate(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainClass = (forArg.value as KSType).declaration as KSClassDeclaration
        val domainName = domainClass.simpleName.asString()
        val entityName = "${domainName}Entity"

        val fields = classResolver.resolve(domainClass) ?: return

        val ctor = FunSpec.constructorBuilder().apply {
            fields.forEach { addParameter(it.originalName, it.resolvedType) }
        }.build()

        val classBuilder = TypeSpec.classBuilder(entityName)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(ctor)
        fields.forEach { field ->
            classBuilder.addProperty(
                PropertySpec.builder(field.originalName, field.resolvedType)
                    .initializer(field.originalName)
                    .build()
            )
        }

        FileSpec.builder("", entityName)
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)

        logger.info("EntityGenerator: generated $entityName with ${fields.size} field(s)")
    }
}

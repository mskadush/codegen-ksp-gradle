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

class EntityGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classResolver: ClassResolver,
) {
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

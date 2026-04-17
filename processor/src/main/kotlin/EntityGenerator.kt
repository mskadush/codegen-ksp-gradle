import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class EntityGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainName = (forArg.value as KSType).declaration.simpleName.asString()
        val entityName = "${domainName}Entity"

        val fileSpec = FileSpec.builder("", entityName)
            .addType(TypeSpec.classBuilder(entityName).build())
            .build()

        fileSpec.writeTo(codeGenerator, aggregating = false)
        logger.info("EntityGenerator: generated $entityName")
    }
}

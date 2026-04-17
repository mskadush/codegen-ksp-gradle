import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import kotlin.sequences.forEach

class DomainMappingProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment) = object : SymbolProcessor {

        private val classResolver = ClassResolver(environment.logger)
        private val entityGenerator = EntityGenerator(environment.codeGenerator, environment.logger, classResolver)

        override fun process(resolver: Resolver): List<KSAnnotated> {
            resolver.getSymbolsWithAnnotation("com.example.annotations.EntitySpec")
              .filterIsInstance<KSClassDeclaration>()
              .forEach { entityGenerator.generate(it) }
            return emptyList()
        }
    }
}

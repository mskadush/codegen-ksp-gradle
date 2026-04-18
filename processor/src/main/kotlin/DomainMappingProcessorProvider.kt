import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import kotlin.sequences.forEach

/**
 * KSP entry point for the domain-mapping code generator.
 *
 * Registered via `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
 * so that KSP discovers and instantiates it during compilation. Creates a [SymbolProcessor] that
 * scans for `@EntitySpec`-annotated classes and delegates generation to [EntityGenerator].
 *
 * To add support for new annotation types (e.g. `@DtoSpec`), extend [process] with additional
 * `getSymbolsWithAnnotation` calls and corresponding generator invocations.
 */
class DomainMappingProcessorProvider : SymbolProcessorProvider {

    /**
     * Called once per compilation to create the [SymbolProcessor].
     *
     * Initialises shared infrastructure ([ClassResolver], [EntityGenerator]) from the
     * [environment] and returns an anonymous processor whose [SymbolProcessor.process]
     * method is invoked each compilation round.
     */
    override fun create(environment: SymbolProcessorEnvironment) = object : SymbolProcessor {

        private val classResolver = ClassResolver(environment.logger)
        private val entityGenerator = EntityGenerator(environment.codeGenerator, environment.logger, classResolver)
        private val dtoGenerator = DtoGenerator(environment.codeGenerator, environment.logger, classResolver)
        private val mapperGenerator = MapperGenerator(environment.codeGenerator, environment.logger, classResolver)
        private val requestGenerator = RequestGenerator(environment.codeGenerator, environment.logger, classResolver)

        /**
         * Processes one compilation round.
         *
         * Finds all `@EntitySpec`-annotated [KSClassDeclaration]s and asks [EntityGenerator]
         * and [MapperGenerator] to generate the entity class and mapper functions for each one.
         *
         * @return An empty list — all symbols are handled in the current round with no deferral.
         */
        override fun process(resolver: Resolver): List<KSAnnotated> {
            resolver.getSymbolsWithAnnotation("com.example.annotations.EntitySpec")
              .filterIsInstance<KSClassDeclaration>()
              .forEach { spec ->
                  entityGenerator.generate(spec)
                  mapperGenerator.generate(spec)
              }
            resolver.getSymbolsWithAnnotation("com.example.annotations.DtoSpec")
              .filterIsInstance<KSClassDeclaration>()
              .forEach { spec ->
                  dtoGenerator.generate(spec)
                  mapperGenerator.generateDtoMappers(spec)
              }
            resolver.getSymbolsWithAnnotation("com.example.annotations.RequestSpec")
              .filterIsInstance<KSClassDeclaration>()
              .forEach { spec ->
                  requestGenerator.generate(spec)
              }
            return emptyList()
        }
    }
}

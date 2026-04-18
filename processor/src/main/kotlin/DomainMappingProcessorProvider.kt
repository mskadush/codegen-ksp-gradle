import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import kotlin.sequences.forEach

/**
 * KSP entry point for the domain-mapping code generator.
 *
 * Registered via `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
 * so that KSP discovers and instantiates it during compilation. Creates a [SymbolProcessor] that
 * scans for spec-annotated classes and delegates generation to the appropriate generators.
 *
 * Processing is split into two passes per round:
 * 1. **Pass 1** — collect all `@EntitySpec` and `@DtoSpec` declarations to build a [SpecRegistry],
 *    then run DFS cycle detection on the entity dependency graph.
 * 2. **Pass 2** — inject the registry into [ClassResolver] and run all generators.
 */
class DomainMappingProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment) = object : SymbolProcessor {

        private val logger = environment.logger
        private val classResolver = ClassResolver(logger)
        private val entityGenerator = EntityGenerator(environment.codeGenerator, logger, classResolver)
        private val dtoGenerator = DtoGenerator(environment.codeGenerator, logger, classResolver)
        private val mapperGenerator = MapperGenerator(environment.codeGenerator, logger, classResolver)
        private val requestGenerator = RequestGenerator(environment.codeGenerator, logger, classResolver)
        private val transformerRegistryScanner = TransformerRegistryScanner(logger)

        override fun process(resolver: Resolver): List<KSAnnotated> {
            val transformerRegistry = transformerRegistryScanner.scan(resolver)

            // --- PASS 1: Build SpecRegistry ---
            val entityTargets = mutableMapOf<String, String>()  // domainFQN -> "AddressEntity"
            val dtoTargets    = mutableMapOf<String, String>()  // domainFQN -> "AddressResponse"
            val entityDomainDecls = mutableMapOf<String, KSClassDeclaration>()  // for cycle detection

            resolver.getSymbolsWithAnnotation("com.example.annotations.EntitySpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    val ann = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
                    val domainClass = (ann.arguments.first { it.name?.asString() == "for_" }.value as KSType)
                        .declaration as KSClassDeclaration
                    val fqn = domainClass.qualifiedName?.asString() ?: return@forEach
                    val entityName = "${domainClass.simpleName.asString()}Entity"
                    entityTargets[fqn] = entityName
                    entityDomainDecls[fqn] = domainClass
                }

            resolver.getSymbolsWithAnnotation("com.example.annotations.DtoSpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    val ann = spec.annotations.first { it.shortName.asString() == "DtoSpec" }
                    val domainClass = (ann.arguments.first { it.name?.asString() == "for_" }.value as KSType)
                        .declaration as KSClassDeclaration
                    val fqn = domainClass.qualifiedName?.asString() ?: return@forEach
                    val suffix = ann.arguments.firstOrNull { it.name?.asString() == "suffix" }?.value as? String ?: "Dto"
                    val prefix = ann.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value as? String ?: ""
                    val dtoName = "$prefix${domainClass.simpleName.asString()}$suffix"
                    dtoTargets[fqn] = dtoName
                }

            val specRegistry = SpecRegistry(entityTargets.toMap(), dtoTargets.toMap())

            // --- PASS 1b: Cycle Detection ---
            val graph = mutableMapOf<String, MutableSet<String>>()
            for ((fqn, domainDecl) in entityDomainDecls) {
                val deps = mutableSetOf<String>()
                val ctor = domainDecl.primaryConstructor ?: continue
                for (param in ctor.parameters) {
                    val paramType = param.type.resolve()
                    val paramFqn = paramType.declaration.qualifiedName?.asString()
                    if (paramFqn != null && paramFqn in entityTargets) deps.add(paramFqn)
                    // Also check collection element type
                    val elemType = extractCollectionElement(paramType)
                    val elemFqn = elemType?.declaration?.qualifiedName?.asString()
                    if (elemFqn != null && elemFqn in entityTargets) deps.add(elemFqn)
                }
                graph[fqn] = deps
            }
            val cycle = CycleDetector.findCycle(graph)
            if (cycle != null) {
                val path = cycle.joinToString(" -> ") { it.substringAfterLast('.') }
                logger.error("Circular mapping detected: $path. Use exclude = true to break the cycle.")
                return emptyList()
            }

            // --- PASS 2: Generate ---
            classResolver.registry = specRegistry

            resolver.getSymbolsWithAnnotation("com.example.annotations.EntitySpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    entityGenerator.generate(spec)
                    mapperGenerator.generate(spec, transformerRegistry)
                }
            resolver.getSymbolsWithAnnotation("com.example.annotations.DtoSpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    dtoGenerator.generate(spec)
                    mapperGenerator.generateDtoMappers(spec, transformerRegistry)
                }
            resolver.getSymbolsWithAnnotation("com.example.annotations.RequestSpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    requestGenerator.generate(spec)
                }
            return emptyList()
        }

        private fun extractCollectionElement(type: KSType): KSType? {
            val fqn = type.declaration.qualifiedName?.asString() ?: return null
            if (fqn != "kotlin.collections.List" && fqn != "kotlin.collections.Set") return null
            return type.arguments.firstOrNull()?.type?.resolve()
        }
    }
}

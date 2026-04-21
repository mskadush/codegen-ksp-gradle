import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * KSP entry point for the domain-mapping code generator.
 *
 * Scans for `@ClassSpec`-annotated classes. Each `@ClassSpec` instance on a declaration
 * drives generation of one output class. Output kind is inferred from the spec:
 * - Any [FieldSpec] scoped to the suffix has non-empty `rules` → request class (init {})
 * - `partial = true` → update-request style (all fields nullable)
 * - Otherwise → data class with bidirectional mapper functions
 */
class DomainMappingProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment) = object : SymbolProcessor {

        private val logger = environment.logger
        private val classResolver  = ClassResolver(logger)
        private val entityGenerator  = EntityGenerator(environment.codeGenerator, logger, classResolver)
        private val dtoGenerator     = DtoGenerator(environment.codeGenerator, logger, classResolver)
        private val mapperGenerator  = MapperGenerator(environment.codeGenerator, logger, classResolver)
        private val requestGenerator = RequestGenerator(environment.codeGenerator, logger, classResolver)
        private val transformerRegistryScanner = TransformerRegistryScanner(logger)
        // BundleRegistry is cached across rounds: KSP AA may return 0 @FieldBundle symbols in
        // subsequent rounds (only new-file symbols are visible), so we keep the last non-empty registry.
        private var cachedBundleRegistry: BundleRegistry = BundleRegistry.EMPTY

        override fun process(resolver: Resolver): List<KSAnnotated> {
            val transformerRegistry = transformerRegistryScanner.scan(resolver)

            // --- PASS 1: Build SpecRegistry from @ClassSpec ---
            // Collect all non-request ClassSpec instances for nested-type resolution.
            val entityTargets = mutableMapOf<String, String>()
            val dtoTargets    = mutableMapOf<String, String>()
            // Needed for cycle detection on entity-style outputs
            val entityDomainDecls = mutableMapOf<String, KSClassDeclaration>()

            resolver.getSymbolsWithAnnotation("com.example.annotations.ClassSpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    spec.classSpecAnnotations().forEach { csAnn ->
                        val domainClass = csAnn.domainClass() ?: return@forEach
                        val fqn    = domainClass.qualifiedName?.asString() ?: return@forEach
                        val suffix = csAnn.argString("suffix")
                        val prefix = csAnn.argString("prefix")
                        val partial = csAnn.argBool("partial")
                        val outputName = "$prefix${domainClass.simpleName.asString()}$suffix"

                        val hasRules = spec.hasRulesForSuffix(suffix)
                        val isRequest = hasRules || partial

                        if (!isRequest) {
                            // Heuristic: suffix ending in "Entity" → entityTargets, else dtoTargets
                            if (suffix.endsWith("Entity")) {
                                entityTargets[fqn] = outputName
                                entityDomainDecls[fqn] = domainClass
                            } else {
                                dtoTargets[fqn] = outputName
                            }
                        }
                    }
                }

            val specRegistry = SpecRegistry(entityTargets.toMap(), dtoTargets.toMap())

            // --- PASS 1b: Cycle detection on entity-style outputs ---
            val graph = mutableMapOf<String, MutableSet<String>>()
            for ((fqn, domainDecl) in entityDomainDecls) {
                val deps = mutableSetOf<String>()
                val ctor = domainDecl.primaryConstructor ?: continue
                for (param in ctor.parameters) {
                    val paramType = param.type.resolve()
                    val paramFqn  = paramType.declaration.qualifiedName?.asString()
                    if (paramFqn != null && paramFqn in entityTargets) deps.add(paramFqn)
                    val elemType = extractCollectionElement(paramType)
                    val elemFqn  = elemType?.declaration?.qualifiedName?.asString()
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

            // --- PASS 1c: Build BundleRegistry ---
            // In KSP AA multi-round processing, @FieldBundle symbols are only visible in the
            // round where they originate. Keep the last non-empty registry for subsequent rounds.
            val newBundleRegistry = BundleRegistry.build(resolver, logger)
            if (newBundleRegistry.bundles.isNotEmpty()) {
                cachedBundleRegistry = newBundleRegistry
            }
            entityGenerator.bundleRegistry  = cachedBundleRegistry
            dtoGenerator.bundleRegistry     = cachedBundleRegistry
            requestGenerator.bundleRegistry = cachedBundleRegistry
            mapperGenerator.bundleRegistry  = cachedBundleRegistry

            // --- PASS 1d: Validate property references in spec and bundle annotations ---
            resolver.getSymbolsWithAnnotation("com.example.annotations.ClassSpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec -> spec.validatePropertyRefs(cachedBundleRegistry, logger) }

            // --- PASS 2: Generate ---
            classResolver.registry = specRegistry

            resolver.getSymbolsWithAnnotation("com.example.annotations.ClassSpec")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    spec.classSpecAnnotations().forEach { csAnn ->
                        val domainClass = csAnn.domainClass() ?: return@forEach
                        val suffix  = csAnn.argString("suffix")
                        val partial = csAnn.argBool("partial")
                        val hasRules = spec.hasRulesForSuffix(suffix)
                        val isRequest = hasRules || partial

                        if (isRequest) {
                            requestGenerator.generate(spec, csAnn)
                        } else {
                            // Heuristic: "Entity" suffix → EntityGenerator; others → DtoGenerator
                            if (suffix.endsWith("Entity")) {
                                entityGenerator.generate(spec, csAnn)
                            } else {
                                dtoGenerator.generate(spec, csAnn)
                            }
                            mapperGenerator.generate(spec, csAnn, transformerRegistry)
                        }
                    }
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

// ---------------------------------------------------------------------------
// Local helpers on KSClassDeclaration / KSAnnotation
// ---------------------------------------------------------------------------

/** Returns all @ClassSpec annotation instances on this declaration (handles @Repeatable container). */
@Suppress("UNCHECKED_CAST")
private fun KSClassDeclaration.classSpecAnnotations(): List<KSAnnotation> {
    // KSP wraps @Repeatable annotations in a container; the container's short name is the
    // annotation name + "s" (Kotlin convention). Handle both direct and container cases.
    val direct = annotations.filter { it.shortName.asString() == "ClassSpec" }.toList()
    if (direct.isNotEmpty()) return direct
    // Fallback: check for a container annotation
    val container = annotations.firstOrNull { it.shortName.asString() == "ClassSpecs" }
    val nested = (container?.arguments?.firstOrNull()?.value as? List<*>)
        ?.filterIsInstance<KSAnnotation>() ?: emptyList()
    return nested
}

/** Extracts the domain KSClassDeclaration from a @ClassSpec annotation's `for_` argument. */
private fun KSAnnotation.domainClass(): KSClassDeclaration? =
    (arguments.firstOrNull { it.name?.asString() == "for_" }?.value as? KSType)
        ?.declaration as? KSClassDeclaration

/**
 * Validates that every `property` value in [@ClassField] and [@FieldSpec] annotations on this
 * spec references an actual primary constructor parameter of the domain class, and that every
 * bundle name in [@ClassSpec.bundles] exists in [bundleRegistry].
 */
private fun KSClassDeclaration.validatePropertyRefs(
    bundleRegistry: BundleRegistry,
    logger: KSPLogger,
) {
    val specName = simpleName.asString()

    // Collect domain property names from all @ClassSpec annotations on this spec
    val domainProps = mutableSetOf<String>()
    var primaryDomainName = ""
    classSpecAnnotations().forEach { csAnn ->
        val domainClass = csAnn.domainClass() ?: return@forEach
        if (primaryDomainName.isEmpty()) primaryDomainName = domainClass.simpleName.asString()
        domainClass.primaryConstructor?.parameters
            ?.mapNotNull { it.name?.asString() }
            ?.let { domainProps.addAll(it) }
    }
    if (domainProps.isEmpty()) return

    // Validate @ClassField and @FieldSpec property references on the spec itself
    annotations
        .filter { it.shortName.asString() in setOf("ClassField", "FieldSpec") }
        .forEach { ann ->
            val property = ann.argString("property")
            if (property.isNotBlank() && property !in domainProps) {
                logger.error("Unknown property '$property' on $primaryDomainName in $specName")
            }
        }

    // Validate bundle names and bundle property references
    classSpecAnnotations().forEach { csAnn ->
        val domainClass = csAnn.domainClass() ?: return@forEach
        val domainName = domainClass.simpleName.asString()
        val localDomainProps = domainClass.primaryConstructor?.parameters
            ?.mapNotNull { it.name?.asString() }?.toSet() ?: return@forEach

        csAnn.argStringList("bundles").forEach { bundleName ->
            val bundleDecl = bundleRegistry.bundles[bundleName] ?: run {
                logger.error("Unknown bundle '$bundleName' on $specName")
                return@forEach
            }
            bundleDecl.annotations
                .filter { it.shortName.asString() in setOf("ClassField", "FieldSpec") }
                .forEach { ann ->
                    val property = ann.argString("property")
                    if (property.isNotBlank() && property !in localDomainProps) {
                        logger.error("Unknown property '$property' on $domainName in $specName")
                    }
                }
        }
    }
}

/** Returns true if any @FieldSpec on this declaration targeting [suffix] has non-empty rules. */
@Suppress("UNCHECKED_CAST")
private fun KSClassDeclaration.hasRulesForSuffix(suffix: String): Boolean =
    annotations
        .filter { it.shortName.asString() == "FieldSpec" }
        .filter { ann ->
            val forList = ann.arguments.firstOrNull { it.name?.asString() == "for_" }?.value as? List<*>
            forList?.filterIsInstance<String>()?.contains(suffix) == true
        }
        .any { ann ->
            val rules = ann.arguments.firstOrNull { it.name?.asString() == "rules" }?.value as? List<*>
            rules?.isNotEmpty() == true
        }

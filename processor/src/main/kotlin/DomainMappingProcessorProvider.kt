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
 * Scans for `@ClassSpec`-annotated classes. Each `@ClassSpec` instance on a declaration drives
 * generation of one output class via [ClassGenerator]. For non-partial outputs a bidirectional
 * mapper is also generated via [MapperGenerator].
 *
 * Output shape is determined entirely by the spec parameters — there is no output-kind
 * discrimination based on suffix naming conventions:
 * - `partial = true`           → every field nullable with `= null` (no mapper generated)
 * - Any field has `rules`      → `validate()` + `validateOrThrow()` emitted on the class
 * - `validateOnConstruct=true` → `init { validateOrThrow() }` also emitted
 */
class DomainMappingProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment) = object : SymbolProcessor {

        private val logger = environment.logger
        private val classResolver   = ClassResolver(logger)
        private val classGenerator  = ClassGenerator(environment.codeGenerator, logger, classResolver)
        private val mapperGenerator = MapperGenerator(environment.codeGenerator, logger, classResolver)
        private val transformerRegistryScanner = TransformerRegistryScanner(logger)
        // BundleRegistry is cached across rounds: KSP AA may return 0 @FieldBundle symbols in
        // subsequent rounds (only new-file symbols are visible), so we keep the last non-empty registry.
        private var cachedBundleRegistry: BundleRegistry = BundleRegistry.EMPTY

        override fun process(resolver: Resolver): List<KSAnnotated> {
            val transformerRegistry = transformerRegistryScanner.scan(resolver)

            // --- PASS 1: Build SpecRegistry + collect domain declarations for cycle detection ---
            // targets: domainFQN → (suffix → outputName)
            val targetsBuilder   = mutableMapOf<String, MutableMap<String, String>>()
            // domain FQN → declaration, used only for cycle detection on non-partial outputs
            val nonPartialDomainDecls = mutableMapOf<String, KSClassDeclaration>()

            resolver.getSymbolsWithAnnotation(FQN_CLASS_SPEC)
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    spec.classSpecAnnotations().forEach { csAnn ->
                        val model = csAnn.toClassSpecModel()
                        val fqn   = model.domainClass.qualifiedName?.asString() ?: return@forEach
                        targetsBuilder.getOrPut(fqn) { mutableMapOf() }[model.suffix] = model.outputName
                        if (!model.partial) nonPartialDomainDecls[fqn] = model.domainClass
                    }
                }

            val specRegistry = SpecRegistry(targetsBuilder.mapValues { it.value.toMap() })

            // --- PASS 1b: Cycle detection on non-partial outputs ---
            val graph = mutableMapOf<String, MutableSet<String>>()
            for ((fqn, domainDecl) in nonPartialDomainDecls) {
                val deps = mutableSetOf<String>()
                val ctor = domainDecl.primaryConstructor ?: continue
                for (param in ctor.parameters) {
                    val paramType = param.type.resolve()
                    val paramFqn  = paramType.declaration.qualifiedName?.asString()
                    if (paramFqn != null && paramFqn in nonPartialDomainDecls) deps.add(paramFqn)
                    val elemType = extractCollectionElement(paramType)
                    val elemFqn  = elemType?.declaration?.qualifiedName?.asString()
                    if (elemFqn != null && elemFqn in nonPartialDomainDecls) deps.add(elemFqn)
                }
                if (deps.isNotEmpty()) graph[fqn] = deps
            }
            val cycle = CycleDetector.findCycle(graph)
            if (cycle != null) {
                val path = cycle.joinToString(" -> ") { it.substringAfterLast('.') }
                logger.error("Circular mapping detected: $path. Use exclude = true to break the cycle.")
                return emptyList()
            }

            // --- PASS 1c: Build BundleRegistry ---
            val newBundleRegistry = BundleRegistry.build(resolver, logger)
            if (newBundleRegistry.bundles.isNotEmpty()) {
                cachedBundleRegistry = newBundleRegistry
            }
            classGenerator.bundleRegistry  = cachedBundleRegistry
            mapperGenerator.bundleRegistry = cachedBundleRegistry

            // --- PASS 1d: Validate property references ---
            resolver.getSymbolsWithAnnotation(FQN_CLASS_SPEC)
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec -> spec.validatePropertyRefs(cachedBundleRegistry, logger) }

            // --- PASS 2: Generate ---
            classResolver.registry = specRegistry

            resolver.getSymbolsWithAnnotation(FQN_CLASS_SPEC)
                .filterIsInstance<KSClassDeclaration>()
                .forEach { spec ->
                    spec.classSpecAnnotations().forEach { csAnn ->
                        val model = csAnn.toClassSpecModel()
                        classGenerator.generate(spec, model)
                        if (!model.partial) {
                            mapperGenerator.generate(spec, model, transformerRegistry)
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
    val direct = annotations.filter { it.shortName.asString() == AN_CLASS_SPEC }.toList()
    if (direct.isNotEmpty()) return direct
    val container = annotations.firstOrNull { it.shortName.asString() == AN_CLASS_SPECS }
    val nested = (container?.arguments?.firstOrNull()?.value as? List<*>)
        ?.filterIsInstance<KSAnnotation>() ?: emptyList()
    return nested
}

/** Extracts the domain KSClassDeclaration from a @ClassSpec annotation's `for_` argument. */
private fun KSAnnotation.domainClass(): KSClassDeclaration? =
    (arguments.firstOrNull { it.name?.asString() == PROP_FOR }?.value as? KSType)
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

    annotations
        .filter { it.shortName.asString() in setOf(AN_CLASS_FIELD, AN_FIELD_SPEC) }
        .forEach { ann ->
            val property = ann.argString(PROP_PROPERTY)
            if (property.isNotBlank() && property !in domainProps) {
                logger.error("Unknown property '$property' on $primaryDomainName in $specName")
            }
        }

    classSpecAnnotations().forEach { csAnn ->
        val domainClass = csAnn.domainClass() ?: return@forEach
        val domainName = domainClass.simpleName.asString()
        val localDomainProps = domainClass.primaryConstructor?.parameters
            ?.mapNotNull { it.name?.asString() }?.toSet() ?: return@forEach

        csAnn.argStringList(PROP_BUNDLES).forEach { bundleName ->
            val bundleDecl = bundleRegistry.bundles[bundleName] ?: run {
                logger.error("Unknown bundle '$bundleName' on $specName")
                return@forEach
            }
            bundleDecl.annotations
                .filter { it.shortName.asString() in setOf(AN_CLASS_FIELD, AN_FIELD_SPEC) }
                .forEach { ann ->
                    val property = ann.argString(PROP_PROPERTY)
                    if (property.isNotBlank() && property !in localDomainProps) {
                        logger.error("Unknown property '$property' on $domainName in $specName")
                    }
                }
        }
    }
}

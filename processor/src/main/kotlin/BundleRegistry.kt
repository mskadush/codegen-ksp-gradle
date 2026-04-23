import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * Index of all [@FieldBundle]-annotated classes, keyed by their fully-qualified class name.
 *
 * Built once at the end of Pass 1 by [build] and injected into all generators before Pass 2.
 *
 * [inclusionGraph] maps each bundle FQN to the set of bundle FQNs it directly includes via
 * [@IncludeBundles]. Cycle detection is run during [build]; a cycle aborts the build.
 */
data class BundleRegistry(
    val bundles: Map<String, KSClassDeclaration>,
    val inclusionGraph: Map<String, Set<String>> = emptyMap(),
) {

    companion object {
        val EMPTY = BundleRegistry(emptyMap())

        fun build(resolver: Resolver, logger: KSPLogger): BundleRegistry {
            val result = mutableMapOf<String, KSClassDeclaration>()

            resolver.getSymbolsWithAnnotation(FQN_FIELD_BUNDLE)
                .filterIsInstance<KSClassDeclaration>()
                .forEach { decl ->
                    val fqn = decl.qualifiedName?.asString() ?: run {
                        logger.error("@FieldBundle on ${decl.simpleName.asString()} has no qualified name")
                        return@forEach
                    }
                    result[fqn] = decl
                }

            // Build inclusion graph from @IncludeBundles annotations
            val inclusionGraph = mutableMapOf<String, Set<String>>()
            for ((bundleFQN, bundleDecl) in result) {
                val includesAnn = bundleDecl.annotations.firstOrNull {
                    it.shortName.asString() == AN_INCLUDE_BUNDLES
                }
                @Suppress("UNCHECKED_CAST")
                val includedTypes = (includesAnn?.arguments
                    ?.firstOrNull { it.name?.asString() == PROP_INCLUDE_BUNDLES }
                    ?.value as? List<*>)
                    ?.filterIsInstance<KSType>() ?: emptyList()

                val validIncludes = mutableSetOf<String>()
                for (type in includedTypes) {
                    val includedFQN = type.declaration.qualifiedName?.asString() ?: continue
                    if (includedFQN !in result) {
                        logger.error(
                            "Bundle '${bundleDecl.simpleName.asString()}' includes " +
                            "'${type.declaration.simpleName.asString()}' which is not annotated with @FieldBundle"
                        )
                    } else {
                        validIncludes.add(includedFQN)
                    }
                }
                if (validIncludes.isNotEmpty()) {
                    inclusionGraph[bundleFQN] = validIncludes
                }
            }

            // Cycle detection on the inclusion graph
            val cycle = CycleDetector.findCycle(inclusionGraph)
            if (cycle != null) {
                val path = cycle.joinToString(" -> ") { it.substringAfterLast('.') }
                logger.error("Circular bundle dependency detected: $path")
                return EMPTY
            }

            return BundleRegistry(result, inclusionGraph)
        }
    }

    /**
     * Expands [directFQNs] into the full ordered list of bundle FQNs reachable via transitive
     * [@IncludeBundles] inclusions, using DFS pre-order traversal.
     *
     * - Direct bundles appear before their transitively included bundles.
     * - Each FQN appears at most once (first-visit wins).
     */
    fun transitiveBundleFQNsFor(directFQNs: List<String>): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(fqn: String) {
            if (!visited.add(fqn)) return
            result.add(fqn)
            for (included in (inclusionGraph[fqn] ?: emptySet())) {
                dfs(included)
            }
        }

        for (fqn in directFQNs) {
            dfs(fqn)
        }
        return result
    }
}

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * Index of all [@FieldBundle]-annotated classes, keyed by their declared [name].
 *
 * Built once at the end of Pass 1 by [build] and injected into all generators before Pass 2.
 *
 * [inclusionGraph] maps each bundle name to the set of bundle names it directly includes via
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
                    val ann = decl.annotations.firstOrNull {
                        it.shortName.asString() == AN_FIELD_BUNDLE
                    } ?: return@forEach

                    val name = ann.argString(PROP_NAME)
                    if (name.isBlank()) {
                        logger.error("@FieldBundle on ${decl.simpleName.asString()} has a blank name")
                        return@forEach
                    }

                    val existing = result[name]
                    if (existing != null) {
                        logger.error(
                            "Duplicate @FieldBundle name '$name': declared on both " +
                                "${existing.simpleName.asString()} and ${decl.simpleName.asString()}"
                        )
                        return@forEach
                    }

                    result[name] = decl
                }

            // Build inclusion graph from @IncludeBundles annotations
            val inclusionGraph = mutableMapOf<String, Set<String>>()
            for ((bundleName, bundleDecl) in result) {
                val includesAnn = bundleDecl.annotations.firstOrNull {
                    it.shortName.asString() == AN_INCLUDE_BUNDLES
                }
                val includedNames = includesAnn?.argStringList(PROP_NAMES) ?: emptyList()
                val validIncludes = mutableSetOf<String>()
                for (includedName in includedNames) {
                    if (includedName !in result) {
                        logger.error("Unknown bundle '$includedName' in @IncludeBundles on ${bundleDecl.simpleName.asString()}")
                    } else {
                        validIncludes.add(includedName)
                    }
                }
                if (validIncludes.isNotEmpty()) {
                    inclusionGraph[bundleName] = validIncludes
                }
            }

            // Cycle detection on the inclusion graph
            val cycle = CycleDetector.findCycle(inclusionGraph)
            if (cycle != null) {
                val path = cycle.joinToString(" -> ")
                logger.error("Circular bundle dependency detected: $path")
                return EMPTY
            }

            return BundleRegistry(result, inclusionGraph)
        }
    }

    /**
     * Expands [directNames] into the full ordered list of bundle names reachable via transitive
     * [@IncludeBundles] inclusions, using DFS pre-order traversal.
     *
     * - Direct bundles appear before their transitively included bundles.
     * - Each bundle name appears at most once (first-visit wins).
     */
    fun transitiveBundleNamesFor(directNames: List<String>): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(name: String) {
            if (!visited.add(name)) return
            result.add(name)
            for (included in (inclusionGraph[name] ?: emptySet())) {
                dfs(included)
            }
        }

        for (name in directNames) {
            dfs(name)
        }
        return result
    }
}

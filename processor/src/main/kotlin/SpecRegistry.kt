import com.squareup.kotlinpoet.ClassName

/**
 * Holds the mapping from domain class FQN → (suffix → generated class info) for every spec
 * processed in the current round.
 *
 * Built in a pre-pass before any generator runs so that [ClassResolver] can look up whether a
 * nested field's type is itself a mapped domain class, and choose the correct generated type
 * for the suffix currently being generated.
 *
 * Example: `"za.skadush.codegen.gradle.app.User"` → `{ "Entity" → ("za.skadush.codegen.gradle.generated", "UserEntity") }`
 */
data class SpecRegistry(
    /** domainFQN → suffix → (outputPackage, simpleName) */
    val targets: Map<String, Map<String, Pair<String, String>>>,
) {
    companion object {
        val EMPTY = SpecRegistry(emptyMap())
    }

    /** Returns the generated class simple name for [domainFQN] under [suffix], or `null` if none. */
    fun lookupNested(domainFQN: String, suffix: String): String? =
        targets[domainFQN]?.get(suffix)?.second

    /** Returns the [ClassName] for the generated type at [domainFQN]/[suffix], or `null` if none. */
    fun lookupNestedClassName(domainFQN: String, suffix: String): ClassName? {
        val (pkg, name) = targets[domainFQN]?.get(suffix) ?: return null
        return ClassName(pkg, name)
    }
}

/**
 * Holds the mapping from domain class FQN → (suffix → generated class simple name) for every
 * spec processed in the current round.
 *
 * Built in a pre-pass before any generator runs so that [ClassResolver] can look up whether a
 * nested field's type is itself a mapped domain class, and choose the correct generated type
 * for the suffix currently being generated.
 *
 * Example: `"com.example.User"` → `{ "Entity" → "UserEntity", "Response" → "UserResponse" }`
 */
data class SpecRegistry(
    val targets: Map<String, Map<String, String>>,
) {
    companion object {
        val EMPTY = SpecRegistry(emptyMap())
    }

    /** Returns the generated class name for [domainFQN] under [suffix], or `null` if none. */
    fun lookupNested(domainFQN: String, suffix: String): String? =
        targets[domainFQN]?.get(suffix)
}

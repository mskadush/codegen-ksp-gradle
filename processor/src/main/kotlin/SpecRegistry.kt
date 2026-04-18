/**
 * Holds the mapping from domain class fully-qualified name to the generated target class simple
 * name, for each spec kind processed in the current round.
 *
 * Built in a single pre-pass before any generator runs so that [ClassResolver] can look up
 * whether a nested field's type is itself a mapped domain class.
 *
 * @param entityTargets domain FQN → generated entity simple name, e.g. `"Address"` → `"AddressEntity"`
 * @param dtoTargets    domain FQN → generated DTO simple name, e.g. `"Address"` → `"AddressResponse"`
 */
data class SpecRegistry(
    val entityTargets: Map<String, String>,
    val dtoTargets: Map<String, String>,
) {
    companion object {
        val EMPTY = SpecRegistry(emptyMap(), emptyMap())
    }
}

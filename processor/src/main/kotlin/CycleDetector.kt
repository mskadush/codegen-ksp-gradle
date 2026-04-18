/**
 * Detects cycles in a directed graph using DFS with grey/black node colouring.
 *
 * @param graph Map from node name to the set of node names it directly depends on.
 * @return The first cycle found as an ordered list `[A, B, A]`, or `null` if the graph is acyclic.
 */
object CycleDetector {

    fun findCycle(graph: Map<String, Set<String>>): List<String>? {
        val grey  = mutableSetOf<String>()
        val black = mutableSetOf<String>()
        val parent = mutableMapOf<String, String?>()

        fun dfs(node: String): List<String>? {
            grey.add(node)
            for (neighbour in (graph[node] ?: emptySet())) {
                if (neighbour in grey) {
                    // Reconstruct cycle path from neighbour back to itself via parent chain
                    val cycle = mutableListOf(neighbour)
                    var cur = node
                    while (cur != neighbour) {
                        cycle.add(0, cur)
                        cur = parent[cur] ?: break
                    }
                    cycle.add(0, neighbour)
                    return cycle
                }
                if (neighbour !in black) {
                    parent[neighbour] = node
                    dfs(neighbour)?.let { return it }
                }
            }
            grey.remove(node)
            black.add(node)
            return null
        }

        for (node in graph.keys) {
            if (node !in black) {
                parent[node] = null
                dfs(node)?.let { return it }
            }
        }
        return null
    }
}

import za.skadush.codegen.gradle.annotations.FieldBundle
import za.skadush.codegen.gradle.annotations.IncludeBundles

/**
 * Wrapper bundle for Order entities. Pulls in [OrderIdBundle] transitively via
 * @IncludeBundles, demonstrating two-level transitive bundle resolution.
 *
 * When a spec references `OrderBaseBundle::class`, the processor expands it DFS-order to:
 *   OrderBaseBundle → OrderIdBundle
 * so all field configs from both bundles are merged into the output class.
 */
@FieldBundle
@IncludeBundles([OrderIdBundle::class])
object OrderBaseBundle

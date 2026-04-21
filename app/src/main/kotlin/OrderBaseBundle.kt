import com.example.annotations.FieldBundle
import com.example.annotations.IncludeBundles

/**
 * Wrapper bundle for Order entities. Pulls in [OrderIdBundle] ("orderId") transitively via
 * @IncludeBundles, demonstrating two-level transitive bundle resolution.
 *
 * When a spec references "orderBase", the processor expands it DFS-order to:
 *   orderBase → orderId
 * so all field configs from both bundles are merged into the output class.
 */
@FieldBundle("orderBase")
@IncludeBundles(names = ["orderId"])
object OrderBaseBundle

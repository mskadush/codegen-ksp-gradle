import com.example.annotations.BundleMergeStrategy
import com.example.annotations.ClassSpec

/**
 * Spec for Order. Uses "orderBase" which transitively includes "orderId" via @IncludeBundles,
 * so OrderEntity.id gets @Id and @GeneratedValue without listing "orderId" explicitly here.
 */
@ClassSpec(
    for_ = Order::class,
    suffix = "Entity",
    bundles = ["orderBase"],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE,
)
object OrderSpec

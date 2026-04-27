import za.skadush.codegen.gradle.annotations.BundleMergeStrategy
import za.skadush.codegen.gradle.annotations.ClassSpec

/**
 * Spec for Order. Uses [OrderBaseBundle] which transitively includes [OrderIdBundle] via @IncludeBundles,
 * so OrderEntity.id gets @Id and @GeneratedValue without listing [OrderIdBundle] explicitly here.
 */
@ClassSpec(
    for_ = Order::class,
    suffix = "Entity",
    bundles = [OrderBaseBundle::class],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE,
)
object OrderSpec

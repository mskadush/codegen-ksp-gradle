import za.skadush.codegen.gradle.annotations.BundleMergeStrategy
import za.skadush.codegen.gradle.annotations.ClassSpec
import za.skadush.codegen.gradle.annotations.UnmappedNestedStrategy

/**
 * Spec for Order. Uses [OrderBaseBundle] which transitively includes [OrderIdBundle] via @IncludeBundles,
 * so OrderEntity.id gets @Id and @GeneratedValue without listing [OrderIdBundle] explicitly here.
 *
 * [UnmappedNestedStrategy.AUTO_GENERATE] causes the processor to automatically generate a passthrough
 * [AddressEntity] from [Address] — no separate AddressSpec file is required.
 */
@ClassSpec(
    for_ = Order::class,
    suffix = "Entity",
    bundles = [OrderBaseBundle::class],
    bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE,
    unmappedNestedStrategy = UnmappedNestedStrategy.AUTO_GENERATE,
)
object OrderSpec

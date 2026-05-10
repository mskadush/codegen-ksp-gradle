import za.skadush.codegen.gradle.annotations.CustomAnnotation
import za.skadush.codegen.gradle.annotations.FieldBundle
import za.skadush.codegen.gradle.annotations.FieldOverride

/**
 * Leaf bundle: marks the primary key of any Order-style entity with JPA @Id and @GeneratedValue.
 * Included transitively by [OrderBaseBundle] via @IncludeBundles.
 */
@FieldBundle
@FieldOverride(
    for_ = ["Entity"],
    property = "id",
    annotations = [
        CustomAnnotation(annotation = jakarta.persistence.Id::class),
        CustomAnnotation(
            annotation = jakarta.persistence.GeneratedValue::class,
            members = ["strategy=jakarta.persistence.GenerationType.IDENTITY"]
        )
    ]
)
object OrderIdBundle

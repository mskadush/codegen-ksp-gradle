import com.example.annotations.CustomAnnotation
import com.example.annotations.FieldBundle
import com.example.annotations.FieldSpec

/**
 * Leaf bundle: marks the primary key of any Order-style entity with JPA @Id and @GeneratedValue.
 * Included transitively by [OrderBaseBundle] via @IncludeBundles.
 */
@FieldBundle
@FieldSpec(
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

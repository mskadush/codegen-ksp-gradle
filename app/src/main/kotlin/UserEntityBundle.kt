import com.example.annotations.CustomAnnotation
import com.example.annotations.FieldBundle
import com.example.annotations.FieldSpec

/**
 * Entity-specific bundle for User: marks the primary key with JPA @Id and @GeneratedValue so
 * the spec itself stays clean of persistence boilerplate.
 *
 * Referenced from UserSpec via bundles = ["timestamps", "userEntity"] with MERGE_ADDITIVE so
 * the spec's nullable override on id is preserved while the bundle's annotations are added.
 */
@FieldBundle("userEntity")
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
object UserEntityBundle

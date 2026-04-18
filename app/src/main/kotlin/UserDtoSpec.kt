import com.example.annotations.DtoField
import com.example.annotations.DtoSpec

@DtoSpec(for_ = User::class, suffix = "Response")
@DtoField(property = "email", rename = "emailAddress")  // → renamed in UserResponse
@DtoField(property = "id", exclude = true)              // → id absent from UserResponse
object UserDtoSpec

data class User(
    val id: Long,
    val name: String,
    val email: String,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant?,
)

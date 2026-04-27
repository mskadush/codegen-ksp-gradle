enum class OrderStatus { PENDING, CONFIRMED, SHIPPED, DELIVERED }

data class Order(
    val id: Long,
    val address: Address,
    val status: OrderStatus,
    val tags: List<String>,
)

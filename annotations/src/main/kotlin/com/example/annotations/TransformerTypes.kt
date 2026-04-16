package com.example.annotations

interface FieldTransformer<Domain, Target> {
    fun toTarget(value: Domain): Target
    fun toDomain(value: Target): Domain
}

class NoOpTransformer : FieldTransformer<Any, Any> {
    override fun toTarget(value: Any) = value
    override fun toDomain(value: Any) = value
}

interface RequestValidator<T> {
    fun validate(request: T, context: Any)
}

class NoOpValidator<T> : RequestValidator<T> {
    override fun validate(request: T, context: Any) = Unit
}

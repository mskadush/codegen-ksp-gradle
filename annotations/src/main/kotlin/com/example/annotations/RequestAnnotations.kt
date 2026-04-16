package com.example.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RequestSpec(val for_: KClass<*>)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class CreateSpec(
    val suffix: String = "CreateRequest",
    val validator: KClass<out RequestValidator<*>> = NoOpValidator::class,
    val fields: Array<CreateField> = []
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class UpdateSpec(
    val suffix: String = "UpdateRequest",
    val partial: Boolean = true,
    val validator: KClass<out RequestValidator<*>> = NoOpValidator::class,
    val fields: Array<UpdateField> = []
)

annotation class CreateField(
    val property: String,
    val rules: Array<Rule> = [],
    val exclude: Boolean = false
)

annotation class UpdateField(
    val property: String,
    val rules: Array<Rule> = [],
    val exclude: Boolean = false
)

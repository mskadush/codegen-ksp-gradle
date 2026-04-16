package com.example.annotations

annotation class DbAnnotation(
    val fqn: String,
    val members: Array<AnnotationMember> = []
)

annotation class AnnotationMember(
    val name: String,
    val value: String
)

annotation class Index(
    val columns: Array<String>,
    val unique: Boolean = false,
    val name: String = ""
)

annotation class Relation(
    val type: RelationType,
    val mappedBy: String = "",
    val cascade: Array<CascadeType> = [],
    val fetch: FetchType = FetchType.LAZY,
    val joinColumn: String = "",
    val joinTable: String = ""
)

annotation class Rule {
    annotation class Required
    annotation class Email
    annotation class NotBlank
    annotation class Positive
    annotation class Past
    annotation class Future
    annotation class MinLength(val value: Int)
    annotation class MaxLength(val value: Int)
    annotation class Min(val value: Double)
    annotation class Max(val value: Double)
    annotation class Pattern(val regex: String, val message: String = "")
    annotation class Custom(val fn: String)
}

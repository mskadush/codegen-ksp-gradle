package com.example.app

import com.example.annotations.FieldTransformer

/** Converts a [String] field to uppercase when writing to the entity/DTO and back to lowercase when reading. */
class UpperCaseTransformer : FieldTransformer<String, String> {
    override fun toTarget(value: String): String = value.uppercase()
    override fun toDomain(value: String): String = value.lowercase()
}

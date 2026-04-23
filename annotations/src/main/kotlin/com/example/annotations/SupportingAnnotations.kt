package com.example.annotations

/**
 * Represents an arbitrary annotation to be emitted on a generated class or field.
 *
 * Use this when you need the code generator to attach framework-specific annotations
 * (e.g. JPA, Jackson) that are not natively modelled by the DSL.
 *
 * Each entry in [members] is a `"paramName=value"` string. Enum values may be expressed
 * as short names (e.g. `"fetch=LAZY"`); the processor resolves the fully-qualified enum
 * type from the annotation class's parameter declaration and adds the required import.
 *
 * Examples:
 * ```kotlin
 * CustomAnnotation(Table::class, members = ["name=\"users\"", "schema=\"public\""])
 * CustomAnnotation(OneToMany::class, members = ["fetch=LAZY", "cascade=ALL"])
 * ```
 *
 * @param annotation The annotation class to emit.
 * @param members Key-value pairs for the annotation's parameters, each as `"name=value"`.
 */
annotation class CustomAnnotation(
    val annotation: kotlin.reflect.KClass<out Annotation>,
    @org.intellij.lang.annotations.Language("kotlin") val members: Array<String> = []
)

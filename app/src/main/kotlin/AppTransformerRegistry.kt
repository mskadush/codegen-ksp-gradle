package za.skadush.codegen.gradle.app

import za.skadush.codegen.gradle.annotations.RegisterTransformer
import za.skadush.codegen.gradle.annotations.TransformerRegistry

@TransformerRegistry
object AppTransformerRegistry {
    @RegisterTransformer("upperCase")
    val upperCase = UpperCaseTransformer()
}

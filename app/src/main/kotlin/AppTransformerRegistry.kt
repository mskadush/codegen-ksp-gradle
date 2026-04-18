package com.example.app

import com.example.annotations.RegisterTransformer
import com.example.annotations.TransformerRegistry

@TransformerRegistry
object AppTransformerRegistry {
    @RegisterTransformer("upperCase")
    val upperCase = UpperCaseTransformer()
}

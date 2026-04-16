# Demo: Read a `KClass<*>` Property from an Annotation in KSP

## Goal

Prove that KSP can read a `KClass<*>` argument from an annotation and use it during code generation — a prerequisite skill for the full domain mapping processor. The existing `@HelloWorld` annotation is extended with a `target: KClass<*>` property; the processor reads it and prints the class name in the generated function body.

---

## Files to Change

| File | Change |
|---|---|
| `annotations/src/main/kotlin/com/example/annotations/HelloWorld.kt` | Add `val target: KClass<*>` parameter |
| `app/src/main/kotlin/Main.kt` | Pass a class: `@HelloWorld(target = String::class)` |
| `processor/src/main/kotlin/HelloWorldProcessor.kt` | Read `target` from annotation arguments → emit class name in generated body |

---

## Implementation Details

### 1. `HelloWorld.kt` — add `target` property

```kotlin
package com.example.annotations

import kotlin.reflect.KClass

annotation class HelloWorld(val target: KClass<*>)
```

### 2. `Main.kt` — pass a class to the annotation

```kotlin
import com.example.annotations.HelloWorld

@HelloWorld(target = String::class)
fun main() {
    helloWorld()
}
```

Any class can be used here. `String::class` is the simplest to verify.

### 3. `HelloWorldProcessor.kt` — read `target` via KSP API

Inside `visitFunctionDeclaration`, resolve the annotation argument:

```kotlin
val annotation = function.annotations.first {
    it.shortName.asString() == "HelloWorld"
}
val targetType = annotation.arguments
    .first { it.name?.asString() == "target" }
    .value as KSType
val className = targetType.declaration.qualifiedName?.asString() ?: "<unknown>"
```

Then emit the class name into the generated file:

```kotlin
file.write("""
    fun helloWorld(): Unit {
        println("Hello world! Target class: ${'$'}className")
    }
""".trimIndent())
```

The `KSType.declaration.qualifiedName` pattern is the same pattern the domain mapping processor will use to resolve `for_ = User::class` on spec objects.

---

## Verification

```bash
./gradlew :app:run
# Expected output:
# Hello world! Target class: kotlin.String
```

Check the generated file at:
```
app/build/generated/ksp/main/kotlin/GeneratedHelloWorld.kt
```
It should contain the hardcoded class name string.

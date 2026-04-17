# Step 2 — KotlinPoet: generate empty `Entity` class

## Context

Step 1 wired all modules and scaffolded `DomainMappingProcessor` (scans `@EntitySpec`, logs, produces no output). Step 2 introduces the first KotlinPoet output: emit a bare `class UserEntity()` for each `@EntitySpec`-annotated object. Also introduces the `User` domain class and `UserEntitySpec` in `app/` to drive the processor.

---

## Checklist

- [x] Create `app/src/main/kotlin/User.kt`
- [x] Create `app/src/main/kotlin/UserEntitySpec.kt`
- [x] Create `processor/src/main/kotlin/EntityGenerator.kt`
- [x] Update `processor/src/main/kotlin/DomainMappingProcessor.kt`
- [x] Run `./gradlew :app:kspKotlin` — confirm `UserEntity.kt` is generated
- [x] Mark step 2 checkboxes in `003-full-domain-mapping-processor.md`

---

## Files

### `app/src/main/kotlin/User.kt` (new)
```kotlin
data class User(
    val id: Long,
    val name: String,
    val email: String,
)
```

### `app/src/main/kotlin/UserEntitySpec.kt` (new)
```kotlin
import com.example.annotations.EntitySpec

@EntitySpec(for_ = User::class, table = "users")
object UserEntitySpec
```

### `processor/src/main/kotlin/EntityGenerator.kt` (new)
```kotlin
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class EntityGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(spec: KSClassDeclaration) {
        val annotation = spec.annotations.first { it.shortName.asString() == "EntitySpec" }
        val forArg = annotation.arguments.first { it.name?.asString() == "for_" }
        val domainName = (forArg.value as KSType).declaration.simpleName.asString()
        val entityName = "${domainName}Entity"

        val fileSpec = FileSpec.builder("", entityName)
            .addType(TypeSpec.classBuilder(entityName).build())
            .build()

        fileSpec.writeTo(codeGenerator, aggregating = false)
        logger.info("EntityGenerator: generated $entityName")
    }
}
```

> Note: plain `class` (not `data class`) — Kotlin requires ≥1 constructor parameter for data classes; fields added in Step 3.

### `processor/src/main/kotlin/DomainMappingProcessor.kt` (update)
```kotlin
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

class DomainMappingProcessor(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    private val entityGenerator = EntityGenerator(codeGenerator, logger)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getSymbolsWithAnnotation("com.example.annotations.EntitySpec")
            .filterIsInstance<KSClassDeclaration>()
            .forEach { entityGenerator.generate(it) }
        return emptyList()
    }
}
```

---

## Verification

```bash
./gradlew :app:kspKotlin
# app/build/generated/ksp/main/kotlin/UserEntity.kt must exist
# class UserEntity()
```

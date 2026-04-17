# Step 4: Field Overrides + @Table / @Column Emission

## Context

Step 4 of `plans-by-ai/003-full-domain-mapping-processor.md`. The processor can already generate a bare `data class UserEntity(val id: Long, val name: String, val email: String)` from the `@EntitySpec` annotation. Step 4 wires in field-level overrides from `@EntityField`:
- `exclude = true` → field absent from the generated class
- `column = "..."` → emit `@Column(name = "...")` on the generated property
- `NullableOverride.YES/NO` → override Kotlin nullability of the generated field type
- `EntitySpec.table` / `EntitySpec.schema` → emit `@Table(name, schema)` on the generated class

## Files to Modify

| File | Change |
|---|---|
| `app/build.gradle.kts` | Add `compileOnly("jakarta.persistence:jakarta.persistence-api:3.1.0")` |
| `app/src/main/kotlin/UserEntitySpec.kt` | Add 3 `@EntityField` overrides (column rename, exclude, nullable override) |
| `processor/src/main/kotlin/EntityGenerator.kt` | Parse `@EntityField`, emit `@Table`/`@Column`, filter excludes, apply nullable override |
| `plans-by-ai/003-full-domain-mapping-processor.md` | Tick Step 4 checkboxes |

## Implementation

### 1. `app/build.gradle.kts`

Add to `dependencies` block:
```kotlin
compileOnly("jakarta.persistence:jakarta.persistence-api:3.1.0")
```

The processor emits `@Table`/`@Column` by `ClassName` string only — no JPA jar needed in the processor. The app needs the jar to compile the generated output.

### 2. `app/src/main/kotlin/UserEntitySpec.kt`

```kotlin
import com.example.annotations.EntityField
import com.example.annotations.EntitySpec
import com.example.annotations.NullableOverride

@EntitySpec(for_ = User::class, table = "users", schema = "public")
@EntityField(property = "name", column = "user_name")           // → @Column(name="user_name")
@EntityField(property = "email", exclude = true)                // → field absent
@EntityField(property = "id", nullable = NullableOverride.YES)  // → Long?
object UserEntitySpec
```

### 3. `processor/src/main/kotlin/EntityGenerator.kt`

**New imports**: `AnnotationSpec`, `ClassName`, `ParameterSpec`

**Logic changes in `generate()`** (single-pass loop replaces existing field loop):

**a) Read table/schema from `@EntitySpec`:**
```kotlin
val tableArg  = annotation.arguments.firstOrNull { it.name?.asString() == "table"  }?.value as? String ?: ""
val schemaArg = annotation.arguments.firstOrNull { it.name?.asString() == "schema" }?.value as? String ?: ""
```

**b) Build override map from `@EntityField` annotations on the spec object:**
```kotlin
// Kotlin @Repeatable: KSP returns each instance separately in spec.annotations
val overrideMap: Map<String, KSAnnotation> = spec.annotations
    .filter { it.shortName.asString() == "EntityField" }
    .associateBy { it.arguments.first { a -> a.name?.asString() == "property" }.value as String }
```

**c) Helper extension functions on `KSAnnotation`:**
```kotlin
fun KSAnnotation.argString(name: String) =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

fun KSAnnotation.argBool(name: String) =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean ?: false

fun KSAnnotation.argEnumName(name: String): String {
    val raw = arguments.firstOrNull { it.name?.asString() == name }?.value ?: return "UNSET"
    return when (raw) {
        is KSType -> raw.declaration.simpleName.asString()
        is KSClassDeclaration -> raw.simpleName.asString()
        else -> raw.toString().substringAfterLast('.')
    }
}
```

**d) Emit `@Table` on the class (conditional on non-blank):**
```kotlin
val tableAnnotation = AnnotationSpec.builder(ClassName("jakarta.persistence", "Table")).apply {
    if (tableArg.isNotBlank()) addMember("name = %S", tableArg)
    if (schemaArg.isNotBlank()) addMember("schema = %S", schemaArg)
}.build()
```

**e) Single-pass loop (replaces old loop):**
```kotlin
val ctorBuilder = FunSpec.constructorBuilder()
val classBuilder = TypeSpec.classBuilder(entityName)
    .addModifiers(KModifier.DATA)
    .addAnnotation(tableAnnotation)

val columnClassName = ClassName("jakarta.persistence", "Column")
for (field in fields) {
    val override = overrideMap[field.originalName]
    if (override?.argBool("exclude") == true) continue

    val finalType = when (override?.argEnumName("nullable") ?: "UNSET") {
        "YES" -> field.resolvedType.copy(nullable = true)
        "NO"  -> field.resolvedType.copy(nullable = false)
        else  -> field.resolvedType
    }

    ctorBuilder.addParameter(field.originalName, finalType)

    val propBuilder = PropertySpec.builder(field.originalName, finalType)
        .initializer(field.originalName)
    val col = override?.argString("column") ?: ""
    if (col.isNotBlank()) {
        propBuilder.addAnnotation(
            AnnotationSpec.builder(columnClassName).addMember("name = %S", col).build()
        )
    }
    classBuilder.addProperty(propBuilder.build())
}
classBuilder.primaryConstructor(ctorBuilder.build())
```

## Expected Generated Output

```kotlin
// app/build/generated/ksp/main/kotlin/UserEntity.kt
@Table(name = "users", schema = "public")
data class UserEntity(
    val id: Long?,              // NullableOverride.YES
    @Column(name = "user_name")
    val name: String,
    // email absent
)
```

## Pitfalls

- **Enum arg reading**: KSP 2.x delivers enum annotation args as `KSType` (declaration = enum entry). The three-branch `when` fallback handles all known KSP versions.
- **Kotlin `@Repeatable`**: KSP unwraps the container transparently — `filter { shortName == "EntityField" }` returns all instances.
- **Constructor/property sync**: Both `ctorBuilder.addParameter` and `classBuilder.addProperty` must use the same `finalType` and be skipped together for excluded fields — hence the single loop.
- **`@Table` when `table` is blank**: Conditional `addMember` prevents emitting `@Table(name = "")`.

## Verification

```bash
./gradlew :app:kspKotlin
# app/build/generated/ksp/main/kotlin/UserEntity.kt must contain:
# @Table(name = "users", schema = "public") on class
# @Column(name = "user_name") on `name` property
# `email` absent from constructor
# `id: Long?` (nullable)
```

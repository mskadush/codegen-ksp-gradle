import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toTypeName

/**
 * Validates a domain class and extracts its primary constructor parameters as [FieldModel]s.
 *
 * Used by [EntityGenerator] (and other generators) to obtain the list of fields that should
 * appear in a generated class. Validation errors are reported through [KSPLogger] and cause
 * the method to return `null` so the caller can skip generation cleanly.
 *
 * Set [registry] before calling [resolveWithKinds] so that nested mapped types are classified
 * correctly as [FieldKind.MappedObject] or [FieldKind.MappedCollection].
 *
 * @param logger KSP logger used to emit compile-time error messages.
 */
class ClassResolver(private val logger: KSPLogger) {

    /** Injected by [DomainMappingProcessorProvider] before generation begins. */
    var registry: SpecRegistry = SpecRegistry.EMPTY

    /**
     * Resolves [cls] into a list of [FieldModel]s derived from its primary constructor parameters.
     *
     * All fields are classified as [FieldKind.Primitive] — use [resolveWithKinds] for nested-type
     * awareness.
     *
     * @param cls The domain class declaration to resolve.
     * @return A list of [FieldModel]s, one per constructor parameter, or `null` if validation fails.
     */
    fun resolve(cls: KSClassDeclaration): List<FieldModel>? {
        if (Modifier.DATA !in cls.modifiers) {
            logger.error("${cls.simpleName.asString()} is not a data class — @EntitySpec requires a data class")
            return null
        }
        val ctor = cls.primaryConstructor ?: run {
            logger.error("${cls.simpleName.asString()} has no primary constructor")
            return null
        }
        return ctor.parameters.map { param ->
            FieldModel(
                originalName = param.name!!.asString(),
                originalType = param.type.resolve(),
                resolvedType = param.type.toTypeName(),
            )
        }
    }

    /**
     * Like [resolve] but additionally classifies each field's type using the current [registry],
     * and applies [unmappedNestedStrategy] for types that are non-primitive but have no spec.
     *
     * - `FAIL`    → logs an error and skips the field (build will fail after the round).
     * - `EXCLUDE` → silently skips the field.
     * - `INLINE`  → flattens the nested class's own fields into the parent, prefixed by
     *   [inlineOverrides] entries' `inlinePrefix` (or the field name if blank).
     *
     * @param cls The domain class to resolve.
     * @param unmappedNestedStrategy One of `"FAIL"`, `"INLINE"`, `"EXCLUDE"`.
     * @param inlineOverrides Map of field name → its `@EntityField`/`@DtoField` annotation,
     *   used to read `inlinePrefix` when strategy is INLINE.
     */
    fun resolveWithKinds(
        cls: KSClassDeclaration,
        unmappedNestedStrategy: String,
        inlineOverrides: Map<String, KSAnnotation> = emptyMap(),
    ): List<FieldModel>? {
        val baseFields = resolve(cls) ?: return null
        val result = mutableListOf<FieldModel>()

        for (field in baseFields) {
            val kind = classifyField(field, unmappedNestedStrategy)
            when {
                kind == null -> continue   // EXCLUDE or FAIL path — skip field

                kind is FieldKind.Primitive && unmappedNestedStrategy == "INLINE" &&
                        isNonPrimitiveUnmapped(field) -> {
                    // INLINE: flatten nested class fields into parent
                    val nestedCls = field.originalType.declaration as? KSClassDeclaration ?: continue
                    val prefix = inlineOverrides[field.originalName]?.argString("inlinePrefix")
                        ?.takeIf { it.isNotBlank() } ?: field.originalName
                    val nestedFields = resolve(nestedCls) ?: continue
                    for (nf in nestedFields) {
                        val inlinedName = "$prefix${nf.originalName.replaceFirstChar { it.uppercase() }}"
                        result.add(nf.copy(
                            originalName = inlinedName,
                            fieldKind = FieldKind.Primitive,
                            sourceExpression = "this.${field.originalName}.${nf.originalName}",
                        ))
                    }
                }

                else -> result.add(field.copy(fieldKind = kind))
            }
        }
        return result
    }

    /**
     * Classifies [field]'s type against [registry].
     *
     * Returns `null` when the field should be dropped (FAIL or EXCLUDE on unmapped nested type).
     */
    private fun classifyField(field: FieldModel, unmappedNestedStrategy: String): FieldKind? {
        // Collection types: List<T> / Set<T>
        val collectionElement = extractCollectionElement(field.originalType)
        if (collectionElement != null) {
            val elemFqn = collectionElement.declaration.qualifiedName?.asString() ?: return FieldKind.Primitive
            val targetName = registry.entityTargets[elemFqn] ?: registry.dtoTargets[elemFqn]
            if (targetName != null) {
                return FieldKind.MappedCollection(
                    targetName = targetName,
                    targetClassName = ClassName("", targetName),
                    collectionFQN = field.originalType.declaration.qualifiedName!!.asString(),
                )
            }
            return FieldKind.Primitive   // collection of primitives
        }

        val fqn = field.originalType.declaration.qualifiedName?.asString() ?: return FieldKind.Primitive

        // Standard library types are always primitive
        if (fqn.startsWith("kotlin.") || fqn.startsWith("java.")) return FieldKind.Primitive

        // Look up in registry
        val targetName = registry.entityTargets[fqn] ?: registry.dtoTargets[fqn]
        if (targetName != null) {
            return FieldKind.MappedObject(
                targetName = targetName,
                targetClassName = ClassName("", targetName),
            )
        }

        // Unmapped non-primitive — apply strategy
        return when (unmappedNestedStrategy) {
            "EXCLUDE" -> {
                logger.info("ClassResolver: excluding unmapped nested type '$fqn' on field '${field.originalName}'")
                null
            }
            "INLINE" -> null   // handled by caller's expansion logic via isNonPrimitiveUnmapped check
            else -> {   // FAIL
                val simpleName = field.originalType.declaration.simpleName.asString()
                logger.error(
                    "$simpleName has no @EntitySpec. " +
                    "Declare one or set unmappedNestedStrategy = INLINE or EXCLUDE"
                )
                null
            }
        }
    }

    /**
     * Returns `true` when [field]'s type is a non-primitive class not found in [registry],
     * meaning it is a candidate for INLINE expansion.
     */
    private fun isNonPrimitiveUnmapped(field: FieldModel): Boolean {
        val fqn = field.originalType.declaration.qualifiedName?.asString() ?: return false
        if (fqn.startsWith("kotlin.") || fqn.startsWith("java.")) return false
        if (registry.entityTargets.containsKey(fqn) || registry.dtoTargets.containsKey(fqn)) return false
        if (extractCollectionElement(field.originalType) != null) return false
        return true
    }

    /**
     * If [type] is `List<T>` or `Set<T>`, returns the resolved element type `T`.
     * Otherwise returns `null`.
     */
    private fun extractCollectionElement(type: KSType): KSType? {
        val fqn = type.declaration.qualifiedName?.asString() ?: return null
        if (fqn != "kotlin.collections.List" && fqn != "kotlin.collections.Set") return null
        return type.arguments.firstOrNull()?.type?.resolve()
    }
}

private fun KSAnnotation.argString(name: String): String =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

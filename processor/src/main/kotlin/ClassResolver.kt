import za.skadush.codegen.gradle.annotations.UnmappedNestedStrategy
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toTypeName

/**
 * Validates a domain class and extracts its primary constructor parameters as [FieldModel]s.
 *
 * Used by [ClassGenerator] (and other generators) to obtain the list of fields that should
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
            logger.error("${cls.simpleName.asString()} is not a data class — @ClassSpec requires a data class")
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
     * - `INLINE`  → flattens the nested class's own fields into the parent, using the field
     *   name as the prefix.
     *
     * [suffix] is the output suffix being generated (e.g. `"Entity"`, `"Response"`), used to
     * look up the correct generated type for nested mapped fields via [SpecRegistry.lookupNested].
     *
     * @param cls The domain class to resolve.
     * @param unmappedNestedStrategy Strategy for fields whose type has no spec for [suffix].
     * @param suffix The output suffix, used for nested-type lookup.
     */
    fun resolveWithKinds(
        cls: KSClassDeclaration,
        unmappedNestedStrategy: UnmappedNestedStrategy,
        suffix: String = "",
    ): List<FieldModel>? {
        val baseFields = resolve(cls) ?: return null
        val result = mutableListOf<FieldModel>()

        for (field in baseFields) {
            val kind = classifyField(field, unmappedNestedStrategy, suffix)
            when {
                kind == null -> continue   // EXCLUDE or FAIL path — skip field

                kind is FieldKind.Primitive && unmappedNestedStrategy == UnmappedNestedStrategy.INLINE &&
                        isNonPrimitiveUnmapped(field) -> {
                    // INLINE: flatten nested class fields into parent, prefixed by field name
                    val nestedCls = field.originalType.declaration as? KSClassDeclaration ?: continue
                    val prefix = field.originalName
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
     * Classifies [field]'s type against [registry] for the given [suffix].
     *
     * Returns `null` when the field should be dropped (FAIL or EXCLUDE on unmapped nested type).
     */
    private fun classifyField(field: FieldModel, unmappedNestedStrategy: UnmappedNestedStrategy, suffix: String): FieldKind? {
        // Collection types: List<T> / Set<T>
        val collectionElement = extractCollectionElement(field.originalType)
        if (collectionElement != null) {
            val elemFqn = collectionElement.declaration.qualifiedName?.asString() ?: return FieldKind.Primitive
            val targetName = registry.lookupNested(elemFqn, suffix)
            if (targetName != null) {
                return FieldKind.MappedCollection(
                    targetName = targetName,
                    targetClassName = registry.lookupNestedClassName(elemFqn, suffix)!!,
                    collectionFQN = field.originalType.declaration.qualifiedName!!.asString(),
                )
            }
            return FieldKind.Primitive   // collection of primitives / no mapped equivalent for this suffix
        }

        val fqn = field.originalType.declaration.qualifiedName?.asString() ?: return FieldKind.Primitive

        // Standard library types are always primitive
        if (fqn.startsWith("kotlin.") || fqn.startsWith("java.")) return FieldKind.Primitive

        // Enums are atomic — there is nothing to generate for them
        if ((field.originalType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS)
            return FieldKind.Primitive

        // Look up the nested type for the same suffix
        val targetName = registry.lookupNested(fqn, suffix)
        if (targetName != null) {
            return FieldKind.MappedObject(
                targetName = targetName,
                targetClassName = registry.lookupNestedClassName(fqn, suffix)!!,
            )
        }

        // Unmapped non-primitive — apply strategy
        return when (unmappedNestedStrategy) {
            UnmappedNestedStrategy.EXCLUDE -> {
                logger.info("ClassResolver: excluding unmapped nested type '$fqn' on field '${field.originalName}'")
                null
            }
            UnmappedNestedStrategy.INLINE -> null   // handled by caller's expansion logic via isNonPrimitiveUnmapped check
            UnmappedNestedStrategy.FAIL -> {
                val simpleName = field.originalType.declaration.simpleName.asString()
                logger.error(
                    "$simpleName has no spec for suffix '$suffix'. " +
                    "Declare one or set unmappedNestedStrategy = INLINE, EXCLUDE, or AUTO_GENERATE"
                )
                null
            }
            // AUTO_GENERATE: the BFS discovery pass in DomainMappingProcessorProvider pre-populates
            // the registry with all auto-generated types before Pass 2 runs. Reaching this branch
            // means the type was NOT picked up by discovery (e.g., not a data class, or the BFS
            // was seeded from a different suffix). Treat as primitive to avoid a spurious error.
            UnmappedNestedStrategy.AUTO_GENERATE -> {
                logger.warn(
                    "AUTO_GENERATE: '${field.originalType.declaration.simpleName.asString()}' " +
                    "was not pre-registered for suffix '$suffix' — treating as primitive"
                )
                FieldKind.Primitive
            }
        }
    }

    /**
     * Returns `true` when [field]'s type is a non-primitive class not found in [registry],
     * meaning it is a candidate for INLINE expansion.
     */
    private fun isNonPrimitiveUnmapped(field: FieldModel): Boolean {
        if (extractCollectionElement(field.originalType) != null) return false
        if (field.originalType.toAutoGenerateCandidate() == null) return false
        val fqn = field.originalType.declaration.qualifiedName?.asString() ?: return false
        return !registry.targets.containsKey(fqn)
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


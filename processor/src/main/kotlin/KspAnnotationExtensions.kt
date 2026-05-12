import za.skadush.codegen.gradle.annotations.BundleMergeStrategy
import za.skadush.codegen.gradle.annotations.NullableOverride
import za.skadush.codegen.gradle.annotations.UnmappedNestedStrategy
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName

// ---------------------------------------------------------------------------
// MergedOverride — consolidated view of @FieldSpec + @FieldOverride for one field
// ---------------------------------------------------------------------------

/**
 * Merged field configuration for a single domain property, combining a [FieldSpec]
 * (which applies to all outputs) with any [FieldOverride] scoped to a specific suffix.
 *
 * [FieldOverride] params win over [FieldSpec] for overlapping properties.
 */
data class MergedOverride(
    val property: String,
    val exclude: Boolean,
    val nullable: NullableOverride,
    val transformerFQN: String?,
    /** Raw CustomAnnotation KSAnnotations from @FieldSpec.annotations */
    val classLevelAnn: List<KSAnnotation>,
    /** Raw CustomAnnotation KSAnnotations from @FieldOverride.annotations */
    val fieldLevelAnn: List<KSAnnotation>,
    val rename: String,
    val validators: List<KSType>,
    /** Merged [Default] config after FieldOverride-wins layering. Sentinel = no default. */
    val defaultConfig: DefaultConfig = DefaultConfig.SENTINEL,
) {
    /** Combined annotations: class-level first, then field-level overrides. */
    val allAnnotations: List<KSAnnotation> get() = classLevelAnn + fieldLevelAnn
}

// ---------------------------------------------------------------------------
// mergedFieldOverrides — build merged override map for a ClassSpec suffix
// ---------------------------------------------------------------------------

/**
 * Builds a [MergedOverride] map for every property configured on this spec class for [suffix].
 *
 * - All [@FieldSpec] annotations contribute base values.
 * - [@FieldOverride] annotations whose [for_] array contains [suffix] override those base values
 *   and add output-kind-specific params (rename, validators, etc.).
 */
internal fun KSClassDeclaration.mergedFieldOverrides(
    suffix: String,
    logger: KSPLogger? = null,
): Map<String, MergedOverride> {
    val classFields: Map<String, KSAnnotation> = annotations
        .filter { it.shortName.asString() == AN_FIELD_SPEC }
        .associateBy { it.argString(PROP_PROPERTY) }

    val fieldSpecs: Map<String, KSAnnotation> = annotations
        .filter { it.shortName.asString() == AN_FIELD_OVERRIDE }
        .filter { ann ->
            val forList = ann.arguments.firstOrNull { it.name?.asString() == PROP_FOR }?.value as? List<*>
            forList?.filterIsInstance<String>()?.contains(suffix) == true
        }
        .associateBy { it.argString(PROP_PROPERTY) }

    val specName = qualifiedName?.asString() ?: simpleName.asString()
    val allProperties = classFields.keys + fieldSpecs.keys
    return allProperties.associateWith { property ->
        val cf = classFields[property]
        val fs = fieldSpecs[property]

        val specDefault = cf?.let {
            if (logger != null)
                it.readDefault(logger, "@FieldSpec(property = \"$property\") on $specName", allowClearInherited = false)
            else it.argDefault()
        } ?: DefaultConfig.SENTINEL
        val overrideDefault = fs?.let {
            if (logger != null)
                it.readDefault(logger, "@FieldOverride(for_=[…\"$suffix\"…], property = \"$property\") on $specName", allowClearInherited = true)
            else it.argDefault()
        } ?: DefaultConfig.SENTINEL

        // Whole-replacement layering: non-sentinel override wins; sentinel falls through to spec.
        val merged = if (!overrideDefault.isSentinel) overrideDefault else specDefault

        MergedOverride(
            property      = property,
            exclude       = fs?.argBool(PROP_EXCLUDE) ?: cf?.argBool(PROP_EXCLUDE) ?: false,
            nullable      = fs?.argNullableOverride() ?: cf?.argNullableOverride() ?: NullableOverride.UNSET,
            transformerFQN= fs?.argKClassFQN(PROP_TRANSFORMER) ?: cf?.argKClassFQN(PROP_TRANSFORMER),
            classLevelAnn = cf?.argAnnotationList() ?: emptyList(),
            fieldLevelAnn = fs?.argAnnotationList() ?: emptyList(),
            rename        = fs?.argString(PROP_RENAME) ?: "",
            validators    = fs?.argKClassList(PROP_VALIDATORS) ?: emptyList(),
            defaultConfig = merged,
        )
    }
}

// ---------------------------------------------------------------------------
// resolveWithBundles — merge spec field overrides with bundle field overrides
// ---------------------------------------------------------------------------

/**
 * Builds the merged override map for [suffix], combining the spec class's own
 * [mergedFieldOverrides] with any [@FieldBundle] classes listed in [bundleFQNs].
 *
 * Merge precedence is controlled by [mergeStrategy] ("SPEC_WINS", "BUNDLE_WINS", "MERGE_ADDITIVE").
 * Within the bundle layer, first-bundle-wins when multiple bundles define the same property.
 */
internal fun KSClassDeclaration.resolveWithBundles(
    suffix: String,
    bundleFQNs: List<String>,
    mergeStrategy: BundleMergeStrategy,
    bundleRegistry: BundleRegistry,
    logger: KSPLogger? = null,
): Map<String, MergedOverride> {
    val specOverrides = mergedFieldOverrides(suffix, logger)
    if (bundleFQNs.isEmpty()) return specOverrides

    // Build the bundle layer: first-bundle-wins for each property.
    // Expand bundle FQNs transitively (DFS pre-order) before iterating.
    val bundleLayer = mutableMapOf<String, MergedOverride>()
    for (bundleFQN in bundleRegistry.transitiveBundleFQNsFor(bundleFQNs)) {
        val bundleDecl = bundleRegistry.bundles[bundleFQN]
        if (bundleDecl == null) {
            // Error already reported during BundleRegistry.build — skip silently here.
            continue
        }
        val bundleOverrides = bundleDecl.mergedFieldOverrides(suffix, logger)
        for ((prop, override) in bundleOverrides) {
            bundleLayer.putIfAbsent(prop, override)
        }
    }

    if (bundleLayer.isEmpty()) return specOverrides

    return when (mergeStrategy) {
        BundleMergeStrategy.BUNDLE_WINS -> {
            val merged = bundleLayer.toMutableMap()
            for ((prop, override) in specOverrides) merged.putIfAbsent(prop, override)
            merged
        }
        BundleMergeStrategy.MERGE_ADDITIVE -> mergeAdditive(specOverrides, bundleLayer)
        BundleMergeStrategy.SPEC_WINS -> {
            val merged = specOverrides.toMutableMap()
            for ((prop, override) in bundleLayer) merged.putIfAbsent(prop, override)
            merged
        }
    }
}

/**
 * Merges spec and bundle overrides property-by-property, preferring non-default values from
 * [spec] and filling remaining defaults from [bundle].
 *
 * "Non-default" means: exclude=true, nullable != "UNSET", non-blank transformerFQN,
 * non-empty annotations, non-blank column/rename, non-empty validators.
 */
private fun mergeAdditive(
    spec: Map<String, MergedOverride>,
    bundle: Map<String, MergedOverride>,
): Map<String, MergedOverride> {
    val allKeys = spec.keys + bundle.keys
    return allKeys.associateWith { prop ->
        val s = spec[prop]
        val b = bundle[prop]
        if (s == null) return@associateWith b!!
        if (b == null) return@associateWith s
        MergedOverride(
	        property       = prop,
	        exclude        = if (s.exclude) true else b.exclude,
	        nullable       = if (s.nullable != NullableOverride.UNSET) s.nullable else b.nullable,
	        transformerFQN = s.transformerFQN ?: b.transformerFQN,
	        classLevelAnn  = s.classLevelAnn.ifEmpty { b.classLevelAnn },
	        fieldLevelAnn  = s.fieldLevelAnn.ifEmpty { b.fieldLevelAnn },
	        rename         = s.rename.ifBlank { b.rename },
	        validators     = s.validators.ifEmpty { b.validators },
	        defaultConfig  = if (!s.defaultConfig.isSentinel) s.defaultConfig else b.defaultConfig,
        )
    }
}

// ---------------------------------------------------------------------------
// customAnnotationSpecs — emit KotlinPoet AnnotationSpecs from CustomAnnotation arrays
// ---------------------------------------------------------------------------

/**
 * Reads the `annotations: Array<CustomAnnotation>` argument from this KSAnnotation and returns
 * a list of KotlinPoet [AnnotationSpec]s ready to attach to a class or property.
 *
 * [addImport] is called for each enum type that is resolved from a short name so the caller
 * can add the necessary import to the [FileSpec.Builder].
 */
internal fun KSAnnotation.customAnnotationSpecs(
    addImport: (pkg: String, name: String) -> Unit = { _, _ -> },
): List<AnnotationSpec> {
    val list = arguments.firstOrNull { it.name?.asString() == PROP_ANNOTATIONS }
        ?.value as? List<*> ?: return emptyList()
    return list.filterIsInstance<KSAnnotation>().mapNotNull { it.toAnnotationSpec(addImport) }
}

/**
 * Converts a single raw [KSAnnotation] that represents a [CustomAnnotation] instance
 * into a KotlinPoet [AnnotationSpec].
 *
 * Member strings follow the format `"paramName=value"`. Enum values may be short names
 * (e.g. `"fetch=LAZY"`); this function resolves them to FQN via KSP and calls [addImport].
 */
internal fun KSAnnotation.toAnnotationSpec(
    addImport: (pkg: String, name: String) -> Unit = { _, _ -> },
): AnnotationSpec? {
    val ksType = arguments.firstOrNull { it.name?.asString() == PROP_ANNOTATION }
        ?.value as? KSType ?: return null
    val decl = ksType.declaration
    val pkg = decl.packageName.asString()
    val cls = decl.simpleName.asString()
    val specBuilder = AnnotationSpec.builder(ClassName(pkg, cls))

    val members = arguments.firstOrNull { it.name?.asString() == PROP_MEMBERS }
        ?.value as? List<*> ?: emptyList<Any>()

    // Build param-name → KSP parameter mapping for enum resolution
    val annotationDecl = ksType.declaration as? KSClassDeclaration
    val paramTypes = annotationDecl?.primaryConstructor?.parameters
        ?.associateBy { it.name?.asString() ?: "" } ?: emptyMap()

    members.filterIsInstance<String>().forEach { memberStr ->
        val eqIdx = memberStr.indexOf('=')
        if (eqIdx < 0) {
            specBuilder.addMember(memberStr)
            return@forEach
        }
        val paramName = memberStr.substring(0, eqIdx).trim()
        val value = memberStr.substring(eqIdx + 1).trim()

        // Attempt enum resolution: look up param type → check if enum → find constant
        val paramKsType = paramTypes[paramName]?.type?.resolve()
        val enumDecl = paramKsType?.declaration as? KSClassDeclaration
        if (enumDecl != null && enumDecl.classKind == ClassKind.ENUM_CLASS) {
            val constantExists = enumDecl.declarations
                .filterIsInstance<KSClassDeclaration>()
                .any { it.classKind == ClassKind.ENUM_ENTRY && it.simpleName.asString() == value }
            if (constantExists) {
                val enumPkg = enumDecl.packageName.asString()
                val enumName = enumDecl.simpleName.asString()
                addImport(enumPkg, enumName)
                specBuilder.addMember("$paramName = %T.$value", ClassName(enumPkg, enumName))
                return@forEach
            }
        }
        // Fall back: emit verbatim (user supplied a literal or a FQN)
        specBuilder.addMember("$paramName = $value")
    }
    return specBuilder.build()
}

// ---------------------------------------------------------------------------
// addFieldAnnotations — collect @AddField instances on a spec class
// ---------------------------------------------------------------------------

/** Returns all [@AddField] annotation instances on this declaration (handles @Repeatable container). */
@Suppress("UNCHECKED_CAST")
internal fun KSClassDeclaration.addFieldAnnotations(): List<KSAnnotation> {
    val direct = annotations.filter { it.shortName.asString() == AN_ADD_FIELD }.toList()
    if (direct.isNotEmpty()) return direct
    val container = annotations.firstOrNull { it.shortName.asString() == AN_ADD_FIELDS }
    val nested = (container?.arguments?.firstOrNull()?.value as? List<*>)
        ?.filterIsInstance<KSAnnotation>() ?: emptyList()
    return nested
}

// ---------------------------------------------------------------------------
// Low-level KSAnnotation argument helpers
// ---------------------------------------------------------------------------

internal fun KSAnnotation.argString(name: String): String =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

internal fun KSAnnotation.argBool(name: String): Boolean =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean ?: false

internal fun KSAnnotation.argEnumName(name: String): String {
    val raw = arguments.firstOrNull { it.name?.asString() == name }?.value ?: return "UNSET"
    return when (raw) {
        is KSType -> raw.declaration.simpleName.asString()
        is KSClassDeclaration -> raw.simpleName.asString()
        else -> raw.toString().substringAfterLast('.')
    }
}

internal fun KSAnnotation.argKClassFQN(name: String): String? =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? KSType)
        ?.declaration?.qualifiedName?.asString()

internal fun KSAnnotation.argKClassList(name: String): List<KSType> =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
        ?.filterIsInstance<KSType>() ?: emptyList()

/** Returns the raw [KSAnnotation] list stored in an `annotations: Array<CustomAnnotation>` arg. */
internal fun KSAnnotation.argAnnotationList(): List<KSAnnotation> =
    (arguments.firstOrNull { it.name?.asString() == PROP_ANNOTATIONS }?.value as? List<*>)
        ?.filterIsInstance<KSAnnotation>() ?: emptyList()

internal fun KSAnnotation.argStringList(name: String): List<String> =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
        ?.filterIsInstance<String>() ?: emptyList()

// ---------------------------------------------------------------------------
// Default (nested annotation) reader
// ---------------------------------------------------------------------------

/**
 * Resolved view of a [Default] annotation argument. The no-arg `Default()` sentinel resolves to
 * [SENTINEL] — call [isSentinel] to test for "no default configured".
 */
data class DefaultConfig(
    val value: String,
    val inherit: Boolean,
    val clearInherited: Boolean,
) {
    val isSentinel: Boolean get() = value.isEmpty() && !inherit && !clearInherited

    companion object {
        val SENTINEL = DefaultConfig(value = "", inherit = false, clearInherited = false)
    }
}

/**
 * Reads the nested [Default] annotation stored under [propName] on this annotation. Returns
 * [DefaultConfig.SENTINEL] when the parameter is absent or carries the no-arg `Default()`.
 */
internal fun KSAnnotation.argDefault(propName: String = PROP_DEFAULT): DefaultConfig {
    val nested = arguments.firstOrNull { it.name?.asString() == propName }?.value as? KSAnnotation
        ?: return DefaultConfig.SENTINEL
    return DefaultConfig(
        value          = nested.argString(PROP_DEFAULT_VALUE),
        inherit        = nested.argBool(PROP_DEFAULT_INHERIT),
        clearInherited = nested.argBool(PROP_DEFAULT_CLEAR_INHERITED),
    )
}

/**
 * Reads + validates the nested [Default] annotation. Logs KSP errors and returns
 * [DefaultConfig.SENTINEL] on any violation so the caller can continue safely.
 *
 * @param allowClearInherited Pass `true` only when reading a `@FieldOverride`'s default.
 */
internal fun KSAnnotation.readDefault(
    logger: KSPLogger,
    site: String,
    allowClearInherited: Boolean,
): DefaultConfig {
    val cfg = argDefault()
    if (cfg.isSentinel) return cfg

    var ok = true

    if (cfg.value.isNotEmpty() && cfg.inherit) {
        logger.error("$site: @Default has both `value` and `inherit = true` — pick one.")
        ok = false
    }
    if (cfg.clearInherited && (cfg.value.isNotEmpty() || cfg.inherit)) {
        logger.error("$site: @Default `clearInherited = true` is mutually exclusive with `value`/`inherit`.")
        ok = false
    }
    if (cfg.clearInherited && !allowClearInherited) {
        logger.error("$site: @Default `clearInherited = true` is only valid on @FieldOverride.")
        ok = false
    }
    if (cfg.value.isNotEmpty() && !cfg.value.isBalancedDefaultExpression()) {
        logger.error("$site: @Default `value` is not a syntactically valid expression (unbalanced quotes/parens or trailing `;`): `${cfg.value}`")
        ok = false
    }
    return if (ok) cfg else DefaultConfig.SENTINEL
}

/**
 * Recovers the source default expression for a primary-constructor parameter by reading the
 * source file at the parameter's location. Returns `null` on any failure (no source, unreadable
 * file, no default present) and logs a descriptive error.
 *
 * Algorithm: from the parameter's start line, find the parameter name, skip the `:` and type
 * (with `< (`, `[`, `{` depth tracking), then if the next non-whitespace token is `=`, capture
 * the expression up to the next `,` or `)` at depth 0, respecting string/char literals.
 */
internal fun KSValueParameter.readSourceDefaultExpression(
    logger: KSPLogger,
    site: String,
): String? {
    if (!hasDefault) {
        logger.error("$site: @Default(inherit = true) but the source property has no default expression.")
        return null
    }
    val loc = location as? FileLocation ?: run {
        logger.error("$site: @Default(inherit = true) — source property has no readable source location (likely a compiled dependency). Use Default(value = \"…\") instead.")
        return null
    }
    val file = java.io.File(loc.filePath)
    if (!file.exists()) {
        logger.error("$site: @Default(inherit = true) — source file ${loc.filePath} not found.")
        return null
    }
    val src = file.readText()
    val paramName = name?.asString() ?: return null

    val startOffset = src.lines().take((loc.lineNumber - 1).coerceAtLeast(0))
        .sumOf { it.length + 1 }

    val match = Regex("\\b${Regex.escape(paramName)}\\b").find(src, startOffset) ?: run {
        logger.error("$site: @Default(inherit = true) — could not locate parameter `$paramName` in ${loc.filePath}.")
        return null
    }

    var i = match.range.last + 1
    while (i < src.length && src[i].isWhitespace()) i++
    if (i >= src.length || src[i] != ':') return null
    i++

    var depth = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '<' || c == '(' || c == '[' || c == '{' -> depth++
            c == '>' || c == ')' || c == ']' || c == '}' -> {
                if (depth == 0) return null   // closed param list without `=`
                depth--
            }
            c == '=' && depth == 0 -> {
                i++
                return extractDefaultExpression(src, i).trim()
            }
            c == ',' && depth == 0 -> return null
        }
        i++
    }
    return null
}

/**
 * Captures the default expression starting at [start], stopping at the next `,` or `)` at
 * depth 0. Respects string and char literals (including triple-quoted strings).
 */
private fun extractDefaultExpression(src: String, start: Int): String {
    var i = start
    while (i < src.length && src[i].isWhitespace()) i++
    val exprStart = i
    var depth = 0
    var inDouble = false
    var inSingle = false
    var inTriple = false
    while (i < src.length) {
        val c = src[i]
        when {
            inTriple -> {
                if (c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                    inTriple = false; i += 2
                }
            }
            inDouble -> when (c) {
                '\\' -> i++
                '"'  -> inDouble = false
            }
            inSingle -> when (c) {
                '\\' -> i++
                '\'' -> inSingle = false
            }
            else -> when {
                c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    inTriple = true; i += 2
                }
                c == '"'  -> inDouble = true
                c == '\'' -> inSingle = true
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    if (depth == 0) return src.substring(exprStart, i)
                    depth--
                }
                c == ',' && depth == 0 -> return src.substring(exprStart, i)
            }
        }
        i++
    }
    return src.substring(exprStart, i)
}

/**
 * Light syntactic check on a verbatim default expression: non-blank, balanced single/double
 * quotes and `()` / `[]` / `{}` pairs, no trailing `;`.
 *
 * Not a full parser — Kotlin's compiler will catch deeper mistakes when the generated file
 * compiles; this just localises common typos to the annotation site.
 */
internal fun String.isBalancedDefaultExpression(): Boolean {
    if (isBlank()) return false
    if (trimEnd().endsWith(";")) return false
    val stack = ArrayDeque<Char>()
    var i = 0
    var inDouble = false
    var inSingle = false
    var inTriple = false
    while (i < length) {
        val c = this[i]
        when {
            inTriple -> {
                if (c == '"' && i + 2 < length && this[i + 1] == '"' && this[i + 2] == '"') {
                    inTriple = false; i += 2
                }
            }
            inDouble -> when (c) {
                '\\' -> i++  // skip escaped char
                '"'  -> inDouble = false
            }
            inSingle -> when (c) {
                '\\' -> i++
                '\'' -> inSingle = false
            }
            else -> when {
                c == '"' && i + 2 < length && this[i + 1] == '"' && this[i + 2] == '"' -> {
                    inTriple = true; i += 2
                }
                c == '"'  -> inDouble = true
                c == '\'' -> inSingle = true
                c == '(' || c == '[' || c == '{' -> stack.addLast(c)
                c == ')' -> if (stack.removeLastOrNull() != '(') return false
                c == ']' -> if (stack.removeLastOrNull() != '[') return false
                c == '}' -> if (stack.removeLastOrNull() != '{') return false
            }
        }
        i++
    }
    return stack.isEmpty() && !inDouble && !inSingle && !inTriple
}

// ---------------------------------------------------------------------------
// Typed enum extractors — one per annotation enum param
// ---------------------------------------------------------------------------

private fun KSAnnotation.rawEnumName(propName: String): String? {
    val raw = arguments.firstOrNull { it.name?.asString() == propName }?.value ?: return null
    return when (raw) {
        is KSType -> raw.declaration.simpleName.asString()
        is KSClassDeclaration -> raw.simpleName.asString()
        else -> raw.toString().substringAfterLast('.')
    }
}

internal fun KSAnnotation.argNullableOverride(): NullableOverride =
    rawEnumName(PROP_NULLABLE)?.let { NullableOverride.valueOf(it) } ?: NullableOverride.UNSET

internal fun KSAnnotation.argBundleMergeStrategy(): BundleMergeStrategy =
    rawEnumName(PROP_BUNDLE_MERGE_STRATEGY)?.let { BundleMergeStrategy.valueOf(it) }
        ?: BundleMergeStrategy.SPEC_WINS

internal fun KSAnnotation.argUnmappedNestedStrategy(): UnmappedNestedStrategy =
    rawEnumName(PROP_UNMAPPED_NESTED)?.let { UnmappedNestedStrategy.valueOf(it) }
        ?: UnmappedNestedStrategy.FAIL

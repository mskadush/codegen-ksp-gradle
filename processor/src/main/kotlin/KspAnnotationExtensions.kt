import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName

// ---------------------------------------------------------------------------
// MergedOverride — consolidated view of ClassField + FieldSpec for one field
// ---------------------------------------------------------------------------

/**
 * Merged field configuration for a single domain property, combining a [ClassField]
 * (which applies to all outputs) with any [FieldSpec] scoped to a specific suffix.
 *
 * [FieldSpec] params win over [ClassField] for overlapping properties.
 */
data class MergedOverride(
    val property: String,
    val exclude: Boolean,
    val nullable: String,          // "UNSET" | "YES" | "NO"
    val transformerRef: String,
    val transformerFQN: String?,
    /** Raw CustomAnnotation KSAnnotations from @ClassField.annotations */
    val classLevelAnn: List<KSAnnotation>,
    /** Raw CustomAnnotation KSAnnotations from @FieldSpec.annotations */
    val fieldLevelAnn: List<KSAnnotation>,
    // entity-specific
    val column: String,
    val inline: Boolean,
    val inlinePrefix: String,
    // DTO-specific
    val rename: String,
    // request-specific
    val rules: List<KSType>,
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
 * - All [@ClassField] annotations contribute base values.
 * - [@FieldSpec] annotations whose [for_] array contains [suffix] override those base values
 *   and add output-kind-specific params (column, rename, rules, etc.).
 */
@Suppress("UNCHECKED_CAST")
internal fun KSClassDeclaration.mergedFieldOverrides(suffix: String): Map<String, MergedOverride> {
    val classFields: Map<String, KSAnnotation> = annotations
        .filter { it.shortName.asString() == "ClassField" }
        .associateBy { it.argString("property") }

    val fieldSpecs: Map<String, KSAnnotation> = annotations
        .filter { it.shortName.asString() == "FieldSpec" }
        .filter { ann ->
            val forList = ann.arguments.firstOrNull { it.name?.asString() == "for_" }?.value as? List<*>
            forList?.filterIsInstance<String>()?.contains(suffix) == true
        }
        .associateBy { it.argString("property") }

    val allProperties = classFields.keys + fieldSpecs.keys
    return allProperties.associateWith { property ->
        val cf = classFields[property]
        val fs = fieldSpecs[property]
        MergedOverride(
            property      = property,
            exclude       = fs?.argBool("exclude") ?: cf?.argBool("exclude") ?: false,
            nullable      = fs?.argEnumName("nullable") ?: cf?.argEnumName("nullable") ?: "UNSET",
            transformerRef= fs?.argString("transformerRef") ?: cf?.argString("transformerRef") ?: "",
            transformerFQN= fs?.argKClassFQN("transformer") ?: cf?.argKClassFQN("transformer"),
            classLevelAnn = cf?.argAnnotationList() ?: emptyList(),
            fieldLevelAnn = fs?.argAnnotationList() ?: emptyList(),
            column        = fs?.argString("column") ?: "",
            inline        = fs?.argBool("inline") ?: false,
            inlinePrefix  = fs?.argString("inlinePrefix") ?: "",
            rename        = fs?.argString("rename") ?: "",
            rules         = fs?.argKClassList("rules") ?: emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------
// resolveWithBundles — merge spec field overrides with bundle field overrides
// ---------------------------------------------------------------------------

/**
 * Builds the merged override map for [suffix], combining the spec class's own
 * [mergedFieldOverrides] with any [@FieldBundle] classes listed in [bundleNames].
 *
 * Merge precedence is controlled by [mergeStrategy] ("SPEC_WINS", "BUNDLE_WINS", "MERGE_ADDITIVE").
 * Within the bundle layer, first-bundle-wins when multiple bundles define the same property.
 */
internal fun KSClassDeclaration.resolveWithBundles(
    suffix: String,
    bundleNames: List<String>,
    mergeStrategy: String,
    bundleRegistry: BundleRegistry,
    logger: KSPLogger,
): Map<String, MergedOverride> {
    val specOverrides = mergedFieldOverrides(suffix)
    if (bundleNames.isEmpty()) return specOverrides

    // Build the bundle layer: first-bundle-wins for each property
    val bundleLayer = mutableMapOf<String, MergedOverride>()
    for (bundleName in bundleNames) {
        val bundleDecl = bundleRegistry.bundles[bundleName]
        if (bundleDecl == null) {
            // Error already reported by PASS 1d validatePropertyRefs — skip silently here.
            continue
        }
        val bundleOverrides = bundleDecl.mergedFieldOverrides(suffix)
        for ((prop, override) in bundleOverrides) {
            bundleLayer.putIfAbsent(prop, override)
        }
    }

    if (bundleLayer.isEmpty()) return specOverrides

    return when (mergeStrategy) {
        "BUNDLE_WINS" -> {
            val merged = bundleLayer.toMutableMap()
            for ((prop, override) in specOverrides) merged.putIfAbsent(prop, override)
            merged
        }
        "MERGE_ADDITIVE" -> mergeAdditive(specOverrides, bundleLayer)
        else -> { // SPEC_WINS (default)
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
 * "Non-default" means: exclude=true, nullable != "UNSET", non-blank transformerRef/transformerFQN,
 * non-empty annotations, non-blank column/rename, non-empty rules.
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
            exclude        = if (s.exclude) s.exclude else b.exclude,
            nullable       = if (s.nullable != "UNSET") s.nullable else b.nullable,
            transformerRef = s.transformerRef.ifBlank { b.transformerRef },
            transformerFQN = s.transformerFQN ?: b.transformerFQN,
            classLevelAnn  = s.classLevelAnn.ifEmpty { b.classLevelAnn },
            fieldLevelAnn  = s.fieldLevelAnn.ifEmpty { b.fieldLevelAnn },
            column         = s.column.ifBlank { b.column },
            inline         = if (s.inline) s.inline else b.inline,
            inlinePrefix   = s.inlinePrefix.ifBlank { b.inlinePrefix },
            rename         = s.rename.ifBlank { b.rename },
            rules          = s.rules.ifEmpty { b.rules },
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
@Suppress("UNCHECKED_CAST")
internal fun KSAnnotation.customAnnotationSpecs(
    addImport: (pkg: String, name: String) -> Unit = { _, _ -> },
): List<AnnotationSpec> {
    val list = arguments.firstOrNull { it.name?.asString() == "annotations" }
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
@Suppress("UNCHECKED_CAST")
internal fun KSAnnotation.toAnnotationSpec(
    addImport: (pkg: String, name: String) -> Unit = { _, _ -> },
): AnnotationSpec? {
    val ksType = arguments.firstOrNull { it.name?.asString() == "annotation" }
        ?.value as? KSType ?: return null
    val decl = ksType.declaration
    val pkg = decl.packageName.asString()
    val cls = decl.simpleName.asString()
    val specBuilder = AnnotationSpec.builder(ClassName(pkg, cls))

    val members = arguments.firstOrNull { it.name?.asString() == "members" }
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

@Suppress("UNCHECKED_CAST")
internal fun KSAnnotation.argKClassList(name: String): List<KSType> =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
        ?.filterIsInstance<KSType>() ?: emptyList()

/** Returns the raw [KSAnnotation] list stored in an `annotations: Array<CustomAnnotation>` arg. */
@Suppress("UNCHECKED_CAST")
internal fun KSAnnotation.argAnnotationList(): List<KSAnnotation> =
    (arguments.firstOrNull { it.name?.asString() == "annotations" }?.value as? List<*>)
        ?.filterIsInstance<KSAnnotation>() ?: emptyList()

@Suppress("UNCHECKED_CAST")
internal fun KSAnnotation.argStringList(name: String): List<String> =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
        ?.filterIsInstance<String>() ?: emptyList()

import za.skadush.codegen.gradle.annotations.BundleMergeStrategy
import za.skadush.codegen.gradle.annotations.NullableOverride
import za.skadush.codegen.gradle.annotations.UnmappedNestedStrategy
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
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
internal fun KSClassDeclaration.mergedFieldOverrides(suffix: String): Map<String, MergedOverride> {
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

    val allProperties = classFields.keys + fieldSpecs.keys
    return allProperties.associateWith { property ->
        val cf = classFields[property]
        val fs = fieldSpecs[property]
        MergedOverride(
            property      = property,
            exclude       = fs?.argBool(PROP_EXCLUDE) ?: cf?.argBool(PROP_EXCLUDE) ?: false,
            nullable      = fs?.argNullableOverride() ?: cf?.argNullableOverride() ?: NullableOverride.UNSET,
            transformerFQN= fs?.argKClassFQN(PROP_TRANSFORMER) ?: cf?.argKClassFQN(PROP_TRANSFORMER),
            classLevelAnn = cf?.argAnnotationList() ?: emptyList(),
            fieldLevelAnn = fs?.argAnnotationList() ?: emptyList(),
            rename        = fs?.argString(PROP_RENAME) ?: "",
            validators    = fs?.argKClassList(PROP_VALIDATORS) ?: emptyList(),
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
): Map<String, MergedOverride> {
    val specOverrides = mergedFieldOverrides(suffix)
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
        val bundleOverrides = bundleDecl.mergedFieldOverrides(suffix)
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

import com.example.annotations.BundleMergeStrategy
import com.example.annotations.UnmappedNestedStrategy
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

// ---------------------------------------------------------------------------
// Typed model for @ClassSpec
// ---------------------------------------------------------------------------

/**
 * Typed view of a single [@ClassSpec] annotation instance.
 *
 * Parsed once at the boundary from a raw [KSAnnotation]; all downstream processor code works
 * with this typed struct instead of calling raw argument helpers at each use site.
 *
 * [bundleFQNs] holds the fully-qualified class names of every [@FieldBundle] class referenced
 * in [@ClassSpec.bundles], derived from the KClass arguments at parse time.
 */
data class ClassSpecModel(
    val domainClass: KSClassDeclaration,
    val suffix: String,
    val prefix: String,
    val partial: Boolean,
    val validateOnConstruct: Boolean,
    val bundleFQNs: List<String>,
    val mergeStrategy: BundleMergeStrategy,
    val unmappedStrategy: UnmappedNestedStrategy,
    val classAnnotations: List<KSAnnotation>,
) {
    val outputName: String get() = "$prefix${domainClass.simpleName.asString()}$suffix"
    val domainName: String get() = domainClass.simpleName.asString()
    val domainPackage: String get() = domainClass.packageName.asString()
}

/** Parses a raw [@ClassSpec] [KSAnnotation] into a [ClassSpecModel]. */
internal fun KSAnnotation.toClassSpecModel(): ClassSpecModel {
    val domainClass = (arguments.first { it.name?.asString() == PROP_FOR }.value as KSType)
        .declaration as KSClassDeclaration
    return ClassSpecModel(
        domainClass         = domainClass,
        suffix              = argString(PROP_SUFFIX),
        prefix              = argString(PROP_PREFIX),
        partial             = argBool(PROP_PARTIAL),
        validateOnConstruct = argBool(PROP_VALIDATE_ON_CONSTRUCT),
        bundleFQNs          = argKClassList(PROP_BUNDLES).mapNotNull { it.declaration.qualifiedName?.asString() },
        mergeStrategy       = argBundleMergeStrategy(),
        unmappedStrategy    = argUnmappedNestedStrategy(),
        classAnnotations    = argAnnotationList(),
    )
}

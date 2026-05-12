import za.skadush.codegen.gradle.annotations.ClassSpec
import za.skadush.codegen.gradle.annotations.CustomAnnotation
import za.skadush.codegen.gradle.annotations.AddField
import za.skadush.codegen.gradle.annotations.Default
import za.skadush.codegen.gradle.annotations.FieldBundle
import za.skadush.codegen.gradle.annotations.FieldOverride
import za.skadush.codegen.gradle.annotations.FieldSpec
import za.skadush.codegen.gradle.annotations.IncludeBundles
import za.skadush.codegen.gradle.annotations.NoOpTransformer

// ── Annotation short names (for shortName.asString() comparisons) ─────────────
internal val AN_CLASS_SPEC           = ClassSpec::class.simpleName!!
internal val AN_CLASS_SPECS          = ClassSpec::class.simpleName!! + "s"   // @Repeatable container
internal val AN_FIELD_SPEC           = FieldSpec::class.simpleName!!
internal val AN_FIELD_OVERRIDE       = FieldOverride::class.simpleName!!
internal val AN_FIELD_BUNDLE         = FieldBundle::class.simpleName!!
internal val AN_INCLUDE_BUNDLES      = IncludeBundles::class.simpleName!!

// ── Annotation FQNs (for getSymbolsWithAnnotation() calls) ────────────────────
internal val FQN_CLASS_SPEC           = ClassSpec::class.qualifiedName!!
internal val FQN_FIELD_BUNDLE         = FieldBundle::class.qualifiedName!!
internal val FQN_NO_OP_TRANSFORMER    = NoOpTransformer::class.qualifiedName!!

// ── ClassSpec property names ──────────────────────────────────────────────────
internal val PROP_FOR                   = ClassSpec::for_.name
internal val PROP_SUFFIX                = ClassSpec::suffix.name
internal val PROP_PREFIX                = ClassSpec::prefix.name
internal val PROP_PARTIAL               = ClassSpec::partial.name
internal val PROP_BUNDLES               = ClassSpec::bundles.name
internal val PROP_BUNDLE_MERGE_STRATEGY = ClassSpec::bundleMergeStrategy.name
internal val PROP_UNMAPPED_NESTED       = ClassSpec::unmappedNestedStrategy.name
internal val PROP_ANNOTATIONS           = ClassSpec::annotations.name
internal val PROP_VALIDATE_ON_CONSTRUCT = ClassSpec::validateOnConstruct.name
internal val PROP_CLASS_SPEC_VALIDATORS = ClassSpec::validators.name
internal val PROP_OUTPUT_PACKAGE        = ClassSpec::outputPackage.name
internal val PROP_CLASS_SPEC_EXCLUDE    = ClassSpec::exclude.name

// ── ObjectValidator FQN (for bound checks) ───────────────────────────────────
internal const val FQN_OBJECT_VALIDATOR = "za.skadush.codegen.gradle.runtime.ObjectValidator"

// ── @FieldSpec / @FieldOverride shared property names ─────────────────────────
internal val PROP_PROPERTY        = FieldSpec::property.name
internal val PROP_EXCLUDE         = FieldSpec::exclude.name
internal val PROP_NULLABLE        = FieldSpec::nullable.name
internal val PROP_TRANSFORMER     = FieldSpec::transformer.name

// ── @FieldOverride-only property names ────────────────────────────────────────
internal val PROP_RENAME      = FieldOverride::rename.name
internal val PROP_VALIDATORS  = FieldOverride::validators.name

// ── CustomAnnotation property names ───────────────────────────────────────────
internal val PROP_ANNOTATION = CustomAnnotation::annotation.name
internal val PROP_MEMBERS    = CustomAnnotation::members.name

// ── IncludeBundles property name ──────────────────────────────────────────────
internal val PROP_INCLUDE_BUNDLES = IncludeBundles::bundles.name  // "bundles"

// ── AddField annotation short names + FQN ────────────────────────────────────
internal val AN_ADD_FIELD  = AddField::class.simpleName!!
internal val AN_ADD_FIELDS = AddField::class.simpleName!! + "s"  // @Repeatable container
internal val FQN_ADD_FIELD = AddField::class.qualifiedName!!

// ── AddField property names ───────────────────────────────────────────────────
internal val PROP_ADD_NAME     = AddField::name.name
internal val PROP_ADD_TYPE     = AddField::type.name
internal val PROP_ADD_NULLABLE = AddField::nullable.name
internal val PROP_ADD_DEFAULT  = AddField::default.name

// ── Default (nested annotation) property names + shared name ─────────────────
internal val PROP_DEFAULT          = FieldSpec::default.name      // "default"
internal val PROP_DEFAULT_VALUE    = Default::value.name
internal val PROP_DEFAULT_INHERIT  = Default::inherit.name
internal val PROP_DEFAULT_CLEAR_INHERITED = Default::clearInherited.name

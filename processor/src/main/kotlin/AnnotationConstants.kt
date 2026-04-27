import za.skadush.codegen.gradle.annotations.ClassField
import za.skadush.codegen.gradle.annotations.ClassSpec
import za.skadush.codegen.gradle.annotations.CustomAnnotation
import za.skadush.codegen.gradle.annotations.AddField
import za.skadush.codegen.gradle.annotations.FieldBundle
import za.skadush.codegen.gradle.annotations.FieldSpec
import za.skadush.codegen.gradle.annotations.IncludeBundles
import za.skadush.codegen.gradle.annotations.NoOpTransformer
import za.skadush.codegen.gradle.annotations.RegisterTransformer
import za.skadush.codegen.gradle.annotations.TransformerRegistry

// ── Annotation short names (for shortName.asString() comparisons) ─────────────
internal val AN_CLASS_SPEC           = ClassSpec::class.simpleName!!
internal val AN_CLASS_SPECS          = ClassSpec::class.simpleName!! + "s"   // @Repeatable container
internal val AN_CLASS_FIELD          = ClassField::class.simpleName!!
internal val AN_FIELD_SPEC           = FieldSpec::class.simpleName!!
internal val AN_FIELD_BUNDLE         = FieldBundle::class.simpleName!!
internal val AN_INCLUDE_BUNDLES      = IncludeBundles::class.simpleName!!
internal val AN_REGISTER_TRANSFORMER = RegisterTransformer::class.simpleName!!

// ── Annotation FQNs (for getSymbolsWithAnnotation() calls) ────────────────────
internal val FQN_CLASS_SPEC           = ClassSpec::class.qualifiedName!!
internal val FQN_FIELD_BUNDLE         = FieldBundle::class.qualifiedName!!
internal val FQN_TRANSFORMER_REGISTRY = TransformerRegistry::class.qualifiedName!!
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
internal val PROP_OUTPUT_PACKAGE        = ClassSpec::outputPackage.name

// ── ClassField / FieldSpec shared property names ──────────────────────────────
internal val PROP_PROPERTY        = ClassField::property.name
internal val PROP_EXCLUDE         = ClassField::exclude.name
internal val PROP_NULLABLE        = ClassField::nullable.name
internal val PROP_TRANSFORMER     = ClassField::transformer.name
internal val PROP_TRANSFORMER_REF = ClassField::transformerRef.name

// ── FieldSpec-only property names ─────────────────────────────────────────────
internal val PROP_RENAME      = FieldSpec::rename.name
internal val PROP_VALIDATORS  = FieldSpec::validators.name

// ── CustomAnnotation property names ───────────────────────────────────────────
internal val PROP_ANNOTATION = CustomAnnotation::annotation.name
internal val PROP_MEMBERS    = CustomAnnotation::members.name

// ── RegisterTransformer property name ─────────────────────────────────────────
internal val PROP_NAME = RegisterTransformer::name.name  // "name"

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
internal val PROP_ADD_DEFAULT  = AddField::defaultValue.name


import com.example.annotations.ClassField
import com.example.annotations.ClassSpec
import com.example.annotations.CustomAnnotation
import com.example.annotations.FieldBundle
import com.example.annotations.FieldSpec
import com.example.annotations.IncludeBundles
import com.example.annotations.NoOpTransformer
import com.example.annotations.RegisterTransformer
import com.example.annotations.RuleExpression
import com.example.annotations.TransformerRegistry

// ── Annotation short names (for shortName.asString() comparisons) ─────────────
internal val AN_CLASS_SPEC           = ClassSpec::class.simpleName!!
internal val AN_CLASS_SPECS          = ClassSpec::class.simpleName!! + "s"   // @Repeatable container
internal val AN_CLASS_FIELD          = ClassField::class.simpleName!!
internal val AN_FIELD_SPEC           = FieldSpec::class.simpleName!!
internal val AN_FIELD_BUNDLE         = FieldBundle::class.simpleName!!
internal val AN_INCLUDE_BUNDLES      = IncludeBundles::class.simpleName!!
internal val AN_REGISTER_TRANSFORMER = RegisterTransformer::class.simpleName!!
internal val AN_RULE_EXPRESSION      = RuleExpression::class.simpleName!!

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

// ── ClassField / FieldSpec shared property names ──────────────────────────────
internal val PROP_PROPERTY        = ClassField::property.name
internal val PROP_EXCLUDE         = ClassField::exclude.name
internal val PROP_NULLABLE        = ClassField::nullable.name
internal val PROP_TRANSFORMER     = ClassField::transformer.name
internal val PROP_TRANSFORMER_REF = ClassField::transformerRef.name

// ── FieldSpec-only property names ─────────────────────────────────────────────
internal val PROP_RENAME = FieldSpec::rename.name
internal val PROP_RULES  = FieldSpec::rules.name

// ── CustomAnnotation property names ───────────────────────────────────────────
internal val PROP_ANNOTATION = CustomAnnotation::annotation.name
internal val PROP_MEMBERS    = CustomAnnotation::members.name

// ── FieldBundle / IncludeBundles property names ───────────────────────────────
internal val PROP_NAME  = FieldBundle::name.name      // "name"
internal val PROP_NAMES = IncludeBundles::names.name  // "names"

// ── RuleExpression property names ─────────────────────────────────────────────
internal val PROP_EXPRESSION = RuleExpression::expression.name

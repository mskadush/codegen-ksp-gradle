package com.example.annotations

import kotlin.reflect.KClass

/**
 * Marks a spec class to drive generation of a database entity from a domain class.
 *
 * The processor reads this annotation at compile time and generates a new `data class`
 * named `<DomainClass>Entity` whose fields mirror the domain class's primary constructor.
 * Place zero or more [EntityField] annotations on the same class to override individual
 * field mappings.
 *
 * Example:
 * ```kotlin
 * @EntitySpec(for_ = User::class, table = "users", schema = "public")
 * @EntityField(property = "createdAt", column = "created_at")
 * class UserEntitySpec
 * ```
 *
 * @param for_ The domain class to generate an entity from.
 * @param table Database table name; defaults to the domain class name (snake_cased) when blank.
 * @param schema Database schema; uses the datasource default when blank.
 * @param bundles Names of [EntityBundle] classes whose field configs are merged into this spec.
 * @param bundleMergeStrategy How to resolve conflicts between this spec's [EntityField]s and bundle fields.
 * @param unmappedNestedStrategy What to do when a nested domain type has no explicit field mapping.
 * @param missingRelationStrategy What to do when a field looks like a relation but has no [Relation] config.
 * @param annotations Extra annotations to emit on the generated entity class (e.g. `@Table`).
 * @param indexes Database indexes to create on the generated entity's table.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class EntitySpec(
    val for_: KClass<*>,
    val table: String = "",
    val schema: String = "",
    val bundles: Array<String> = [],
    val bundleMergeStrategy: BundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    val unmappedNestedStrategy: UnmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    val missingRelationStrategy: MissingRelationStrategy = MissingRelationStrategy.FAIL,
    val annotations: Array<DbAnnotation> = [],
    val indexes: Array<Index> = []
)

/**
 * Overrides the mapping of a single field during entity generation.
 *
 * Apply this annotation (repeatable) on the same spec class as [EntitySpec] to customise
 * how individual properties from the domain class appear in the generated entity.
 *
 * @param property Name of the property in the domain class to configure.
 * @param column Database column name; defaults to the property name (snake_cased) when blank.
 * @param exclude When `true`, the field is omitted from the generated entity.
 * @param nullable Overrides the field's nullability; [NullableOverride.UNSET] preserves the source type.
 * @param transformer Class-reference to a [FieldTransformer] for value conversion.
 * @param transformerRef Name of a transformer registered via [RegisterTransformer]; takes precedence over [transformer].
 * @param relation ORM relationship configuration for association fields.
 * @param annotations Extra annotations to emit on the generated field (e.g. `@Column`, `@JoinColumn`).
 * @param inline When `true`, flattens a nested domain object's fields into this entity.
 * @param inlinePrefix Prefix prepended to inlined field names to avoid collisions.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class EntityField(
    val property: String,
    val column: String = "",
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val transformerRef: String = "",
    val relation: Relation = Relation(RelationType.NONE),
    val annotations: Array<DbAnnotation> = [],
    val inline: Boolean = false,
    val inlinePrefix: String = ""
)

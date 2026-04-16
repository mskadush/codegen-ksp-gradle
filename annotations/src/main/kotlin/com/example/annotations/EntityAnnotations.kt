package com.example.annotations

import kotlin.reflect.KClass

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

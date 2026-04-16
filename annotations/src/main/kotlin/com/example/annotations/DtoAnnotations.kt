package com.example.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DtoSpec(
    val for_: KClass<*>,
    val suffix: String = "Dto",
    val prefix: String = "",
    val bundles: Array<String> = [],
    val bundleMergeStrategy: BundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    val unmappedNestedStrategy: UnmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    val excludedFieldStrategy: ExcludedFieldStrategy = ExcludedFieldStrategy.USE_DEFAULT
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class DtoField(
    val property: String,
    val rename: String = "",
    val exclude: Boolean = false,
    val nullable: NullableOverride = NullableOverride.UNSET,
    val transformer: KClass<out FieldTransformer<*, *>> = NoOpTransformer::class,
    val transformerRef: String = ""
)

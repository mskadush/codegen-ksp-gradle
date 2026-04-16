package com.example.annotations

enum class NullableOverride { UNSET, YES, NO }
enum class BundleMergeStrategy { SPEC_WINS, BUNDLE_WINS, MERGE_ADDITIVE }
enum class UnmappedNestedStrategy { FAIL, INLINE, EXCLUDE }
enum class MissingRelationStrategy { FAIL, INFER }
enum class ExcludedFieldStrategy { USE_DEFAULT, REQUIRE_MANUAL, NULLABLE_OVERRIDE }
enum class RelationType { NONE, ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }
enum class CascadeType { ALL, PERSIST, MERGE, REMOVE, REFRESH }
enum class FetchType { LAZY, EAGER }

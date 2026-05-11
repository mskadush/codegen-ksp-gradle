package za.skadush.codegen.gradle.annotations

import kotlin.reflect.KClass

/**
 * Drives generation of a single output class from a domain type.
 *
 * Apply multiple `@ClassSpec` annotations to the same spec class to generate several output
 * classes from one domain type. Each instance is uniquely identified by [suffix], which is
 * also used by [FieldOverride.for_] to scope field overrides to a specific output class.
 *
 * **Output-kind inference** (processor):
 * - Any [FieldOverride] scoped to this suffix has non-empty [FieldOverride.validators] → a `validate()` /
 *   `validateOrThrow()` pair is emitted on the class.
 * - [partial] = `true` → all fields are nullable with `= null` defaults (update-request style).
 * - Otherwise → plain data class with bidirectional mapper functions (`to<Suffix>()`/`toDomain()`).
 *
 * **Example**:
 * ```kotlin
 * @ClassSpec(for_ = User::class, suffix = "Entity",
 *            bundles = [TimestampsBundle::class, UserEntityBundle::class],
 *            bundleMergeStrategy = BundleMergeStrategy.MERGE_ADDITIVE,
 *            annotations = [CustomAnnotation(Entity::class),
 *                           CustomAnnotation(Table::class, members = ["name=\"users\""])])
 * @ClassSpec(for_ = User::class, suffix = "Response")
 * @ClassSpec(for_ = User::class, suffix = "CreateRequest", bundles = [TimestampsBundle::class])
 * @ClassSpec(for_ = User::class, suffix = "UpdateRequest", partial = true)
 * class UserSpec
 * ```
 *
 * @param for_                   Domain class to generate from.
 * @param suffix                 Appended to the domain class name to form the output class name.
 *                               Also used as the discriminator by [FieldOverride.for_].
 * @param prefix                 Prepended to the domain class name.
 * @param partial                When `true`, every generated field is nullable with `= null`.
 * @param bundles                [@FieldBundle] classes whose field configs are merged into this spec.
 * @param bundleMergeStrategy    How to resolve conflicts when spec and bundle configure the same field.
 * @param unmappedNestedStrategy What to do when a nested domain type has no explicit mapping.
 * @param annotations            Annotations forwarded verbatim to the generated class.
 * @param validateOnConstruct    When `true`, emits an `init { validateOrThrow() }` block so the
 *                               object is validated immediately on construction. Useful when
 *                               deserialisation frameworks call the constructor directly.
 * @param validators             Cross-field validators applied to the generated class. Each entry
 *                               must be a singleton `object` implementing
 *                               `za.skadush.codegen.gradle.runtime.ObjectValidator<GeneratedClass>`,
 *                               where `GeneratedClass` is the output class produced by this spec
 *                               (i.e. `prefix + domainName + suffix`). Object validators run in
 *                               declaration order **after** all field validators in the generated
 *                               `validate()` method. The processor verifies the type argument; a
 *                               mismatch fails compilation with a clear KSP error.
 *                               Loose-typed as `KClass<*>` here because the annotations module
 *                               must not depend on the runtime module where `ObjectValidator` lives.
 * @param outputPackage          Package for the generated class and its mapper file.
 *                               Precedence: this value → `codegen.defaultPackage` KSP option →
 *                               domain class package (existing behaviour when both are unset).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class ClassSpec(
    val for_: KClass<*>,
    val suffix: String = "",
    val prefix: String = "",
    val partial: Boolean = false,
    val bundles: Array<KClass<*>> = [],
    val bundleMergeStrategy: BundleMergeStrategy = BundleMergeStrategy.SPEC_WINS,
    val unmappedNestedStrategy: UnmappedNestedStrategy = UnmappedNestedStrategy.FAIL,
    val annotations: Array<CustomAnnotation> = [],
    val validateOnConstruct: Boolean = false,
    val validators: Array<KClass<*>> = [],
    val outputPackage: String = "",
)


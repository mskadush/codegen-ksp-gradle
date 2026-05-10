package za.skadush.codegen.gradle.annotations

/**
 * Marks a class as a bundle of field configurations that can be shared
 * across multiple [@ClassSpec] specs.
 *
 * A bundle class carries the same [@FieldSpec] and [@FieldOverride] annotations as a spec class,
 * but without [@ClassSpec]. Field overrides inside a bundle are scoped to specific output kinds
 * via [@FieldOverride.for_] exactly as they are in any spec class.
 *
 * **Why this exists.** Bundles are the standardisation unit for cross-cutting concerns that show
 * up in every domain class — the canonical use cases are a `DocumentBundle` for persistence
 * documents (audit columns, tenant id, soft-delete flags, JPA/Mongo annotations) and a
 * `DtoBundle` for request/response models (validation annotations, JSON naming, exclusion of
 * server-managed fields). Without `@FieldBundle`, a class becomes a "bundle" only by being
 * referenced from `@ClassSpec.bundles`, and any class — including ones that were never intended
 * as a bundle — would silently be accepted. The marker makes the contract explicit and lets the
 * processor reject typos like `bundles = [User::class]` early with a precise error.
 *
 * Reference the bundle by class in [@ClassSpec.bundles]:
 * ```kotlin
 * @ClassSpec(for_ = User::class, suffix = "Entity",
 *            bundles = [DocumentBundle::class, DtoBundle::class])
 * ```
 *
 * **Example — `DocumentBundle`** standardises persistence concerns across every entity:
 * ```kotlin
 * @FieldBundle
 * @FieldOverride(for_ = ["Entity"], property = "createdAt", annotations = [
 *     CustomAnnotation(jakarta.persistence.Column::class,
 *                      members = ["name=\"created_at\"", "nullable=false", "updatable=false"])
 * ])
 * @FieldOverride(for_ = ["Entity"], property = "tenantId", annotations = [
 *     CustomAnnotation(jakarta.persistence.Column::class, members = ["name=\"tenant_id\""])
 * ])
 * object DocumentBundle
 * ```
 *
 * **Example — `DtoBundle`** standardises request/response shape across every endpoint:
 * ```kotlin
 * @FieldBundle
 * @FieldOverride(for_ = ["CreateRequest", "UpdateRequest"], property = "createdAt", exclude = true)
 * @FieldOverride(for_ = ["CreateRequest", "UpdateRequest"], property = "updatedAt", exclude = true)
 * @FieldOverride(for_ = ["CreateRequest"], property = "id", exclude = true)
 * object DtoBundle
 * ```
 *
 * Pulling both into a spec gives every domain class consistent persistence and DTO conventions
 * without repeating the field overrides on each `@ClassSpec`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class FieldBundle
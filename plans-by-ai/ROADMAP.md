# Project Roadmap

## 🔵 In Progress

## 📋 Backlog

## ✅ Done
- [026-cross-field-validation](026-cross-field-validation.md) — `ObjectValidator<T>` + `@ClassSpec.validators` for cross-field rules; validator chooses per-field error attribution via `ValidationContext.error(...)`
- [021-enum-fields-as-primitives](021-enum-fields-as-primitives.md) — Treat enum fields as primitive pass-throughs in `ClassResolver.classifyField()`; prevents FAIL/EXCLUDE/INLINE misclassification of custom enum field types
- [025-rename-field-annotations](025-rename-field-annotations.md) — Rename `@ClassField` → `@FieldSpec` (default field config) and `@FieldSpec` → `@FieldOverride` (per-output override) so public annotations follow the `@…Spec` pattern
- [023-recursive-class-spec-generation](023-recursive-class-spec-generation.md) — Add AUTO_GENERATE to UnmappedNestedStrategy; recursively generate passthrough output classes for unregistered nested domain types
- [022-package-config-and-migration](022-package-config-and-migration.md) — Configurable output package for generated files (`outputPackage` on `@ClassSpec`, `codegen.defaultPackage` KSP option) + full package migration `com.example` → `za.skadush.codegen.gradle`
- [020-deployment-paths](020-deployment-paths.md) — Maven Local + GitHub Packages publishing for annotations, runtime, and processor via workflow_dispatch

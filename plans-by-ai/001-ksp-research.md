# Amper v0.10.0 KSP processor project: configuration facts for Stage 1

**Amper natively supports KSP with first-class `module.yaml` syntax, including local processor module wiring — no Gradle interop required.** The exact four-module layout you need (annotations, processor, runtime, sample) maps cleanly onto Amper's standalone build model. Amper v0.10.0, released **March 31, 2026**, is confirmed as the latest release. It ships with **Kotlin 2.3.20** and **KSP 2.3.6** as defaults, and exclusively supports **KSP2** (KSP1 is not available). Amper remains **experimental (pre-alpha)**, but KSP support has been stable since v0.5.0 and has been refined across five subsequent releases.

---

## Module layout maps directly to Amper's conventions

Each module is a directory containing a `module.yaml` file alongside `src/` and `test/` directories. The project root contains `project.yaml` listing all modules. Here's the exact disk layout for your domain-mapping processor:

```
domain-mapper/
├─ annotations/
│  ├─ src/
│  │  └─ com/example/mapper/annotations/  # @DomainMap, @MapField, etc.
│  ├─ test/
│  └─ module.yaml
├─ processor/
│  ├─ src/
│  │  └─ com/example/mapper/processor/    # SymbolProcessor + Provider
│  ├─ resources/
│  │  └─ META-INF/services/
│  │     └─ com.google.devtools.ksp.processing.SymbolProcessorProvider
│  ├─ test/
│  └─ module.yaml
├─ runtime/
│  ├─ src/
│  │  └─ com/example/mapper/runtime/      # Generated code helpers, base classes
│  ├─ test/
│  └─ module.yaml
├─ sample/
│  ├─ src/
│  │  └─ com/example/mapper/sample/       # Annotated domain models + usage
│  ├─ test/
│  └─ module.yaml
├─ amper              # CLI wrapper (checked into repo)
├─ amper.bat
└─ project.yaml
```

Amper's default "flat" layout puts Kotlin and Java files together in `src/` with no `main/kotlin` nesting. Resources go in `resources/` at the module root. Test sources go in `test/`, test resources in `testResources/`. There are no separate `src/main/kotlin` or `src/main/java` subdirectories unless you explicitly opt into `layout: gradle-jvm` or `layout: maven-like`.

---

## Exact module.yaml for each module

### `project.yaml` (project root)

```yaml
modules:
  - ./annotations
  - ./processor
  - ./runtime
  - ./sample
```

Every module must appear here. Dependencies between modules are only valid within this declared scope.

### `annotations/module.yaml` — pure Kotlin library, zero dependencies

```yaml
product:
  type: lib
  platforms: [jvm]
```

This is the simplest possible module. No dependencies needed — it just holds annotation class definitions. The `lib` product type with `platforms: [jvm]` produces a reusable JVM library. If you later want multiplatform annotations (e.g., for KMP consumers), change to `platforms: [jvm, iosArm64, ...]`, but note that KSP-generated code won't be visible in common source sets.

### `processor/module.yaml` — KSP processor, depends on KSP API

```yaml
product:
  type: lib
  platforms: [jvm]

dependencies:
  - ../annotations: exported
  - com.google.devtools.ksp:symbol-processing-api:2.0.21-1.0.25: compile-only
```

**Critical details:**
- The KSP symbol-processing-api dependency **must be `compile-only`** — it's provided by the KSP runtime at processing time, not bundled into the processor JAR.
- The `annotations` dependency is marked `exported` so that consumers of the processor automatically get transitive access to the annotations. (In Amper, `exported` is the equivalent of Gradle's `api` scope; the default is non-exported, equivalent to `implementation`.)
- You **must** register the `SymbolProcessorProvider` via standard SPI: create `resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` containing the fully-qualified class name.
- Amper's default KSP version in v0.10.0 is **2.3.6**. The `symbol-processing-api` version you declare here should be compatible with the KSP2 version Amper uses. You may want to use the version matching Amper's default or verify compatibility.

### `runtime/module.yaml` — runtime helpers library

```yaml
product:
  type: lib
  platforms: [jvm]

dependencies:
  - ../annotations: exported
```

This module holds any base classes, interfaces, or utility functions that the generated code will reference at runtime. It depends on `annotations` (exported) so consumers get both.

### `sample/module.yaml` — consumer app wiring the processor

```yaml
product: jvm/app

dependencies:
  - ../annotations
  - ../runtime

settings:
  kotlin:
    ksp:
      processors:
        - ../processor
```

**This is the key wiring.** The `settings.kotlin.ksp.processors` list accepts **relative paths to local processor modules** — this is the direct equivalent of Gradle's `ksp(project(":processor"))`. The `../processor` path points to the directory containing the processor's `module.yaml`. Generated source files are placed in the build output directory and **automatically added to the compilation classpath** — no manual source directory configuration is needed.

---

## Dependency graph in Amper terms

```
annotations  ←──────── processor (compile-only: KSP API)
     ↑                      ↑
     │                      │ (via settings.kotlin.ksp.processors)
  runtime                   │
     ↑                      │
     │                      │
   sample ──────────────────┘
```

In concrete Amper terms:

| Module | `dependencies` | `settings.kotlin.ksp.processors` |
|---|---|---|
| `annotations` | *(none)* | *(none)* |
| `processor` | `../annotations: exported`, `ksp-api: compile-only` | *(none)* |
| `runtime` | `../annotations: exported` | *(none)* |
| `sample` | `../annotations`, `../runtime` | `../processor` |

The `sample` module does not declare `processor` as a regular dependency. The processor is **only** referenced through the KSP settings block. Amper handles compilation ordering and classpath wiring automatically.

---

## Amper v0.10.0 configuration syntax reference

### Dependency scope mappings (Gradle → Amper)

| Gradle scope | Amper syntax |
|---|---|
| `implementation(...)` | `- group:artifact:version` *(default, no modifier)* |
| `api(...)` | `- group:artifact:version: exported` |
| `compileOnly(...)` | `- group:artifact:version: compile-only` |
| `runtimeOnly(...)` | `- group:artifact:version: runtime-only` |
| `testImplementation(...)` | Listed under `test-dependencies:` |
| `ksp(project(":mod"))` | Listed under `settings.kotlin.ksp.processors` |

Long-form syntax for combined modifiers:
```yaml
dependencies:
  - group:artifact:version:
      exported: true
      scope: compile-only
```

### Kotlin and JVM configuration

```yaml
settings:
  kotlin:
    languageVersion: 2.0    # Kotlin language version
  jvm:
    release: 17             # JVM bytecode target (equivalent to jvmTarget)
    jdk:
      version: 21           # JDK provisioning (new in 0.10)
      distributions: [zulu, temurin]
```

These settings can be centralized in a **template file** to avoid repetition across modules. Create `common.module-template.yaml` at the project root:

```yaml
# common.module-template.yaml
settings:
  jvm:
    release: 17
```

Then apply it in each module:
```yaml
apply:
  - ../common.module-template.yaml

product:
  type: lib
  platforms: [jvm]
```

Templates cannot contain `product:` or `apply:` sections. They can contain `dependencies`, `test-dependencies`, `settings`, and `test-settings`.

### KSP processor options

```yaml
settings:
  kotlin:
    ksp:
      processors:
        - ../processor
      processorOptions:
        myOption: myValue
```

All options are passed to **all** processors globally — processors must use unique option names to avoid collisions.

---

## Five gotchas that will affect your design

**1. KSP2 exclusively.** Amper only runs KSP2. Your processor must implement the KSP2-compatible API. KSP1 processors will not work. Since the KSP project has dropped KSP1 from recent releases, this is forward-compatible but means you cannot use older KSP1-only libraries.

**2. Generated sources are invisible in common multiplatform source sets.** If you ever make the `sample` module multiplatform, KSP runs per-platform. Generated code will only be available in platform-specific compilation, not in `src/` (common). This is a fundamental KSP limitation tracked at google/ksp#567. For a JVM-only project, this is irrelevant — but it constrains future KMP expansion.

**3. No KSP version override syntax documented.** Amper v0.10.0 defaults to KSP **2.3.6**. The documentation does not show how to override this default version. You work with whatever KSP version Amper bundles. Your `symbol-processing-api` dependency version in the processor module should align with this.

**4. AMPER-4945 was fixed in v0.10.0** — a bug where KSP processors couldn't access local dependencies for web targets. This is resolved but signals that local-processor-dependency resolution has had edge cases. Stick to JVM targets for the processor module.

**5. Amper is experimental.** The build tool is pre-alpha. Publication of library modules to Maven repositories is "unofficially supported" but not stable. If your processor needs to be consumed outside this project (published to Maven Central), you'll need to verify the publication workflow works or maintain a parallel Gradle build for publishing.

---

## Decisions for your Stage 1 plan

Based on these facts, here are the design decisions you can lock in:

**Module count: four** — `annotations`, `processor`, `runtime`, `sample`. All are `type: lib` with `platforms: [jvm]` except `sample` which is `jvm/app`. This is the standard KSP three-module pattern plus a `runtime` module for generated-code dependencies.

**Build tool: standalone Amper** — use `project.yaml` + `module.yaml` files, no Gradle. KSP is built-in. The `amper` / `amper.bat` wrapper scripts handle everything.

**KSP wiring: declarative** — the processor is referenced by relative path in the consumer's `settings.kotlin.ksp.processors`. No plugin application, no buildscript classpath manipulation.

**SPI registration: manual** — you still need `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` in the processor's `resources/` directory. Amper doesn't auto-generate this.

**Template for shared settings: recommended** — use a `common.module-template.yaml` to centralize `jvm.release`, `kotlin.languageVersion`, and any shared test dependencies across all four modules.

**Test framework: automatic** — Amper auto-configures the Kotlin test framework. Additional test dependencies (like MockK or compile-testing) go in each module's `test-dependencies:` section. Consider adding `com.github.tschuchortdev:kotlin-compile-testing-ksp` to the processor module's test dependencies for processor unit tests.
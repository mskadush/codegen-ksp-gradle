# Plan 020: Deployment Paths — Maven Local & GitHub Packages

**Created**: 2026-04-27
**Supersedes**: none

## Context

The project is a KSP annotation processor library split into three publishable modules:
- `annotations` — the public annotation API consumers place on their classes
- `runtime` — runtime utilities referenced by generated code
- `processor` — the KSP `SymbolProcessorProvider` that drives code generation

Currently no publishing configuration exists anywhere in the codebase: no `maven-publish` plugin, no group ID, no version, and no CI/CD. Consumers cannot depend on any of these artifacts via a package registry.

The goal is to add two deployment paths:
1. **Maven Local** — for local development and testing: `./gradlew publishToMavenLocal`
2. **GitHub Packages** — for release distribution, triggered automatically by pushing a `v*` tag via GitHub Actions

**Publish coordinates:**
- Group: `za.co.skadush.codegen.gradle`
- Artifact IDs: `codegen-annotations`, `codegen-runtime`, `codegen-processor`
- Version: from git tag (e.g. `v0.1.0` → `0.1.0`), defaulting to `0.1.0-SNAPSHOT` locally
- Registry URL: `https://maven.pkg.github.com/mskadush/codegen-ksp-gradle`

## Approach

Three publishable modules need `maven-publish` wired with correct coordinates and POM metadata. The riskiest piece is **GitHub Packages authentication in CI**: the repository URL format, credential environment variable names, and the `permissions: packages: write` scope in the workflow are all easy to get subtly wrong. A misconfigured URL or missing permission silently produces a 403 or 404 at publish time — not at workflow authoring time.

The sequence retires risk in this order: first prove that the Gradle publishing configuration is correct and the JAR structure is valid (including the `META-INF/services` KSP registration file) via `publishToMavenLocal` on one module. Then extend to all three modules with a centralised root-level convention to avoid duplication. Then wire up GitHub Packages credentials — this is the cross-system boundary. Finally, write the GitHub Actions workflow that connects the tag push event to the `publishAllPublicationsToGitHubPackagesRepository` task.

POM metadata, source JARs, and Javadoc JARs are deferred to the refactor stage — they are nice-to-have for a registry, but not required for the integration to work.

## Step Sequence

### Stage 1 — Tracer bullet: publishToMavenLocal for processor

- **W1.** Add `version=0.1.0-SNAPSHOT` and `group=za.co.skadush.codegen.gradle` to `gradle.properties`.
  **Verify:** `./gradlew properties | grep -E "^(group|version)"` prints the expected values for the root project.

- **W2.** Apply `maven-publish` to `processor/build.gradle.kts`. Add a single `MavenPublication` named `"maven"` that calls `from(components["java"])`, sets `groupId = "za.co.skadush.codegen.gradle"`, `artifactId = "codegen-processor"`, and `version = project.version.toString()`. Add no repositories yet.
  **Verify:** `./gradlew :processor:generatePomFileForMavenPublication` succeeds and produces a POM at `processor/build/publications/maven/pom-default.xml` with the correct coordinates.

- **W3.** Run `./gradlew :processor:publishToMavenLocal`. Inspect the output in `~/.m2/repository/za/co/skadush/codegen/gradle/codegen-processor/0.1.0-SNAPSHOT/`.
  **Verify:** The directory contains a `.jar` and a `.pom`. Unzip the JAR and confirm `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` is present with `DomainMappingProcessorProvider` as its content.

**Demo:**
1. Run `./gradlew :processor:publishToMavenLocal`
2. Run `ls ~/.m2/repository/za/co/skadush/codegen/gradle/codegen-processor/0.1.0-SNAPSHOT/` — expect a `.jar` and `.pom`
3. Run `unzip -p ~/.m2/repository/za/co/skadush/codegen/gradle/codegen-processor/0.1.0-SNAPSHOT/codegen-processor-0.1.0-SNAPSHOT.jar META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` — expect `DomainMappingProcessorProvider`

---

### Stage 2 — Extend to all three modules, centralise config in root

- **W4.** Move the `maven-publish` configuration out of `processor/build.gradle.kts` and into the root `build.gradle.kts`. Use `configure(listOf(project(":annotations"), project(":runtime"), project(":processor")))` to target the three publishable modules explicitly by name — no filter heuristics. Each module's artifact ID is derived from the project name: `"codegen-${project.name}"`.
  **Verify:** `./gradlew :annotations:generatePomFileForMavenPublication :runtime:generatePomFileForMavenPublication :processor:generatePomFileForMavenPublication` all succeed with correct artifact IDs.

- **W5.** Run `./gradlew publishToMavenLocal` (all modules).
  **Verify:** Three directories appear under `~/.m2/repository/za/co/skadush/codegen/gradle/`: `codegen-annotations/`, `codegen-runtime/`, `codegen-processor/`, each containing a `.jar` and `.pom`.

**Demo:**
1. Run `./gradlew publishToMavenLocal`
2. Run `find ~/.m2/repository/za/co/skadush/codegen/gradle -name "*.jar"` — expect three JARs

---

### Stage 3 — GitHub Packages repository config (riskiest integration)

- **W6.** Add a `repositories` block inside the shared `configure(...)` publishing extension in the root `build.gradle.kts`. Define a Maven repository named `"GitHubPackages"` with URL `https://maven.pkg.github.com/mskadush/codegen-ksp-gradle`. Set credentials with `username = System.getenv("GITHUB_ACTOR") ?: ""` and `password = System.getenv("GITHUB_TOKEN") ?: ""`.
  **Verify:** `./gradlew :processor:publishMavenPublicationToGitHubPackagesRepository --dry-run` (or `tasks | grep GitHubPackages`) lists the task without error. Note: the actual publish will fail locally without credentials — that is expected at this stage.

- **W7.** Confirm that `./gradlew tasks --group publishing` lists `publishAllPublicationsToGitHubPackagesRepository` for each publishable module and the aggregate root task.
  **Verify:** Task names are visible in the output.

**Demo:**
1. Run `./gradlew tasks --group publishing`
2. Observe `publishAllPublicationsToGitHubPackagesRepository` listed alongside `publishToMavenLocal`

---

### Stage 4 — GitHub Actions workflow with dispatch-triggered tag creation and publish

- **W8.** Create `.github/workflows/publish.yml`. The workflow triggers on `workflow_dispatch` with a required string input `version` (e.g. `0.1.0`). It runs on `ubuntu-latest` and requests `permissions: packages: write, contents: write` (write on contents is needed to push the tag). Steps:
  1. Checkout with `fetch-depth: 0` so the full history is present.
  2. Set up Java 25 (Temurin).
  3. Create and push the git tag: `git tag v${{ inputs.version }} && git push origin v${{ inputs.version }}`.
  4. Run the publish task:
     ```
     ./gradlew publishAllPublicationsToGitHubPackagesRepository -Pversion=${{ inputs.version }}
     ```
     with `GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` and `GITHUB_ACTOR: ${{ github.actor }}` in the `env` block.

  **Verify:** Workflow YAML is valid. The `workflow_dispatch` block has a `version` input with a description and no default. `permissions` includes both `packages: write` and `contents: write`.

- **W9.** Add a comment block at the top of the workflow file documenting how to trigger a release: navigate to Actions → Publish → Run workflow → enter version (no `v` prefix).
  **Verify:** Comment is present in the file.

**Demo:**
1. Open Actions tab on `https://github.com/mskadush/codegen-ksp-gradle`
2. Select the "Publish" workflow → click "Run workflow" → enter `0.1.0` in the version field
3. Observe the workflow create tag `v0.1.0`, push it, then publish all three modules to `https://github.com/mskadush/codegen-ksp-gradle/packages`

---

### Stage 5 — Refactor and documentation (last)

- **W10.** Extract the GitHub Packages URL and repository owner/name to `gradle.properties` as `githubPackagesUrl=https://maven.pkg.github.com/mskadush/codegen-ksp-gradle` so it's easy to update without touching build logic.
- **W11.** Add a `sources` JAR to each publication (`java { withSourcesJar() }`) so consumers can navigate to sources.
- **W12.** Create `docs/installation.md` — a standalone installation guide covering:
  - Prerequisites (Kotlin, KSP plugin version, Java requirement)
  - How to add the GitHub Packages Maven repository to a consumer project (Gradle Kotlin DSL and Groovy DSL examples), including the required `GITHUB_TOKEN` env var or `~/.gradle/gradle.properties` credential configuration
  - Dependency declarations for each of the three artifacts (`codegen-annotations`, `codegen-runtime`, and `ksp(...)` for `codegen-processor`)
  - A minimal working `build.gradle.kts` snippet the consumer can copy
  - How to use Maven Local for local development: `./gradlew publishToMavenLocal` and adding `mavenLocal()` to the consumer's repositories

<!-- No Demo block — pure refactor/polish, no new observable behaviour -->

---

## Files to create / modify

| File | Action |
|------|--------|
| `gradle.properties` | Add `group`, `version`, `githubPackagesUrl` |
| `build.gradle.kts` (root) | Add shared `maven-publish` config in `configure(subprojects.filter { it.name != "app" })` |
| `annotations/build.gradle.kts` | No changes needed (publishing applied from root) |
| `runtime/build.gradle.kts` | No changes needed (publishing applied from root) |
| `processor/build.gradle.kts` | No changes needed (publishing applied from root) |
| `.github/workflows/publish.yml` | Create new |
| `docs/installation.md` | Create new |
| `plans-by-ai/020-deployment-paths.md` | Create (copy of this plan) |
| `plans-by-ai/ROADMAP.md` | Create with this plan in Backlog |

## Verification checklist

- [ ] W1: `group` and `version` properties visible via `./gradlew properties`
- [ ] W2: POM generated at correct path with correct coordinates
- [ ] W3: Maven Local has processor JAR with `META-INF/services` intact
- [ ] W4: Root-level convention applies to `annotations`, `runtime`, `processor` — not `app`
- [ ] W5: All three JARs published to Maven Local
- [ ] W6: `publishMavenPublicationToGitHubPackagesRepository` task exists without error
- [ ] W7: `publishAllPublicationsToGitHubPackagesRepository` is a root aggregate task
- [ ] W8: Workflow YAML valid; `workflow_dispatch` with `version` input; `contents: write` permission present
- [ ] W9: Release trigger instructions documented in workflow header comment
- [ ] W10-12: (post-refactor) `docs/installation.md` created, sources JARs attached, URL in gradle.properties

## Recommendations

1. **GitHub Packages read auth**: consuming the packages from GitHub Packages requires a `GITHUB_TOKEN` with `read:packages` scope — consumers outside the org need a personal access token. Document this in README during W12.
2. **KSP version alignment**: the KSP processor version (`2.3.5`) must match the consumer's KSP plugin version. Consider adding the KSP version to `gradle.properties` and exposing it in the POM as a dependency so consumers can detect mismatches.
3. **Java 25 on CI runners**: Java 25 is a preview release; `ubuntu-latest` may not have it preinstalled. Use `actions/setup-java@v4` with `distribution: temurin` and verify the version is available on the runner. If not, fall back to Java 21 LTS (the KSP API constraint is the actual floor, not the toolchain).
4. **No signing configured**: GitHub Packages does not require artifact signing, but Maven Central does. If this ever needs to go to Central, signing (GPG via `signing` Gradle plugin + secrets) will need a separate plan.

## What Was Done
<!-- Filled in after implementation completes. -->

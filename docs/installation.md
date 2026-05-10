# Installation Guide

This library is a [KSP](https://github.com/google/ksp) annotation processor that generates domain mapping code at compile time. It is distributed as three artifacts published to GitHub Packages.

| Artifact | Purpose |
|----------|---------|
| `codegen-annotations` | Annotation API (`@ClassSpec`, `@FieldOverride`, `@FieldBundle`, …) |
| `codegen-runtime` | Runtime utilities used by generated code |
| `codegen-processor` | KSP symbol processor — drives code generation |

---

## Prerequisites

- Kotlin **2.3.0** or higher
- KSP plugin **2.3.5** (must match the processor's KSP API version)
- Java **21+** (project targets JVM toolchain 25)

---

## Adding the GitHub Packages repository

GitHub Packages requires authentication even for public packages. You need a GitHub personal access token (PAT) with the `read:packages` scope.

### Store credentials outside your build files

Add the following to `~/.gradle/gradle.properties` (never commit credentials):

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITHUB_PAT
```

### Gradle Kotlin DSL (`build.gradle.kts`)

```kotlin
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/mskadush/codegen-ksp-gradle")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```

### Gradle Groovy DSL (`build.gradle`)

```groovy
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/mskadush/codegen-ksp-gradle")
        credentials {
            username = findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = findProperty("gpr.token") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```

---

## Declaring dependencies

### Gradle Kotlin DSL (`build.gradle.kts`)

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    id("com.google.devtools.ksp") version "2.3.5"
}

dependencies {
    // Annotation API — place these annotations on your domain classes
    implementation("za.co.skadush.codegen.gradle:codegen-annotations:VERSION")

    // Runtime utilities required by generated code
    implementation("za.co.skadush.codegen.gradle:codegen-runtime:VERSION")

    // KSP processor — runs at compile time only
    ksp("za.co.skadush.codegen.gradle:codegen-processor:VERSION")
}
```

Replace `VERSION` with the release version (e.g. `0.1.0`). Check the
[Packages page](https://github.com/mskadush/codegen-ksp-gradle/packages) for available versions.

### Gradle Groovy DSL (`build.gradle`)

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.3.0'
    id 'com.google.devtools.ksp' version '2.3.5'
}

dependencies {
    implementation 'za.co.skadush.codegen.gradle:codegen-annotations:VERSION'
    implementation 'za.co.skadush.codegen.gradle:codegen-runtime:VERSION'
    ksp          'za.co.skadush.codegen.gradle:codegen-processor:VERSION'
}
```

---

## Complete minimal `build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    id("com.google.devtools.ksp") version "2.3.5"
}

repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/mskadush/codegen-ksp-gradle")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("za.co.skadush.codegen.gradle:codegen-annotations:0.1.0")
    implementation("za.co.skadush.codegen.gradle:codegen-runtime:0.1.0")
    ksp("za.co.skadush.codegen.gradle:codegen-processor:0.1.0")
}
```

---

## Local development (Maven Local)

If you have cloned this repository and want to test changes locally before publishing:

1. Publish all three modules to your local Maven cache:
   ```bash
   ./gradlew publishToMavenLocal
   # or for a specific version:
   ./gradlew publishToMavenLocal -Pversion=0.2.0-SNAPSHOT
   ```

2. In your consumer project, add `mavenLocal()` **before** other repositories:
   ```kotlin
   repositories {
       mavenLocal()
       mavenCentral()
   }
   ```

3. Reference the SNAPSHOT version in your dependencies:
   ```kotlin
   implementation("za.co.skadush.codegen.gradle:codegen-annotations:0.2.0-SNAPSHOT")
   implementation("za.co.skadush.codegen.gradle:codegen-runtime:0.2.0-SNAPSHOT")
   ksp("za.co.skadush.codegen.gradle:codegen-processor:0.2.0-SNAPSHOT")
   ```

---

## Releasing a new version

Releases are triggered via GitHub Actions (no manual tagging needed):

1. Open the **Actions** tab on GitHub
2. Select the **Publish** workflow
3. Click **Run workflow**
4. Enter the version number without the `v` prefix (e.g. `0.2.0`)
5. Click **Run workflow**

The workflow will create and push the `v0.2.0` tag, then publish all three artifacts to GitHub Packages.

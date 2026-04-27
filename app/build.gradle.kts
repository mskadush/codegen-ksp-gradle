plugins {
    kotlin("jvm")
    application
    id("com.google.devtools.ksp") version "2.3.5"
}

application {
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
}

ksp {
    arg("codegen.defaultPackage", "za.skadush.codegen.gradle.generated")
}

dependencies {
    implementation(project(":annotations"))
    implementation(project(":runtime"))
    ksp(project(":processor"))
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.1.0")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.17.0")
    compileOnly("jakarta.validation:jakarta.validation-api:3.1.0")
}

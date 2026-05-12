
plugins {
	kotlin("jvm") version "2.3.0"
	id("dev.detekt") version "2.0.0-alpha.2"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
    }

    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        baseline = file("$rootDir/config/detekt/detekt-baseline.xml")
    }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            html.required.set(true)
        }
    }
}

configure(listOf(project(":annotations"), project(":runtime"), project(":processor"))) {
    apply(plugin = "maven-publish")

    afterEvaluate {
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            withSourcesJar()
        }
        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    groupId = rootProject.group.toString()
                    artifactId = "codegen-${project.name}"
                    version = project.version.toString()
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri(rootProject.findProperty("githubPackagesUrl").toString())
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: ""
                        password = System.getenv("GITHUB_TOKEN") ?: ""
                    }
                }
            }
        }
    }
}

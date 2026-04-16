rootProject.name = "codegen-ksp-gradle"

pluginManagement {
	repositories {
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
	}
}

include(":annotations")
include(":app")
include(":processor")

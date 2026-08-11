pluginManagement {
    // Convention plugins live in the build-logic composite build.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    // gradle/libs.versions.toml is picked up automatically as the "libs" catalog.
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "viewforge"

// --- core: pure/JVM, no Compose (CLAUDE.md rule 1) ---
include(":core:model")
include(":core:project")
include(":core:prefs")
include(":core:command")
include(":core:spi")

// --- editor: Compose Desktop UI ---
include(":editor:canvas")
include(":editor:panels")
include(":editor:state")
include(":editor:shell")

// --- packages: one Compose framework package with N targets (ADR-008) ---
include(":packages:compose")

// --- app: desktop entry point + packaging ---
include(":app")

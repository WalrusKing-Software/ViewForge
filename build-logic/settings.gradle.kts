// Composite build holding ViewForge's convention plugins. Included from the root
// settings.gradle.kts via includeBuild("build-logic"). Keeping shared Gradle logic here
// (rather than in a buildSrc) keeps convention-plugin changes from invalidating the whole
// build's configuration cache.

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    // Reuse the project's single version catalog so convention plugins pin the same versions.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

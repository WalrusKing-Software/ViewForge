plugins {
    `kotlin-dsl`
}

// Plugin artifacts pulled onto the convention plugins' classpath so the precompiled script
// plugins in src/main/kotlin can apply them by id without repeating a version. Versions come
// from gradle/libs.versions.toml (single source of truth).
dependencies {
    implementation(libs.plugin.kotlin.gradle)
    implementation(libs.plugin.compose.gradle)
    implementation(libs.plugin.compose.compiler)
    implementation(libs.plugin.spotless.gradle)

    // Exposes the generated version-catalog accessors (LibrariesForLibs / `libs`) to the
    // precompiled script plugins in src/main/kotlin. Without this, `the<LibrariesForLibs>()`
    // and the `org.gradle.accessors.dm` import don't resolve — a known Gradle limitation.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

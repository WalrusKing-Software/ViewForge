import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Base convention for a plain Kotlin/JVM module. Applied by every module, including core/*,
// which must NOT depend on Compose (CLAUDE.md rule 1 / ADR-007). This convention deliberately
// pulls in no Compose plugin or dependency, so a core module physically cannot reference it.

plugins {
    kotlin("jvm")
    id("com.diffplug.spotless")
}

// Precompiled script plugins don't get the generated `libs` accessor; resolve the catalog manually.
val libs = the<LibrariesForLibs>()

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    "testImplementation"(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events("passed", "skipped", "failed")
    }
}

// Formatting is a project rule (risk 7.3): every module is linted the same way.
// @Composable functions are PascalCase by Compose convention (they emit UI, not compute values),
// which the standard ktlint function-naming rule rejects. Exempt them here — spotless 6.x does not
// reliably pick this up from .editorconfig, so it is set as an explicit override. Applies from M2,
// the first Compose UI code; harmless for core/* modules, which have no composables.
val ktlintOverrides = mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable")

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintOverrides)
        endWithNewline()
        trimTrailingWhitespace()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

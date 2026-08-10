plugins {
    id("viewforge.compose-library")
}

// packages/compose: THE Compose framework package (ADR-008) — component & modifier definitions,
// runtime renderers, KotlinPoet emitters, and per-target exporters. The one and only SPI
// implementation until Phase 5 (ADR-007). This is the module the core boundary protects.
dependencies {
    api(projects.core.spi)
    implementation(projects.core.model)

    // Codegen: KotlinPoet structural API only — never string concatenation (CLAUDE.md rule 4).
    implementation(libs.kotlinpoet)

    // Runtime renderers use real Compose composables (ARCHITECTURE §4.1).
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // Golden fixtures are loaded from real `.vforge` files via the project codec.
    testImplementation(projects.core.project)

    // Codegen compile gate (G2/GC-6): compile generated Kotlin in-process against Compose. The
    // compose-compiler plugin embeddable supplies the `@Composable` registrar; both embeddables are
    // forced to the pinned Kotlin version so the plugin matches the compiler it registers into.
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.compose.compiler.plugin.embeddable)
}

// The golden `.kt` fixtures under test resources are codegen *output*, asserted byte-for-byte against
// what the emitter produces — they are not hand-written source. Exempt them from spotless/ktlint so
// the formatter can never rewrite a fixture out from under the golden tests (they must match
// KotlinPoet's emission, not ktlint's opinion).
spotless {
    kotlin {
        targetExclude("src/test/resources/**")
    }
}

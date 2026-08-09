// Convention for a Compose-aware library module: packages/compose and editor/*.
// Builds on viewforge.kotlin-library and adds the Compose Multiplatform + compiler plugins.
// core/* must never apply this — that is the enforced framework boundary (CLAUDE.md rule 1).

plugins {
    id("viewforge.kotlin-library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

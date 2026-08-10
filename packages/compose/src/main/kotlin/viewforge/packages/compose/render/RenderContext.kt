package viewforge.packages.compose.render

import viewforge.model.Theme

/**
 * What every node needs while it renders (ARCHITECTURE §4.2). Deliberately tiny for M2 (a static
 * canvas): the project [theme] for token resolution and the [dark] flag selecting the light/dark
 * half of each theme color pair.
 *
 * This grows later — M3 adds editor instrumentation (a per-node modifier for hit-testing, ADR-009)
 * and a component registry — but M2 needs neither, and the working agreement is one implementation
 * before one abstraction (ADR-007).
 */
data class RenderContext(val theme: Theme, val dark: Boolean)

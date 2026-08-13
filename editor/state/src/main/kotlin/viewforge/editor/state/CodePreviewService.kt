package viewforge.editor.state

import viewforge.model.ComponentDef
import viewforge.model.Project
import viewforge.model.Screen

/**
 * The editor-owned code-preview seam (the ADR-013 pattern, like [ProjectExportService]): the shell asks
 * for the generated source of a screen or a reusable component (G3) without ever naming
 * `packages/compose`. `:app` binds it to the Compose code generator.
 *
 * Compose-generator-free (this module has no dependency on the framework package): it deals only in the
 * [Project] and a [Screen] / [ComponentDef], returning source text.
 */
interface CodePreviewService {
    /**
     * The generated Kotlin/Compose source for [screen] within [project], with a node→source-range map
     * (G3, #51) so the panel can highlight the selected node. May throw if generation fails (an
     * unsupported node, an invalid identifier); [previewContent] turns that into a visible message.
     */
    fun previewScreen(project: Project, screen: Screen): PreviewSource

    /**
     * The generated Kotlin/Compose source for reusable [component] within [project] — the same `@Composable`
     * the export emits — so the panel follows a component opened for in-place editing (#69), with the same
     * node→source-range map (#51). May throw on a generation failure, which [previewContent] turns into a
     * visible message.
     */
    fun previewComponent(project: Project, component: ComponentDef): PreviewSource
}

/**
 * Generated source paired with a node→source-range map (G3, #51): [spans] maps a node id to the half-open
 * character range in [code] that the node's code occupies. The framework-free mirror of the compose
 * package's `GeneratedSource`, so this module carries no dependency on `packages/compose`.
 */
data class PreviewSource(val code: String, val spans: Map<String, IntRange>)

/**
 * What the code preview shows: the active [OfScreen] or, while a component is open for in-place editing,
 * the [OfComponent] whose source is generated instead (#69).
 */
sealed interface PreviewTarget {
    data class OfScreen(val screen: Screen) : PreviewTarget

    data class OfComponent(val component: ComponentDef) : PreviewTarget
}

/** What the code-preview panel should display: generated [Source] (with node spans), or a [Failure] message (G3). */
sealed interface PreviewContent {
    data class Source(val code: String, val spans: Map<String, IntRange> = emptyMap()) : PreviewContent

    data class Failure(val message: String) : PreviewContent
}

/**
 * Resolve what the code preview shows for [target] in [project] (G3, #69). Generation is wrapped so a
 * failure — an unsupported node, an invalid name — becomes a visible [Failure] message rather than a
 * thrown exception that would blank or crash the panel (CLAUDE.md: fail loudly, never degrade silently).
 * A null [target] (an empty project with no screen) is itself a [Failure] with a friendly note. Pure, so
 * it is unit-tested without a composition.
 */
fun previewContent(service: CodePreviewService, project: Project, target: PreviewTarget?): PreviewContent {
    val generate: () -> PreviewSource = when (target) {
        null -> return PreviewContent.Failure("No screen to preview.")
        is PreviewTarget.OfScreen -> {
            { service.previewScreen(project, target.screen) }
        }
        is PreviewTarget.OfComponent -> {
            { service.previewComponent(project, target.component) }
        }
    }
    return runCatching(generate).fold(
        onSuccess = { PreviewContent.Source(it.code, it.spans) },
        onFailure = { PreviewContent.Failure(it.message ?: it::class.simpleName ?: "Code generation failed.") },
    )
}

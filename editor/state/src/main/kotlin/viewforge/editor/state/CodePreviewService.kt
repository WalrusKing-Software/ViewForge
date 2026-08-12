package viewforge.editor.state

import viewforge.model.Project
import viewforge.model.Screen

/**
 * The editor-owned code-preview seam (the ADR-013 pattern, like [ProjectExportService]): the shell asks
 * for the generated source of a screen (G3) without ever naming `packages/compose`. `:app` binds it to
 * the Compose code generator.
 *
 * Compose-generator-free (this module has no dependency on the framework package): it deals only in the
 * [Project] and a [Screen], returning source text.
 */
interface CodePreviewService {
    /**
     * The generated Kotlin/Compose source for [screen] within [project]. May throw if generation fails
     * (an unsupported node, an invalid identifier); [previewContent] turns that into a visible message.
     */
    fun previewScreen(project: Project, screen: Screen): String
}

/** What the code-preview panel should display: generated [Source], or a [Failure] message (G3). */
sealed interface PreviewContent {
    data class Source(val code: String) : PreviewContent

    data class Failure(val message: String) : PreviewContent
}

/**
 * Resolve what the code preview shows for [screen] in [project] (G3). Generation is wrapped so a failure
 * — an unsupported node, an invalid screen name — becomes a visible [Failure] message rather than a
 * thrown exception that would blank or crash the panel (CLAUDE.md: fail loudly, never degrade silently).
 * A null [screen] (an empty project) is itself a [Failure] with a friendly note. Pure, so it is
 * unit-tested without a composition.
 */
fun previewContent(service: CodePreviewService, project: Project, screen: Screen?): PreviewContent {
    if (screen == null) return PreviewContent.Failure("No screen to preview.")
    return runCatching { service.previewScreen(project, screen) }.fold(
        onSuccess = { PreviewContent.Source(it) },
        onFailure = { PreviewContent.Failure(it.message ?: it::class.simpleName ?: "Code generation failed.") },
    )
}

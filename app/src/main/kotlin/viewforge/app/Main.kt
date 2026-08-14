package viewforge.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import viewforge.editor.canvas.CanvasRenderer
import viewforge.editor.shell.EditorShell
import viewforge.editor.state.CodePreviewService
import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.ExportMode
import viewforge.editor.state.PaletteEntry
import viewforge.editor.state.PreviewSource
import viewforge.editor.state.ProjectExportService
import viewforge.model.ComponentDef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.packages.compose.catalog.ComposeComponents
import viewforge.packages.compose.catalog.ComposeModifiers
import viewforge.packages.compose.codegen.ComposeCodeGenerator
import viewforge.packages.compose.codegen.KotlinIdentifiers
import viewforge.packages.compose.render.ComposeRenderer
import viewforge.packages.compose.targets.DesktopExporter
import viewforge.prefs.ConfigDir
import viewforge.prefs.PreferencesStore
import viewforge.project.CrashReporter
import viewforge.project.ExportFile
import viewforge.project.ProjectExporter
import java.nio.file.Path

/**
 * Desktop entry point and the single bootstrapping site allowed a compile-time dependency on
 * `packages/compose` (ARCHITECTURE §3): it binds the editor's Compose-free [CanvasRenderer] seam to
 * the Compose package's [ComposeRenderer]. Nothing else in the editor names the framework package.
 *
 * M2 loads a hardcoded document ([sampleProject]) and renders it in a real Compose Desktop window;
 * open/save, packaging, and the rest of the shell arrive in later milestones.
 */
fun main() {
    installCrashReporter()
    runEditor()
}

/**
 * Route uncaught exceptions on any thread to a local crash log under the config dir (S6, #106), then
 * delegate to the JVM's previous handler so default print-and-exit still happens. **Local only** — no
 * network (SECURITY / ADR-011); the working document is preserved separately by recovery (#54). Installed
 * before the UI starts so an early failure is still captured.
 */
private fun installCrashReporter() {
    val dir = ConfigDir.resolve()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        CrashReporter.write(dir, throwable, context = "thread=${thread.name}")
        previous?.uncaughtException(thread, throwable)
    }
}

private fun runEditor() = application {
    val state = EditorState(sampleProject(), ComposeCatalog)
    // Load persisted preferences once, before the first frame — a missing/corrupt file yields defaults,
    // so this never fails startup. Restore the panel layout (#43) now; the shell persists changes back on
    // toggle/resize. The autosave interval (#55) is handed to the shell's recovery timer below.
    val prefs = PreferencesStore.load()
    state.applyLayout(prefs.panelLayout)
    state.applyRecentProjects(prefs.recentProjects) // File → Open Recent (#88), restored before the first frame
    // Editor chrome theme (S3, #104) + the S5 editor settings — autosave cadence, undo depth, default export
    // path (#105) — seeded before the first frame; the Preferences dialog edits them live thereafter.
    state.applyPreferences(prefs)
    val images = AssetImageLoader { state.document.assets }

    // The wiring: the editor asks CanvasRenderer to draw a node, handing it the per-node bounds
    // instrumentation the canvas needs for hit-testing (ADR-009); the Compose package obliges,
    // theming it with the project's own theme and applying that instrumentation to each node.
    // `dark` follows the toolbar's light/dark preview toggle (FEATURES H2). `imageLoader` resolves an
    // Image node's asset to a bitmap for the canvas (kept in `:app` so the render layer stays pure).
    val renderer =
        CanvasRenderer { root, instrument ->
            ComposeRenderer.RenderScreen(
                root = root,
                theme = state.document.theme,
                dark = state.canvasDark,
                instrument = instrument,
                imageLoader = images::load,
                components = state.document.components,
            )
        }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 832.dp))
    // Save-on-close guard (#56): closing with unsaved edits raises this flag so the shell can prompt
    // Save/Discard/Cancel instead of quitting outright; a clean document exits immediately. Recovery (#54)
    // still protects a hard kill — this is only the clean-exit UX.
    var closeRequested by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = { if (state.isDirty) closeRequested = true else exitApplication() },
        state = windowState,
        title = "ViewForge",
    ) {
        EditorShell(
            state,
            renderer,
            DesktopExportService,
            DesktopCodePreviewService,
            ConfigDir.resolve(),
            closeRequested = closeRequested,
            onCloseHandled = { closeRequested = false },
            onExit = ::exitApplication,
        )
    }
}

/**
 * Binds the editor's Compose-free [CodePreviewService] seam (G3) to the Compose code generator, the same
 * bootstrapping role `Main` plays for the renderer, catalog, and export (ADR-013) — the live code panel
 * shows generated source without the editor ever naming the framework package. Read-only: it calls the
 * `*WithSpans` lowering, whose `code` is identical to the golden `generateScreen`, plus the node→source
 * map (#51) it maps into the seam's [PreviewSource] — so the panel and the export still agree.
 */
private object DesktopCodePreviewService : CodePreviewService {
    private val generator = ComposeCodeGenerator()

    override fun previewScreen(project: Project, screen: Screen): PreviewSource = generator.generateScreenWithSpans(
        screen,
        project.theme,
        project.name.ifBlank { "Project" },
        project.schemaVersion,
        project.assets,
        project.components,
    ).let { PreviewSource(it.code, it.spans) }

    override fun previewComponent(project: Project, component: ComponentDef): PreviewSource =
        generator.generateComponentWithSpans(
            component,
            project.theme,
            project.name.ifBlank { "Project" },
            project.schemaVersion,
            project.assets,
            project.components,
        ).let { PreviewSource(it.code, it.spans) }
}

/**
 * Binds the editor's Compose-free [ProjectExportService] seam to the Compose desktop target exporter
 * ([DesktopExporter], which builds the file bundle) and the guarded [ProjectExporter] in `core/project`
 * (which writes it). The same bootstrapping role `Main` plays for the renderer and catalog (ADR-013) —
 * the shell exports without ever naming the framework package.
 */
private object DesktopExportService : ProjectExportService {
    override fun conflicts(project: Project, dir: Path, mode: ExportMode): List<String> =
        ProjectExporter.conflicts(dir, bundle(project, mode))

    override fun export(project: Project, dir: Path, mode: ExportMode): List<String> =
        ProjectExporter.write(dir, bundle(project, mode))

    private fun bundle(project: Project, mode: ExportMode): List<ExportFile> = when (mode) {
        ExportMode.LOOSE_FILES -> DesktopExporter.looseFiles(project)
        // Gradle export ships the referenced image assets so it runs unmodified (ADR-021); bytes come
        // from the same classpath source the canvas loads from.
        ExportMode.GRADLE_PROJECT -> DesktopExporter.gradleProject(project) { asset -> classpathAssetBytes(asset.path) }
    }
}

/**
 * Adapts the Compose package's [ComposeComponents] catalog to the editor's Compose-free
 * [ComponentCatalog] seam — the same bootstrapping role `Main` plays for `CanvasRenderer` (ADR-013).
 * The editor consults this for the palette and drop validation without ever naming the package.
 */
private object ComposeCatalog : ComponentCatalog {
    override val palette: List<PaletteEntry> =
        ComposeComponents.specs.map { PaletteEntry(it.type, it.label, it.category) }

    override fun newNode(type: String): Node =
        (ComposeComponents.specFor(type) ?: error("Unknown component type: $type")).create()

    override fun acceptsChildren(type: String): Boolean = ComposeComponents.specFor(type)?.acceptsChildren ?: false

    override fun slotsOf(type: String): List<String> = ComposeComponents.specFor(type)?.slots ?: emptyList()

    override fun propsFor(type: String): List<PropDefinition> = ComposeComponents.specFor(type)?.props ?: emptyList()

    override val modifierCatalog: List<ModifierDefinition> = ComposeModifiers.definitions

    override fun modifierDef(type: String): ModifierDefinition? = ComposeModifiers.definitionFor(type)

    // A screen name must be a legal Kotlin identifier to become a composable/file name (GC-3);
    // delegate to the package's validator so the hard-keyword list stays single-sourced.
    override fun isValidScreenName(name: String): Boolean = KotlinIdentifiers.isValidFunctionName(name)
}

package viewforge.packages.compose.targets

import viewforge.model.Asset
import viewforge.model.Project
import viewforge.packages.compose.codegen.ComposeCodeGenerator
import viewforge.packages.compose.codegen.KotlinFormatter
import viewforge.packages.compose.codegen.KotlinIdentifiers
import viewforge.packages.compose.codegen.NavHost
import viewforge.project.BinaryFile
import viewforge.project.ExportFile
import viewforge.project.TextFile

/**
 * The Compose **desktop** target exporter (repo layout §4, ADR-008: one package, N targets). It turns
 * a [Project] into a ready-to-write [ExportFile] bundle in one of two shapes:
 *
 * - [looseFiles] — just the generated `@Composable` screens (G4), for pasting into an existing project.
 * - [gradleProject] — a full, runnable Compose Desktop scaffold (G5): the screens under
 *   `src/main/kotlin`, a generated `Main.kt` entry point, Gradle build scripts, and the Gradle
 *   wrapper, so `./gradlew run` works unmodified.
 *
 * Pure by design — it returns bytes, never touches disk. Writing (with overwrite confirmation and the
 * path guard) is [viewforge.project.ProjectExporter]'s job. All generated Kotlin is built with
 * KotlinPoet and passed through the [KotlinFormatter] G7 pass (ADR-018); the build-config text comes
 * from [GradleScaffold]. The Gradle wrapper jar and `gradlew` scripts are copied verbatim from the
 * checksum-verified copies bundled with ViewForge (SECURITY DS-5), never re-synthesized.
 */
object DesktopExporter {
    private const val SOURCE_DIR = "src/main/kotlin"

    /**
     * Where asset bytes land in the scaffold. `src/main/resources` is on a `kotlin("jvm")` runtime
     * classpath, so a generated `painterResource("assets/foo.png")` (ADR-021) resolves at run time —
     * without this the exported project compiles but throws `Resource … not found` when the screen draws.
     */
    private const val RESOURCE_DIR = "src/main/resources"

    /** The generated screen composables only (G4), each formatted, at top level (default package). */
    fun looseFiles(project: Project): List<ExportFile> = screenFiles(project, dir = "")

    /**
     * A complete runnable Compose Desktop project (G5). [assetBytes] resolves each referenced [Asset]
     * to its raw bytes so images ship inside the scaffold and the app *runs* unmodified, not merely
     * compiles (ADR-021); the caller (`:app`) reads them from the project's asset store. An asset whose
     * bytes can't be resolved is omitted rather than aborting the whole export — the default resolver
     * yields nothing, so a caller that doesn't wire assets simply exports source only, as before.
     */
    fun gradleProject(project: Project, assetBytes: (Asset) -> ByteArray? = { null }): List<ExportFile> {
        val slug = GradleScaffold.slug(project.name.ifBlank { "Project" })
        val themeSource = ComposeCodeGenerator().generateTheme(project)
        return buildList {
            addAll(screenFiles(project, dir = "$SOURCE_DIR/"))
            // The project theme's AppTheme wrapper (H4), when the theme defines anything; Main wraps the
            // screen in it so the compiled app is themed exactly like the canvas (ADR-018).
            if (themeSource != null) add(TextFile("$SOURCE_DIR/Theme.kt", themeSource))
            // The screen-switching host (ADR-039, #214), only when some screen navigates — Main renders App()
            // instead of the first screen. A non-navigating project ships no App.kt, exactly as before.
            if (NavHost.projectNavigates(project)) {
                add(TextFile("$SOURCE_DIR/${NavHost.APP_KT}", NavHost.appHost(project)))
            }
            add(
                TextFile(
                    "$SOURCE_DIR/${ComposeEntryPoints.MAIN_KT}",
                    ComposeEntryPoints.desktopMain(project, themed = themeSource != null),
                ),
            )
            // Referenced assets, copied verbatim into the classpath resources so painterResource resolves.
            for (asset in project.assets) {
                val bytes = assetBytes(asset) ?: continue
                add(BinaryFile("$RESOURCE_DIR/${asset.path}", bytes))
            }
            add(TextFile("build.gradle.kts", GradleScaffold.buildGradle()))
            add(TextFile("settings.gradle.kts", GradleScaffold.settingsGradle(slug)))
            add(TextFile("gradle.properties", GradleScaffold.gradleProperties()))
            add(TextFile(".gitignore", GradleScaffold.gitignore()))
            add(TextFile("README.md", GradleScaffold.readme(project.name.ifBlank { "Project" })))
            add(TextFile("gradle/wrapper/gradle-wrapper.properties", GradleScaffold.wrapperProperties()))
            add(BinaryFile("gradle/wrapper/gradle-wrapper.jar", resource("gradle-wrapper.jar")))
            add(BinaryFile("gradlew", resource("gradlew"), executable = true))
            add(BinaryFile("gradlew.bat", resource("gradlew.bat")))
        }
    }

    /**
     * Maps each screen's generated `.kt` path *in the Gradle bundle* to its [viewforge.model.Screen.id]
     * (ADR-032), for the re-open manifest. Uses the exact `$SOURCE_DIR/<FunctionName>.kt` shape
     * [gradleProject] emits, so a recognised file resolves back to the screen it came from. Screens only —
     * user components and the scaffold are not re-open entry points.
     */
    fun screenPaths(project: Project): Map<String, String> = project.screens.associate { screen ->
        "$SOURCE_DIR/${KotlinIdentifiers.requireFunctionName(screen.name)}.kt" to screen.id
    }

    /** Runs codegen (reusing the same emitter the canvas mirrors) and the G7 formatter over each screen. */
    private fun screenFiles(project: Project, dir: String): List<TextFile> =
        ComposeCodeGenerator().generate(project).map { generated ->
            TextFile("$dir${generated.path}", KotlinFormatter.format(generated.content))
        }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/scaffold/$name")) { "missing scaffold resource: $name" }
            .use { it.readBytes() }
}

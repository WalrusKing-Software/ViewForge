package viewforge.packages.compose.targets

import viewforge.model.Asset
import viewforge.model.Project
import viewforge.packages.compose.codegen.ComposeCodeGenerator
import viewforge.packages.compose.codegen.DrawableResources
import viewforge.packages.compose.codegen.ImageResources
import viewforge.packages.compose.codegen.KotlinFormatter
import viewforge.packages.compose.codegen.NavHost
import viewforge.project.BinaryFile
import viewforge.project.ExportFile
import viewforge.project.TextFile
import viewforge.spi.GeneratedFile

/**
 * The Compose **KMP multi-target** exporter (G9, M11, ADR-036): turns a [Project] into a single runnable
 * Compose Multiplatform Gradle project that targets **desktop** (`jvm`) and **Android** from shared
 * `commonMain` UI. Added *alongside* [DesktopExporter] (which stays the desktop-only kotlin-jvm export),
 * so a desktop-only user is unaffected.
 *
 * The generated screens/components are the same framework-neutral Compose that [DesktopExporter] emits —
 * they are simply routed to `commonMain`; only the per-platform entry points differ (`Main.kt` →
 * `jvmMain`, `MainActivity.kt` + `AndroidManifest.xml` → `androidMain`), placed via each
 * [viewforge.spi.TargetDefinition]'s [sourceSetFor][viewforge.spi.TargetDefinition.sourceSetFor] — the G9
 * routing seam made real for the first time.
 *
 * Pure by design — returns bytes, never touches disk; writing goes through [viewforge.project.ProjectExporter]
 * with its path guard (CLAUDE.md rule 6). **Image assets** are emitted via the Compose Multiplatform resources
 * API (`Res.drawable.x`, #223, ADR-021) — a `commonMain` API, so an image renders on Android too — with each
 * asset routed into `commonMain/composeResources/drawable/` for the resources plugin to turn into the `Res`
 * accessor codegen references. [assetBytes] resolves each referenced asset's bytes; an unresolved asset is
 * skipped (its `Res.drawable.x` reference then won't build, exactly as a missing desktop resource wouldn't).
 */
object MultiplatformExporter {
    /** Where drawables land for the resources plugin — the accessor package is [MultiplatformScaffold]-configured. */
    private const val DRAWABLE_DIR = "src/$COMMON_MAIN/composeResources/drawable"

    fun multiplatformProject(project: Project, assetBytes: (Asset) -> ByteArray? = { null }): List<ExportFile> {
        val name = project.name.ifBlank { "Project" }
        val slug = GradleScaffold.slug(name)
        val gen = ComposeCodeGenerator()
        val themeSource = gen.generateTheme(project)
        val themed = themeSource != null
        // The generated `Res` accessor package, configured on the scaffold so codegen's import is deterministic.
        val images = ImageResources.Multiplatform("${MultiplatformScaffold.applicationId(slug)}.generated.resources")
        return buildList {
            // Shared UI → commonMain. Screens/components are commonMain by definition (not entry points),
            // so they are placed directly rather than routed, avoiding a mis-route of a screen named "Main".
            gen.generate(project, images).forEach { file ->
                add(TextFile(kotlinPath(COMMON_MAIN, file.path), KotlinFormatter.format(file.content)))
            }
            // The screen-switching host (ADR-039, #214) → commonMain, so both entry points render App(); only
            // when some screen navigates, else the KMP bundle is unchanged.
            if (NavHost.projectNavigates(project)) {
                add(TextFile(kotlinPath(COMMON_MAIN, NavHost.APP_KT), NavHost.appHost(project)))
            }
            // Referenced image assets → the resources source set, where the plugin generates their Res.drawable
            // accessors (#223). Skipped when the bytes can't be resolved, like the desktop exporter.
            project.assets.forEach { asset ->
                assetBytes(asset)?.let { add(BinaryFile("$DRAWABLE_DIR/${DrawableResources.fileName(asset)}", it)) }
            }
            // The project theme wrapper (H4), when the theme defines anything — commonMain via the seam.
            themeSource?.let {
                val theme = GeneratedFile("Theme.kt", it)
                add(TextFile(kotlinPath(DesktopTarget.sourceSetFor(theme), theme.path), it))
            }
            // Desktop entry (jvmMain) and Android entry + manifest (androidMain), routed through the targets.
            val main = GeneratedFile(ComposeEntryPoints.MAIN_KT, ComposeEntryPoints.desktopMain(project, themed))
            add(TextFile(kotlinPath(DesktopTarget.sourceSetFor(main), main.path), main.content))

            val activity =
                GeneratedFile(
                    ComposeEntryPoints.MAIN_ACTIVITY_KT,
                    ComposeEntryPoints.androidMainActivity(project, themed),
                )
            add(TextFile(kotlinPath(AndroidTarget.sourceSetFor(activity), activity.path), activity.content))

            val manifest = GeneratedFile(AndroidTarget.MANIFEST_XML, MultiplatformScaffold.androidManifest(name))
            // Manifest lives at the source-set root, not under kotlin/.
            add(TextFile("src/${AndroidTarget.sourceSetFor(manifest)}/${manifest.path}", manifest.content))

            // Build config + the checksum-verified Gradle wrapper (copied verbatim, SECURITY DS-5).
            add(TextFile("build.gradle.kts", MultiplatformScaffold.buildGradle(slug)))
            add(TextFile("settings.gradle.kts", MultiplatformScaffold.settingsGradle(slug)))
            add(TextFile("gradle.properties", MultiplatformScaffold.gradleProperties()))
            add(TextFile(".gitignore", MultiplatformScaffold.gitignore()))
            add(TextFile("README.md", MultiplatformScaffold.readme(name)))
            add(TextFile("gradle/wrapper/gradle-wrapper.properties", GradleScaffold.wrapperProperties()))
            add(BinaryFile("gradle/wrapper/gradle-wrapper.jar", resource("gradle-wrapper.jar")))
            add(BinaryFile("gradlew", resource("gradlew"), executable = true))
            add(BinaryFile("gradlew.bat", resource("gradlew.bat")))
        }
    }

    private fun kotlinPath(sourceSet: String, fileName: String): String = "src/$sourceSet/kotlin/$fileName"

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/scaffold/$name")) { "missing scaffold resource: $name" }
            .use { it.readBytes() }
}

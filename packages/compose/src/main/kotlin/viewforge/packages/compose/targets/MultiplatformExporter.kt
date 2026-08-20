package viewforge.packages.compose.targets

import viewforge.model.Project
import viewforge.packages.compose.codegen.ComposeCodeGenerator
import viewforge.packages.compose.codegen.KotlinFormatter
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
 * with its path guard (CLAUDE.md rule 6). **Image assets are not wired here yet:** the desktop
 * `painterResource(String)` (ADR-021) is not a `commonMain` API, so image-bearing screens compile for
 * Android only once `Image` moves to multiplatform resources (#223); until then the multiplatform export
 * targets image-free projects.
 */
object MultiplatformExporter {
    fun multiplatformProject(project: Project): List<ExportFile> {
        val name = project.name.ifBlank { "Project" }
        val slug = GradleScaffold.slug(name)
        val gen = ComposeCodeGenerator()
        val themeSource = gen.generateTheme(project)
        val themed = themeSource != null
        return buildList {
            // Shared UI → commonMain. Screens/components are commonMain by definition (not entry points),
            // so they are placed directly rather than routed, avoiding a mis-route of a screen named "Main".
            gen.generate(project).forEach { file ->
                add(TextFile(kotlinPath(COMMON_MAIN, file.path), KotlinFormatter.format(file.content)))
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

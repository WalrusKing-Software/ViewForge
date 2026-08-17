package viewforge.packages.compose.targets

import viewforge.project.BinaryFile
import viewforge.project.ExportFile
import viewforge.project.ProjectCodec
import viewforge.project.TextFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop target exporter (G4/G5). Uses the same `Demo` fixture the golden codegen suite uses, so
 * the exported screen is provably the M6 emitter's output plus the G7 formatting pass — no separate
 * hand-authored golden that could drift from the emitter.
 */
class DesktopExporterTest {
    private fun resourceText(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }.bufferedReader().readText()

    private fun resourceBytes(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }.use { it.readBytes() }

    private fun demoProject() = ProjectCodec.decode(resourceText("/golden/Demo.vforge"))

    private fun textOf(files: List<ExportFile>, path: String): String =
        (files.single { it.path == path } as TextFile).content

    @Test
    fun `loose export is the formatted screen only`() {
        val files = DesktopExporter.looseFiles(demoProject())
        assertEquals(listOf("HomeScreen.kt"), files.map { it.path })

        // Exactly the committed M6 golden with the redundant `public` removed by the G7 pass.
        val expected = resourceText("/golden/Demo.kt").replace("public fun", "fun")
        assertEquals(expected.trimEnd('\n'), textOf(files, "HomeScreen.kt").trimEnd('\n'))
    }

    @Test
    fun `gradle project has the expected file layout`() {
        val files = DesktopExporter.gradleProject(demoProject())
        assertEquals(
            setOf(
                "src/main/kotlin/HomeScreen.kt",
                // Demo's theme defines colors.primary, so the project gains an AppTheme wrapper (M8/H4).
                "src/main/kotlin/Theme.kt",
                "src/main/kotlin/Main.kt",
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradle.properties",
                ".gitignore",
                "README.md",
                "gradle/wrapper/gradle-wrapper.properties",
                "gradle/wrapper/gradle-wrapper.jar",
                "gradlew",
                "gradlew.bat",
            ),
            files.map { it.path }.toSet(),
        )
    }

    @Test
    fun `a project with no theme emits no Theme file and Main uses a default MaterialTheme`() {
        val bare = ProjectCodec.decode(resourceText("/golden/RowBox.vforge")) // empty theme
        val files = DesktopExporter.gradleProject(bare)
        assertFalse(files.any { it.path == "src/main/kotlin/Theme.kt" })
        assertContains(textOf(files, "src/main/kotlin/Main.kt"), "MaterialTheme {")
    }

    @Test
    fun `build script is runnable and version-pinned`() {
        val build = textOf(DesktopExporter.gradleProject(demoProject()), "build.gradle.kts")
        assertContains(build, "compose.desktop.currentOs")
        // currentOs bundles only Material 2; generated screens + Theme use Material 3, so the scaffold
        // must declare it explicitly. The in-process compile gate can't catch this — it inherits
        // ViewForge's own richer classpath — so this asserts the real scaffold dependency directly.
        assertContains(build, "compose.material3")
        assertContains(build, "mainClass = \"MainKt\"")
        assertContains(build, "version \"${GradleScaffold.KOTLIN_VERSION}\"")
        assertContains(build, "version \"${GradleScaffold.COMPOSE_VERSION}\"")
        assertContains(build, "jvmToolchain(${GradleScaffold.JVM_TOOLCHAIN})")

        val settings = textOf(DesktopExporter.gradleProject(demoProject()), "settings.gradle.kts")
        assertContains(settings, "rootProject.name = \"demo\"") // slug("Demo")

        val wrapperProps =
            textOf(DesktopExporter.gradleProject(demoProject()), "gradle/wrapper/gradle-wrapper.properties")
        assertContains(wrapperProps, "gradle-${GradleScaffold.GRADLE_VERSION}-bin.zip")
    }

    @Test
    fun `generated Main renders the first screen and carries no explicit public`() {
        val main = textOf(DesktopExporter.gradleProject(demoProject()), "src/main/kotlin/Main.kt")
        assertContains(main, "fun main()")
        assertContains(main, "application {")
        assertContains(main, "Window(onCloseRequest = ::exitApplication, title = \"Demo\")")
        // Demo has a theme, so Main wraps the screen in the generated AppTheme wrapper (M8/H4).
        assertContains(main, "AppTheme {")
        assertContains(main, "HomeScreen()")
        assertFalse(
            main.lineSequence().any {
                it.trimStart().startsWith("public ")
            },
            "Main.kt must omit redundant public",
        )
    }

    @Test
    fun `gradle export ships referenced assets under resources so it runs unmodified`() {
        // Image.vforge references two assets (paths assets/hero.png, assets/icon.png).
        val project = ProjectCodec.decode(resourceText("/golden/Image.vforge"))
        val bytesByPath = mapOf(
            "assets/hero.png" to byteArrayOf(1, 2, 3),
            "assets/icon.png" to byteArrayOf(4, 5),
        )
        val files = DesktopExporter.gradleProject(project) { bytesByPath[it.path] }

        val hero = files.single { it.path == "src/main/resources/assets/hero.png" } as BinaryFile
        assertTrue(hero.bytes.contentEquals(byteArrayOf(1, 2, 3)), "hero.png bytes must be copied verbatim")
        assertTrue(files.any { it.path == "src/main/resources/assets/icon.png" }, "icon.png must be exported")
    }

    @Test
    fun `gradle export omits assets when their bytes cannot be resolved`() {
        // The default resolver yields nothing: a caller that doesn't wire assets exports source only.
        val project = ProjectCodec.decode(resourceText("/golden/Image.vforge"))
        val files = DesktopExporter.gradleProject(project)
        assertFalse(files.any { it.path.startsWith("src/main/resources/") }, "no bytes → no asset files")
    }

    @Test
    fun `wrapper jar and scripts are copied verbatim with gradlew marked executable`() {
        val files = DesktopExporter.gradleProject(demoProject())
        val jar = files.single { it.path == "gradle/wrapper/gradle-wrapper.jar" } as BinaryFile
        assertTrue(jar.bytes.contentEquals(resourceBytes("/scaffold/gradle-wrapper.jar")))

        val gradlew = files.single { it.path == "gradlew" } as BinaryFile
        assertTrue(gradlew.executable, "gradlew must be marked executable for ./gradlew to run on POSIX")

        val gradlewBat = files.single { it.path == "gradlew.bat" } as BinaryFile
        assertFalse(gradlewBat.executable)
    }
}

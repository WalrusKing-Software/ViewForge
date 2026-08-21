package viewforge.packages.compose.targets

import viewforge.project.ExportFile
import viewforge.project.ProjectCodec
import viewforge.project.TextFile
import viewforge.spi.GeneratedFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The KMP multi-target exporter (G9/M11, ADR-036). Uses the same image-free `Demo`/`RowBox` fixtures the
 * golden suite and [DesktopExporterTest] use, so the exported screen is provably the emitter's output — no
 * hand-authored golden that could drift. **Structural verification only:** the emitted Android project is
 * not compiled here (no Android SDK; the in-process Android compile gate is #219, ADR-038).
 */
class MultiplatformExporterTest {
    private fun resourceText(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }.bufferedReader().readText()

    private fun demoProject() = ProjectCodec.decode(resourceText("/golden/Demo.vforge"))

    private fun textOf(files: List<ExportFile>, path: String): String =
        (files.single { it.path == path } as TextFile).content

    @Test
    fun `sourceSetFor routes shared UI to commonMain and each entry point to its own set`() {
        // Shared UI is commonMain under either target.
        val screen = GeneratedFile("HomeScreen.kt", "")
        assertEquals("commonMain", DesktopTarget.sourceSetFor(screen))
        assertEquals("commonMain", AndroidTarget.sourceSetFor(screen))
        // Each entry point is claimed only by its own target.
        assertEquals("jvmMain", DesktopTarget.sourceSetFor(GeneratedFile(ComposeEntryPoints.MAIN_KT, "")))
        assertEquals("androidMain", AndroidTarget.sourceSetFor(GeneratedFile(ComposeEntryPoints.MAIN_ACTIVITY_KT, "")))
        assertEquals("androidMain", AndroidTarget.sourceSetFor(GeneratedFile(AndroidTarget.MANIFEST_XML, "")))
        // A target ignores another target's entry point (routes it to commonMain).
        assertEquals("commonMain", DesktopTarget.sourceSetFor(GeneratedFile(ComposeEntryPoints.MAIN_ACTIVITY_KT, "")))
        assertEquals("desktop", DesktopTarget.id)
        assertEquals("android", AndroidTarget.id)
    }

    @Test
    fun `multiplatform project has the expected KMP source-set layout`() {
        val files = MultiplatformExporter.multiplatformProject(demoProject())
        assertEquals(
            setOf(
                // Shared UI → commonMain (Demo has a theme, so an AppTheme wrapper is emitted, M8/H4).
                "src/commonMain/kotlin/HomeScreen.kt",
                "src/commonMain/kotlin/Theme.kt",
                // Per-platform entry points.
                "src/jvmMain/kotlin/Main.kt",
                "src/androidMain/kotlin/MainActivity.kt",
                "src/androidMain/AndroidManifest.xml",
                // Build config + wrapper.
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
    fun `commonMain screen is the same formatted output the desktop export emits`() {
        val project = demoProject()
        val shared = textOf(MultiplatformExporter.multiplatformProject(project), "src/commonMain/kotlin/HomeScreen.kt")
        val desktop = (DesktopExporter.looseFiles(project).single() as TextFile).content
        assertEquals(desktop.trimEnd('\n'), shared.trimEnd('\n'))
    }

    @Test
    fun `build script targets desktop and Android and is version-pinned`() {
        val build = textOf(MultiplatformExporter.multiplatformProject(demoProject()), "build.gradle.kts")
        assertContains(build, "kotlin(\"multiplatform\") version \"${GradleScaffold.KOTLIN_VERSION}\"")
        assertContains(build, "id(\"com.android.application\") version \"${MultiplatformScaffold.AGP_VERSION}\"")
        assertContains(build, "id(\"org.jetbrains.compose\") version \"${GradleScaffold.COMPOSE_VERSION}\"")
        assertContains(build, "jvm()")
        assertContains(build, "androidTarget()")
        assertContains(build, "implementation(compose.desktop.currentOs)")
        assertContains(build, "androidx.activity:activity-compose:${MultiplatformScaffold.ACTIVITY_COMPOSE_VERSION}")
        assertContains(build, "compileSdk = ${MultiplatformScaffold.ANDROID_COMPILE_SDK}")
        assertContains(build, "minSdk = ${MultiplatformScaffold.ANDROID_MIN_SDK}")
        assertContains(build, "targetSdk = ${MultiplatformScaffold.ANDROID_TARGET_SDK}")
        // App id derives from the slug and is a valid package (slug("Demo") = "demo").
        assertContains(build, "applicationId = \"dev.viewforge.demo\"")
        assertContains(build, "namespace = \"dev.viewforge.demo\"")
        assertContains(build, "mainClass = \"MainKt\"")

        val settings = textOf(MultiplatformExporter.multiplatformProject(demoProject()), "settings.gradle.kts")
        assertContains(settings, "rootProject.name = \"demo\"")
    }

    @Test
    fun `MainActivity is a ComponentActivity that sets content to the first screen`() {
        val activity =
            textOf(MultiplatformExporter.multiplatformProject(demoProject()), "src/androidMain/kotlin/MainActivity.kt")
        assertContains(activity, "class MainActivity : ComponentActivity()")
        assertContains(activity, "override fun onCreate(savedInstanceState: Bundle?)")
        assertContains(activity, "super.onCreate(savedInstanceState)")
        assertContains(activity, "setContent {")
        // Demo has a theme, so the activity wraps the screen in the generated AppTheme (M8/H4).
        assertContains(activity, "AppTheme {")
        assertContains(activity, "HomeScreen()")
        assertFalse(
            activity.lineSequence().any { it.trimStart().startsWith("public ") },
            "MainActivity must omit redundant public",
        )
    }

    @Test
    fun `manifest binds the launcher activity by fully-qualified default-package name`() {
        val manifest =
            textOf(MultiplatformExporter.multiplatformProject(demoProject()), "src/androidMain/AndroidManifest.xml")
        // No leading dot ⇒ Android reads it as an absolute class name (the default-package MainActivity).
        assertContains(manifest, "android:name=\"MainActivity\"")
        assertContains(manifest, "android:exported=\"true\"")
        assertContains(manifest, "android.intent.action.MAIN")
        assertContains(manifest, "android.intent.category.LAUNCHER")
        assertContains(manifest, "android:label=\"Demo\"")
    }

    @Test
    fun `a project with no theme emits no Theme file and entry points use a default MaterialTheme`() {
        val bare = ProjectCodec.decode(resourceText("/golden/RowBox.vforge")) // empty theme
        val files = MultiplatformExporter.multiplatformProject(bare)
        assertFalse(files.any { it.path == "src/commonMain/kotlin/Theme.kt" })
        assertContains(textOf(files, "src/jvmMain/kotlin/Main.kt"), "MaterialTheme {")
        assertContains(textOf(files, "src/androidMain/kotlin/MainActivity.kt"), "MaterialTheme {")
    }

    @Test
    fun `image assets use the multiplatform Res drawable API and ship into composeResources (#223)`() {
        val project = ProjectCodec.decode(resourceText("/golden/Image.vforge"))
        val files = MultiplatformExporter.multiplatformProject(project) { byteArrayOf(1, 2, 3) }
        val screen = textOf(files, "src/commonMain/kotlin/ImageScreen.kt")
        // The commonMain resources API (renders on Android too), not the desktop painterResource(String).
        assertContains(screen, "import org.jetbrains.compose.resources.painterResource")
        assertContains(screen, "import dev.viewforge.image.generated.resources.Res")
        assertContains(screen, "painter = painterResource(Res.drawable.hero)")
        assertContains(screen, "painter = painterResource(Res.drawable.icon)")
        assertFalse("androidx.compose.ui.res.painterResource" in screen, "must not use the desktop image API")
        // Each referenced asset is shipped into the resources source set under its accessor file name, where the
        // resources plugin turns it into the Res.drawable accessor codegen references.
        val paths = files.map { it.path }.toSet()
        assertContains(paths, "src/commonMain/composeResources/drawable/hero.png")
        assertContains(paths, "src/commonMain/composeResources/drawable/icon.png")
        // The scaffold wires the resources dependency + the deterministic accessor package the import matches.
        val build = textOf(files, "build.gradle.kts")
        assertContains(build, "implementation(compose.components.resources)")
        assertContains(build, "packageOfResClass = \"dev.viewforge.image.generated.resources\"")
    }

    @Test
    fun `an unresolved image asset is skipped, shipping no drawable`() {
        val project = ProjectCodec.decode(resourceText("/golden/Image.vforge"))
        val files = MultiplatformExporter.multiplatformProject(project) { null }
        assertFalse(files.any { it.path.startsWith("src/commonMain/composeResources/") })
    }

    @Test
    fun `desktop Main entry point is unchanged from the desktop export after the shared refactor`() {
        val project = demoProject()
        val kmpMain = textOf(MultiplatformExporter.multiplatformProject(project), "src/jvmMain/kotlin/Main.kt")
        val desktopMain = textOf(DesktopExporter.gradleProject(project), "src/main/kotlin/Main.kt")
        assertEquals(desktopMain, kmpMain)
    }
}

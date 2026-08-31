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

    /** The significant lines (non-blank, non-`package`) of [source], so the KMP (packaged) and desktop
     * (default-package) outputs can be compared for everything *except* the package declaration the KMP
     * export adds — robust to the blank line KotlinPoet reflows around a package block (#356). */
    private fun withoutPackage(source: String): List<String> = source.lineSequence().map { it.trimEnd() }
        .filter { it.isNotBlank() && !it.trimStart().startsWith("package ") }
        .toList()

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
    fun `commonMain screen is the desktop output plus the app-package declaration (#356)`() {
        val project = demoProject()
        val shared = textOf(MultiplatformExporter.multiplatformProject(project), "src/commonMain/kotlin/HomeScreen.kt")
        val desktop = (DesktopExporter.looseFiles(project).single() as TextFile).content
        // The KMP screen lives in the app package (so the Android entry point can reference it without importing
        // a default-package symbol, #356); every other line is byte-identical to the desktop export.
        assertContains(shared, "package dev.viewforge.demo")
        assertEquals(withoutPackage(desktop), withoutPackage(shared))
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
    fun `MainActivity handles edge-to-edge insets with opaque bars and inset-padded content (#358)`() {
        val activity =
            textOf(MultiplatformExporter.multiplatformProject(demoProject()), "src/androidMain/kotlin/MainActivity.kt")
        // enableEdgeToEdge() clears the API-35 compatibility scrim over the system bars.
        assertContains(activity, "import androidx.activity.enableEdgeToEdge")
        assertContains(activity, "enableEdgeToEdge()")
        // A full-window Surface paints the bar regions solid (opaque, theme-coloured bars).
        assertContains(activity, "Surface(modifier = Modifier.fillMaxSize())")
        // Content is inset out from under the bars so its touch targets are not swallowed by the system bar (#4).
        assertContains(activity, "Box(modifier = Modifier.safeDrawingPadding())")
        assertContains(activity, "import androidx.compose.foundation.layout.safeDrawingPadding")
        // enableEdgeToEdge runs before setContent, and the screen renders inside the inset-padded Box.
        val setContentAt = activity.indexOf("setContent {")
        assertContains(activity.substring(0, setContentAt), "enableEdgeToEdge()")
    }

    @Test
    fun `the launcher activity, its package, and the namespace all agree so it instantiates at runtime (#356)`() {
        val files = MultiplatformExporter.multiplatformProject(demoProject())
        val manifest = textOf(files, "src/androidMain/AndroidManifest.xml")
        val activity = textOf(files, "src/androidMain/kotlin/MainActivity.kt")
        val build = textOf(files, "build.gradle.kts")
        // The activity class is generated in the applicationId package (NOT the default package) ...
        assertContains(activity, "package dev.viewforge.demo")
        // ... the module namespace equals that package ...
        assertContains(build, "namespace = \"dev.viewforge.demo\"")
        // ... and the manifest names it *relative to that namespace* (leading dot). AGP resolves this to the
        // merged FQN dev.viewforge.demo.MainActivity, which matches the compiled class. A bare name / default-
        // package activity would ClassNotFound at launch because AGP namespaces the manifest name (#356).
        assertContains(manifest, "android:name=\".MainActivity\"")
        // The shared UI sits in the same package, so MainActivity references it without importing a
        // default-package symbol (impossible in Kotlin) — the original reason the code was default-package.
        assertContains(textOf(files, "src/commonMain/kotlin/HomeScreen.kt"), "package dev.viewforge.demo")
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
        // Each `Res.drawable.<x>` accessor is a top-level extension property, so it must be imported too — not just
        // `Res`. Without these imports the KMP build fails with `Unresolved reference` (#322); the string-only golden
        // could not catch that because the accessor output is not compiled in-process (ADR-021).
        assertContains(screen, "import dev.viewforge.image.generated.resources.hero")
        assertContains(screen, "import dev.viewforge.image.generated.resources.icon")
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
    fun `desktop Main entry point matches the desktop export apart from the app-package declaration (#356)`() {
        val project = demoProject()
        val kmpMain = textOf(MultiplatformExporter.multiplatformProject(project), "src/jvmMain/kotlin/Main.kt")
        val desktopMain = textOf(DesktopExporter.gradleProject(project), "src/main/kotlin/Main.kt")
        // The KMP jvmMain Main shares the app package with the screens/activity (#356); otherwise unchanged.
        assertContains(kmpMain, "package dev.viewforge.demo")
        assertEquals(withoutPackage(desktopMain), withoutPackage(kmpMain))
    }

    @Test
    fun `a navigating project ships the App host in commonMain and renders it from MainActivity (#214)`() {
        val nav = ProjectCodec.decode(resourceText("/golden/Navigation.vforge"))
        val files = MultiplatformExporter.multiplatformProject(nav)
        // The screen-switching host (ADR-039) is emitted to commonMain so both entry points can render it —
        // without this the generated MainActivity calls App() with no definition and the KMP build fails.
        assertContains(textOf(files, "src/commonMain/kotlin/App.kt"), "fun App()")
        // The Android entry point renders the host, not a single screen directly.
        assertContains(textOf(files, "src/androidMain/kotlin/MainActivity.kt"), "App()")
    }

    @Test
    fun `a non-navigating project ships no App host, leaving the KMP bundle unchanged (#214)`() {
        // Demo has a single screen and never navigates, so no host is emitted.
        assertFalse(
            MultiplatformExporter.multiplatformProject(demoProject()).any {
                it.path ==
                    "src/commonMain/kotlin/App.kt"
            },
        )
    }

    @Test
    fun `the exported README explains Android SDK setup, not just the run command (#349)`() {
        // A user's first Android build fails with AGP's "SDK location not found" unless the README tells them
        // how to point Gradle at the SDK — so the README must document sdk.dir / ANDROID_HOME and assembleDebug.
        val readme = textOf(MultiplatformExporter.multiplatformProject(demoProject()), "README.md")
        assertContains(readme, "sdk.dir")
        assertContains(readme, "ANDROID_HOME")
        assertContains(readme, "assembleDebug")
    }
}

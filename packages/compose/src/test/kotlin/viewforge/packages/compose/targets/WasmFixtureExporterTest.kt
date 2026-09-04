package viewforge.packages.compose.targets

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural tests for the CI smoke-test fixture generator (#375). Verifies that
 * [WasmFixtureExporter.export] emits the expected file set, that its build script
 * contains the Karma headless-Chrome test task, and that a `SmokeTest.kt` is present.
 */
class WasmFixtureExporterTest {
    private fun exported(): Map<String, String> {
        val outDir = Files.createTempDirectory("wasm-fixture-test").toFile()
        try {
            WasmFixtureExporter.export(outDir)
            return outDir.walkTopDown()
                .filter { it.isFile }
                .associate { file ->
                    file.relativeTo(outDir).path.replace('\\', '/') to
                        (
                            if (file.name.endsWith(".kt") ||
                                file.name.endsWith(".kts") ||
                                file.name.endsWith(".html") ||
                                file.name.endsWith(".md") ||
                                file.name.endsWith(".properties") ||
                                file.name.endsWith(".xml") ||
                                file.name.endsWith(".gitignore") ||
                                file.name.endsWith(".js")
                            ) {
                                file.readText()
                            } else {
                                ""
                            }
                            )
                }
        } finally {
            outDir.deleteRecursively()
        }
    }

    @Test
    fun `fixture includes SmokeTest and wasmJsTest source set (#375)`() {
        val files = exported()
        assertNotNull(files["src/wasmJsTest/kotlin/SmokeTest.kt"], "SmokeTest.kt must be present")
        assertContains(files["src/wasmJsTest/kotlin/SmokeTest.kt"]!!, "fun smokeWasmLoads()")
        assertContains(files["src/wasmJsTest/kotlin/SmokeTest.kt"]!!, "assertTrue(true)")
    }

    @Test
    fun `fixture build script includes Karma headless Chrome test task (#375)`() {
        val build = exported()["build.gradle.kts"]!!
        assertContains(build, "testTask {")
        assertContains(build, "useKarma {")
        assertContains(build, "useChromeHeadlessNoSandbox()")
        assertContains(build, "wasmJsTest.dependencies {")
        assertContains(build, "implementation(kotlin(\"test\"))")
    }

    @Test
    fun `fixture includes the full standard KMP layout plus the smoke additions (#375)`() {
        val files = exported()
        // Standard KMP layout from MultiplatformExporter.
        // Screen named "Home" → Home.kt (requireFunctionName returns the name verbatim).
        assertTrue("src/commonMain/kotlin/Home.kt" in files, "commonMain screen")
        assertTrue("src/jvmMain/kotlin/Main.kt" in files, "jvmMain entry")
        assertTrue("src/androidMain/kotlin/MainActivity.kt" in files, "androidMain activity")
        assertTrue("src/androidMain/AndroidManifest.xml" in files, "android manifest")
        assertTrue("src/wasmJsMain/kotlin/Main.kt" in files, "wasmJsMain entry")
        assertTrue("src/wasmJsMain/resources/index.html" in files, "wasmJsMain index.html")
        assertTrue("gradlew" in files, "gradlew wrapper")
        // Smoke additions.
        assertTrue("src/wasmJsTest/kotlin/SmokeTest.kt" in files, "SmokeTest")
    }

    @Test
    fun `fixture wasmJsMain entry uses CanvasBasedWindow (#375)`() {
        val main = exported()["src/wasmJsMain/kotlin/Main.kt"]!!
        assertContains(main, "CanvasBasedWindow")
    }

    @Test
    fun `fixture build script has wasmJs and Karma but regular export does not (#375)`() {
        val fixtureSlug = GradleScaffold.slug("Smoke")
        val regularBuild = MultiplatformScaffold.buildGradle(fixtureSlug, smokeTest = false)
        val smokeBuild = MultiplatformScaffold.buildGradle(fixtureSlug, smokeTest = true)
        assertContains(smokeBuild, "useChromeHeadlessNoSandbox()")
        assertTrue(
            "useChromeHeadlessNoSandbox" !in regularBuild,
            "Regular user export must not contain Karma test config",
        )
    }
}

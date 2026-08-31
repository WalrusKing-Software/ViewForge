import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipFile

plugins {
    id("viewforge.compose-library")
}

// packages/compose: THE Compose framework package (ADR-008) — component & modifier definitions,
// runtime renderers, KotlinPoet emitters, and per-target exporters. The one and only SPI
// implementation until Phase 5 (ADR-007). This is the module the core boundary protects.
dependencies {
    api(projects.core.spi)
    implementation(projects.core.model)

    // Export writer + file types (M7): the desktop target exporter assembles an ExportFile bundle
    // that the guarded writer in core/project persists. core/project is framework-agnostic, so this
    // keeps the dependency direction clean (package → core).
    implementation(projects.core.project)

    // Codegen: KotlinPoet structural API only — never string concatenation (CLAUDE.md rule 4).
    implementation(libs.kotlinpoet)

    // Runtime renderers use real Compose composables (ARCHITECTURE §4.1).
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // Golden fixtures are loaded from real `.vforge` files via the project codec.
    testImplementation(projects.core.project)

    // Codegen compile gate (G2/GC-6): compile generated Kotlin in-process against Compose. The
    // compose-compiler plugin embeddable supplies the `@Composable` registrar; both embeddables are
    // forced to the pinned Kotlin version so the plugin matches the compiler it registers into.
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.compose.compiler.plugin.embeddable)
}

// --- Android compile gate (#219, ADR-038) -------------------------------------------------------
// The in-process Android compile gate (AndroidCompilationTest) feeds the generated `androidMain`
// entry point + shared screens to kctfork against the *Android* Compose/activity artifacts + an
// `android.jar` stub — NO Android SDK, so it runs on the SDK-less Forgejo runner beside codegen-verify.
//
// Those artifacts (`androidx.activity:activity-compose`, its transitive `androidx.*`, and the compose
// `-android` variants) are AAR-packaged, which a plain kotlin-jvm module cannot place on a compile
// classpath. This registers a small AAR → classes.jar artifact transform so a dedicated resolvable
// configuration resolves the whole transitive AAR graph to jars, which the test task hands to kctfork
// via a system property. android-all (the stub) is a plain jar and needs no transform.
val artifactType = Attribute.of("artifactType", String::class.java)

abstract class ExtractAarClassesJar : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val aar = inputArtifact.get().asFile
        ZipFile(aar).use { zip ->
            // A resource-only AAR carries no classes.jar; there is then nothing to compile against.
            val entry = zip.getEntry("classes.jar")
            if (entry != null) {
                val out = outputs.file("${aar.nameWithoutExtension}-classes.jar")
                zip.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}

dependencies {
    registerTransform(ExtractAarClassesJar::class.java) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }
}

val androidCompileGate by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // The android.jar stub (Robolectric android-all publishes the platform stubs as a plain jar) …
    androidCompileGate(libs.robolectric.android.all)
    // … plus the Android entry-point surface (ComponentActivity/setContent) and its transitive AARs,
    // which the transform above flattens to jars.
    androidCompileGate(libs.androidx.activity.compose)
}

tasks.test {
    // Resolve the gate classpath as jars (applying the AAR → classes.jar transform) and expose it to
    // AndroidCompilationTest. Resolution is lazy (execution-time), so a `test` run that skips that
    // class still pays nothing beyond wiring.
    val gateClasspath = androidCompileGate.incoming.artifactView {
        attributes.attribute(artifactType, "jar")
    }.files
    inputs.files(gateClasspath).withPropertyName("androidCompileGateClasspath")
    doFirst {
        systemProperty("viewforge.android.gate.classpath", gateClasspath.asPath)
    }
}

// The golden `.kt` fixtures under test resources are codegen *output*, asserted byte-for-byte against
// what the emitter produces — they are not hand-written source. Exempt them from spotless/ktlint so
// the formatter can never rewrite a fixture out from under the golden tests (they must match
// KotlinPoet's emission, not ktlint's opinion).
spotless {
    kotlin {
        targetExclude("src/test/resources/**")
    }
}

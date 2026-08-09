// Root build script. ViewForge configures modules through convention plugins in build-logic;
// the root deliberately holds almost nothing so per-module concerns stay in each module.

val allTests =
    tasks.register("allTests") {
        group = "verification"
        description = "Runs the test suite across every module (PROJECT_PLAN §3.4 build gate)."
    }

// Wire only leaf modules (those that apply the Kotlin plugin via a convention). The grouping
// projects :core, :editor, :packages have no build script and therefore no `test` task.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        allTests.configure { dependsOn(tasks.named("test")) }
    }
}

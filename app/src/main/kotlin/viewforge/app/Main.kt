package viewforge.app

/**
 * Desktop entry point. For M0 this is a plain main() that proves the application module builds
 * and the module graph links. It becomes the Compose Desktop window (application { Window { ... } }
 * hosting editor/shell) at M2, and gains native packaging at M10 (see viewforge.compose-app).
 */
fun main() {
    println("ViewForge — editor shell arrives at M2.")
}

package viewforge.prefs

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the per-user configuration directory ViewForge stores its editor preferences in — the
 * platform-conventional location, never inside a project (ADR-023):
 *
 * - **Windows:** `%APPDATA%\ViewForge` (falling back to `~/AppData/Roaming/ViewForge`).
 * - **macOS:** `~/Library/Application Support/ViewForge`.
 * - **Linux/other:** `$XDG_CONFIG_HOME/viewforge` (falling back to `~/.config/viewforge`).
 *
 * The directory is *not* created here — [PreferencesStore]'s guarded write creates it on first save.
 * Every input is an injectable parameter so tests resolve against a temp home/APPDATA rather than the
 * real machine.
 */
object ConfigDir {
    private const val APP_DIR = "ViewForge" // Windows / macOS: display-cased app folder.
    private const val UNIX_DIR = "viewforge" // XDG convention: lowercase.

    fun resolve(
        os: String = System.getProperty("os.name").orEmpty(),
        home: String = System.getProperty("user.home").orEmpty(),
        env: (String) -> String? = System::getenv,
    ): Path {
        val name = os.lowercase()
        return when {
            name.contains("win") -> {
                val appData = env("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let { Paths.get(it) } ?: Paths.get(home, "AppData", "Roaming")).resolve(APP_DIR)
            }
            name.contains("mac") || name.contains("darwin") ->
                Paths.get(home, "Library", "Application Support", APP_DIR)
            else -> {
                val xdg = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdg?.let { Paths.get(it) } ?: Paths.get(home, ".config")).resolve(UNIX_DIR)
            }
        }
    }
}

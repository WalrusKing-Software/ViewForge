package viewforge.prefs

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-user config-dir resolution (ADR-023). Every input is injected so this asserts the platform
 * conventions deterministically. Expected paths are built with [Paths.get] so the comparison uses the
 * running JVM's own separators — the assertions hold whether the suite runs on Windows or Linux CI.
 */
class ConfigDirTest {
    @Test
    fun `windows uses APPDATA`() {
        val appData = "C:\\Users\\dev\\AppData\\Roaming"
        val dir = ConfigDir.resolve(os = "Windows 11", home = "C:\\Users\\dev", env = {
            if (it ==
                "APPDATA"
            ) {
                appData
            } else {
                null
            }
        })
        assertEquals(Paths.get(appData).resolve("ViewForge"), dir)
    }

    @Test
    fun `windows falls back to the home Roaming path when APPDATA is unset`() {
        val dir = ConfigDir.resolve(os = "Windows 10", home = "C:\\Users\\dev", env = { null })
        assertEquals(Paths.get("C:\\Users\\dev", "AppData", "Roaming", "ViewForge"), dir)
    }

    @Test
    fun `macOS uses Application Support`() {
        val dir = ConfigDir.resolve(os = "Mac OS X", home = "/Users/dev", env = { null })
        assertEquals(Paths.get("/Users/dev", "Library", "Application Support", "ViewForge"), dir)
    }

    @Test
    fun `linux honours XDG_CONFIG_HOME`() {
        val xdg = "/home/dev/.xdg"
        val dir = ConfigDir.resolve(os = "Linux", home = "/home/dev", env = {
            if (it ==
                "XDG_CONFIG_HOME"
            ) {
                xdg
            } else {
                null
            }
        })
        assertEquals(Paths.get(xdg).resolve("viewforge"), dir)
    }

    @Test
    fun `linux falls back to dot-config`() {
        val dir = ConfigDir.resolve(os = "Linux", home = "/home/dev", env = { null })
        assertEquals(Paths.get("/home/dev", ".config", "viewforge"), dir)
    }
}

package viewforge.prefs

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editor-preferences store (ADR-023). Loading is total — it never throws, degrading to defaults for
 * anything it can't read — because losing remembered chrome is acceptable where losing a document is not.
 * Saving is atomic and confined to the config dir (it reuses the guarded writer).
 */
class PreferencesStoreTest {
    private fun tempDir() = Files.createTempDirectory("vf-prefs")

    @Test
    fun `absent file loads defaults`() {
        val prefs = PreferencesStore.load(tempDir())
        assertEquals(EditorPreferences(), prefs)
        assertTrue(prefs.panelLayout.paletteVisible)
        assertEquals(PanelLayout.DEFAULT_TREE_WIDTH, prefs.panelLayout.treeWidth)
        // The code preview defaults to hidden and to its own (wider) default width (#52).
        assertFalse(prefs.panelLayout.codePreviewVisible)
        assertEquals(PanelLayout.DEFAULT_CODE_PREVIEW_WIDTH, prefs.panelLayout.codePreviewWidth)
    }

    @Test
    fun `save then load round-trips the layout`() {
        val dir = tempDir()
        val prefs = EditorPreferences(
            panelLayout = PanelLayout(
                paletteVisible = false,
                treeVisible = true,
                inspectorVisible = false,
                codePreviewVisible = true,
                paletteWidth = 200f,
                treeWidth = 260f,
                inspectorWidth = 300f,
                codePreviewWidth = 700f, // wider than a side panel's MAX_WIDTH (520) — its own bound (#115)
                codePreviewWrap = true,
            ),
            autosaveIntervalSeconds = 30,
        )
        PreferencesStore.save(prefs, dir)
        assertEquals(prefs, PreferencesStore.load(dir))
    }

    @Test
    fun `recent projects round-trip and are sanitized (deduped, blanks dropped, capped) on load`() {
        val dir = tempDir()
        val prefs = EditorPreferences(recentProjects = listOf("/a.vforge", "/b.vforge"))
        PreferencesStore.save(prefs, dir)
        assertEquals(prefs, PreferencesStore.load(dir))

        // A hand-edited file with blanks, duplicates, and too many entries is cleaned on load.
        val many = (1..(RecentProjects.MAX + 5)).joinToString(",") { "\"/p$it.vforge\"" }
        dir.resolve(PreferencesStore.FILE_NAME).writeText(
            """{ "prefsVersion": 1, "recentProjects": ["/a.vforge", "", "/a.vforge", $many] }""",
        )
        val loaded = PreferencesStore.load(dir).recentProjects
        assertEquals(RecentProjects.MAX, loaded.size)
        assertEquals("/a.vforge", loaded.first())
        assertEquals(loaded.distinct(), loaded)
        assertFalse(loaded.any { it.isBlank() })
    }

    @Test
    fun `favorite components round-trip and are sanitized (deduped, blanks dropped) on load`() {
        val dir = tempDir()
        val prefs = EditorPreferences(favoriteComponents = listOf("compose.material3.Text", "01ABC"))
        PreferencesStore.save(prefs, dir)
        assertEquals(prefs, PreferencesStore.load(dir))

        // A hand-edited file with blanks and duplicates is cleaned on load, keeping order and staying uncapped.
        dir.resolve(PreferencesStore.FILE_NAME).writeText(
            """{ "prefsVersion": 1, "favoriteComponents": ["a", "", "a", "b"] }""",
        )
        assertEquals(listOf("a", "b"), PreferencesStore.load(dir).favoriteComponents)
    }

    @Test
    fun `autosave interval defaults when absent and clamps out-of-range values on load`() {
        val dir = tempDir()
        // No prefs file -> the default cadence (matching #54's original constant).
        assertEquals(
            EditorPreferences.DEFAULT_AUTOSAVE_INTERVAL_SECONDS,
            PreferencesStore.load(dir).autosaveIntervalSeconds,
        )

        // A too-small value (would hammer the disk) is clamped up to the minimum on load.
        dir.resolve(PreferencesStore.FILE_NAME).writeText("""{ "prefsVersion": 1, "autosaveIntervalSeconds": 0 }""")
        assertEquals(
            EditorPreferences.MIN_AUTOSAVE_INTERVAL_SECONDS,
            PreferencesStore.load(dir).autosaveIntervalSeconds,
        )

        // A too-large value (would effectively disable recovery) is clamped down to the maximum.
        dir.resolve(
            PreferencesStore.FILE_NAME,
        ).writeText("""{ "prefsVersion": 1, "autosaveIntervalSeconds": 100000 }""")
        assertEquals(
            EditorPreferences.MAX_AUTOSAVE_INTERVAL_SECONDS,
            PreferencesStore.load(dir).autosaveIntervalSeconds,
        )
    }

    @Test
    fun `a pre-52 file without code-preview keys loads the code-preview defaults`() {
        val dir = tempDir()
        // A file written before #52 carries only the three side panels; the code-preview fields must
        // fall back to their defaults rather than being lost (forward tolerance).
        dir.resolve(PreferencesStore.FILE_NAME).writeText(
            """{ "prefsVersion": 1, "panelLayout": { "treeWidth": 260.0, "inspectorVisible": false } }""",
        )
        val layout = PreferencesStore.load(dir).panelLayout
        assertEquals(260f, layout.treeWidth) // its own known field survives
        assertFalse(layout.inspectorVisible)
        assertFalse(layout.codePreviewVisible)
        assertEquals(PanelLayout.DEFAULT_CODE_PREVIEW_WIDTH, layout.codePreviewWidth)
    }

    @Test
    fun `history depth and default export path round-trip, default, and clamp on load`() {
        val dir = tempDir()
        // Absent file -> the shipped defaults (undo depth matches History's, export path blank).
        val defaults = PreferencesStore.load(dir)
        assertEquals(EditorPreferences.DEFAULT_HISTORY_DEPTH, defaults.historyDepth)
        assertEquals("", defaults.defaultExportPath)

        // Both survive a save/load round-trip.
        val prefs = EditorPreferences(historyDepth = 500, defaultExportPath = "/home/me/out")
        PreferencesStore.save(prefs, dir)
        assertEquals(prefs, PreferencesStore.load(dir))

        // A too-small depth (would wedge undo off) clamps up; a pre-105 file omitting the keys gets defaults.
        dir.resolve(PreferencesStore.FILE_NAME).writeText("""{ "prefsVersion": 1, "historyDepth": 0 }""")
        val loaded = PreferencesStore.load(dir)
        assertEquals(EditorPreferences.MIN_HISTORY_DEPTH, loaded.historyDepth)
        assertEquals("", loaded.defaultExportPath) // absent key -> default (forward tolerance)

        // A too-large depth clamps down.
        dir.resolve(PreferencesStore.FILE_NAME).writeText("""{ "prefsVersion": 1, "historyDepth": 100000 }""")
        assertEquals(EditorPreferences.MAX_HISTORY_DEPTH, PreferencesStore.load(dir).historyDepth)
    }

    @Test
    fun `chrome theme round-trips and defaults to dark when absent`() {
        val dir = tempDir()
        // Absent file -> chrome defaults to dark, matching the previously hardcoded scheme (#104).
        assertTrue(PreferencesStore.load(dir).chromeDark)

        // A light-chrome choice survives a save/load round-trip.
        PreferencesStore.save(EditorPreferences(chromeDark = false), dir)
        assertFalse(PreferencesStore.load(dir).chromeDark)

        // A pre-104 file omitting the field falls back to the dark default (forward tolerance).
        dir.resolve(PreferencesStore.FILE_NAME).writeText("""{ "prefsVersion": 1, "autosaveIntervalSeconds": 10 }""")
        assertTrue(PreferencesStore.load(dir).chromeDark)
    }

    @Test
    fun `launch state round-trips and defaults to first-run with no remembered path`() {
        val dir = tempDir()
        // Absent file -> a fresh install: never launched, no last project (so #156 seeds the sample once).
        val defaults = PreferencesStore.load(dir)
        assertFalse(defaults.hasLaunched)
        assertEquals("", defaults.lastProjectPath)

        // Both survive a save/load round-trip once the app has run and opened a document.
        val prefs = EditorPreferences(hasLaunched = true, lastProjectPath = "/home/me/app.vforge")
        PreferencesStore.save(prefs, dir)
        assertEquals(prefs, PreferencesStore.load(dir))

        // A pre-156 file omitting the keys falls back to the first-run defaults (forward tolerance).
        dir.resolve(PreferencesStore.FILE_NAME).writeText("""{ "prefsVersion": 1, "autosaveIntervalSeconds": 10 }""")
        val loaded = PreferencesStore.load(dir)
        assertFalse(loaded.hasLaunched)
        assertEquals("", loaded.lastProjectPath)
    }

    @Test
    fun `save writes preferences_json inside the config dir`() {
        val dir = tempDir()
        PreferencesStore.save(EditorPreferences(), dir)
        val file = dir.resolve(PreferencesStore.FILE_NAME)
        assertTrue(Files.exists(file))
        assertTrue(file.readText().contains("panelLayout"))
    }

    @Test
    fun `save creates the config dir when it does not exist yet`() {
        val dir = tempDir().resolve("nested").resolve("ViewForge")
        PreferencesStore.save(EditorPreferences(), dir)
        assertTrue(Files.exists(dir.resolve(PreferencesStore.FILE_NAME)))
    }

    @Test
    fun `a corrupt file falls back to defaults instead of throwing`() {
        val dir = tempDir()
        dir.resolve(PreferencesStore.FILE_NAME).writeText("{ this is not json ]")
        assertEquals(EditorPreferences(), PreferencesStore.load(dir))
    }

    @Test
    fun `out-of-range widths are clamped on load`() {
        val dir = tempDir()
        // A hand-edited file wedging panels to zero and off-screen must never survive the load.
        dir.resolve(PreferencesStore.FILE_NAME).writeText(
            """{ "prefsVersion": 1, "panelLayout": { "paletteWidth": 0.0, "inspectorWidth": 99999.0 } }""",
        )
        val layout = PreferencesStore.load(dir).panelLayout
        assertEquals(PanelLayout.MIN_WIDTH, layout.paletteWidth)
        assertEquals(PanelLayout.MAX_WIDTH, layout.inspectorWidth)
    }

    @Test
    fun `the code preview has its own wider width bound (#115)`() {
        val dir = tempDir()
        // 700 exceeds a side panel's MAX_WIDTH (520) but is within the code preview's bound: it survives.
        // 99999 is beyond even that, so it clamps to the code preview's own maximum.
        dir.resolve(PreferencesStore.FILE_NAME).writeText(
            """{ "prefsVersion": 1, "panelLayout": { "codePreviewWidth": 700.0, "treeWidth": 99999.0 } }""",
        )
        val within = PreferencesStore.load(dir).panelLayout
        assertEquals(700f, within.codePreviewWidth) // not clamped down to the side-panel MAX_WIDTH
        assertEquals(PanelLayout.MAX_WIDTH, within.treeWidth) // a side panel still uses the narrower bound

        dir.resolve(PreferencesStore.FILE_NAME).writeText(
            """{ "prefsVersion": 1, "panelLayout": { "codePreviewWidth": 99999.0 } }""",
        )
        assertEquals(PanelLayout.MAX_CODE_PREVIEW_WIDTH, PreferencesStore.load(dir).panelLayout.codePreviewWidth)
    }

    @Test
    fun `unknown keys and a newer version are tolerated, keeping known fields`() {
        val dir = tempDir()
        // A newer build may add fields; an older build must still read its own, not discard the layout.
        dir.resolve(PreferencesStore.FILE_NAME).writeText(
            """{ "prefsVersion": 99, "panelLayout": { "treeWidth": 222.0, "futureField": true }, "windowWidth": 1000 }""",
        )
        val prefs = PreferencesStore.load(dir)
        assertEquals(99, prefs.prefsVersion)
        assertEquals(222f, prefs.panelLayout.treeWidth)
    }
}

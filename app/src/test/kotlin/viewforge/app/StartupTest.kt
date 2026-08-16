package viewforge.app

import viewforge.prefs.EditorPreferences
import viewforge.project.LoadFailure
import viewforge.project.LoadResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The launch-document decision (#156): the sample only on the very first run, otherwise the last-open
 * document or a blank canvas. Pure, so it is tested here without a UI or a real config directory — the
 * `load` function is injected.
 */
class StartupTest {
    private val project = sampleProject()

    @Test
    fun `first run seeds the sample and never touches disk`() {
        var loaded = false
        val seed = resolveStartupSeed(EditorPreferences(hasLaunched = false, lastProjectPath = "/ignored.vforge")) {
            loaded = true
            LoadResult.Success(project)
        }
        assertEquals(StartupSeed.FirstRunSample, seed)
        assertFalse(loaded, "first run must not read the last-project file")
    }

    @Test
    fun `a launched editor with no remembered path opens blank`() {
        val seed = resolveStartupSeed(EditorPreferences(hasLaunched = true, lastProjectPath = "")) {
            error("load must not be called when there is no path")
        }
        assertEquals(StartupSeed.Blank, seed)
    }

    @Test
    fun `a remembered path that loads is restored as the last session`() {
        val prefs = EditorPreferences(hasLaunched = true, lastProjectPath = "/projects/app.vforge")
        var asked: Path? = null
        val seed = resolveStartupSeed(prefs) { p ->
            asked = p
            LoadResult.Success(project)
        }
        val last = assertIs<StartupSeed.LastSession>(seed)
        assertEquals(Path.of("/projects/app.vforge"), asked)
        assertEquals(project, last.project)
        assertEquals(Path.of("/projects/app.vforge"), last.path)
        assertFalse(last.migrated, "a current-schema file was not migrated")
    }

    @Test
    fun `restoring an older-schema file carries the migrated flag for the D9 backup`() {
        val prefs = EditorPreferences(hasLaunched = true, lastProjectPath = "/old.vforge")
        val seed = resolveStartupSeed(prefs) { LoadResult.Success(project, migratedFromVersion = 1) }
        assertTrue(assertIs<StartupSeed.LastSession>(seed).migrated)
    }

    @Test
    fun `a remembered path that fails to load falls back to blank`() {
        val prefs = EditorPreferences(hasLaunched = true, lastProjectPath = "/gone.vforge")
        val seed = resolveStartupSeed(prefs) { LoadResult.Failure(LoadFailure.IO_ERROR, "no such file") }
        assertEquals(StartupSeed.Blank, seed)
    }
}

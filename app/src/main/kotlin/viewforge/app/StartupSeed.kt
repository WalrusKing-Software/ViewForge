package viewforge.app

import viewforge.model.Project
import viewforge.prefs.EditorPreferences
import viewforge.project.LoadResult
import viewforge.project.ProjectStore
import java.nio.file.Path

/**
 * What document the editor should open on launch (#156). Kept a pure decision — separate from the
 * Compose wiring in [main] — so the first-run / restore / blank branches are unit-testable without a UI
 * or a real config directory.
 */
sealed interface StartupSeed {
    /** First run on this machine: show the sample [sampleProject] so a new user sees a working example. */
    data object FirstRunSample : StartupSeed

    /** A blank canvas — the user had no document (or a never-saved one) open when they last closed. */
    data object Blank : StartupSeed

    /**
     * Reopen the document open at last close. [migrated] carries [LoadResult.Success.migratedFromVersion]
     * so the first save can back up the original older-schema file before overwriting it (D9).
     */
    data class LastSession(val project: Project, val path: Path, val migrated: Boolean) : StartupSeed
}

/**
 * Decide the launch document from persisted [prefs] (#156):
 * - never launched → [StartupSeed.FirstRunSample];
 * - no remembered path → [StartupSeed.Blank];
 * - a remembered path that [load]s → [StartupSeed.LastSession], else (moved / deleted / corrupt) fall
 *   back to [StartupSeed.Blank] silently — startup is not the place for an error dialog, and the stale
 *   entry self-cleans from Open Recent when next used.
 *
 * [load] is injected (defaults to [ProjectStore.load]) so the decision can be tested without touching disk.
 */
fun resolveStartupSeed(prefs: EditorPreferences, load: (Path) -> LoadResult = ProjectStore::load): StartupSeed {
    if (!prefs.hasLaunched) return StartupSeed.FirstRunSample
    val path = prefs.lastProjectPath.ifBlank { null }?.let(Path::of) ?: return StartupSeed.Blank
    return when (val result = load(path)) {
        is LoadResult.Success -> StartupSeed.LastSession(result.project, path, result.migratedFromVersion != null)
        is LoadResult.Failure -> StartupSeed.Blank
    }
}

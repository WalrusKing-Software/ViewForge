package viewforge.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.SCHEMA_VERSION
import viewforge.project.migrations.M1to2
import viewforge.project.migrations.M2to3
import viewforge.project.migrations.M3to4
import viewforge.project.migrations.M4to5
import viewforge.project.migrations.M5to6
import viewforge.project.migrations.M6to7

/**
 * One step in the schema upgrade chain (DATA_MODEL §10). Migrations operate on the raw [JsonObject],
 * never on deserialized data classes — otherwise every historical version of every class would have
 * to be kept alive forever. Steps are chained 1→2→3→4→5→6→7 and never skipped.
 */
interface Migration {
    val fromVersion: Int
    val toVersion: Int

    fun migrate(document: JsonObject): JsonObject
}

/** Raised when a document cannot be brought up to [SchemaMigrations.CURRENT]. */
class MigrationException(message: String) : RuntimeException(message)

/**
 * The registry and runner for schema migrations. Steps are chained 1->2->3->4->5->6->7 and selected by
 * [Migration.fromVersion]; registering a new step is a one-line addition to [ALL] plus a fixture.
 */
object SchemaMigrations {
    /** The schema version this build understands. Mirrors the model's [SCHEMA_VERSION]. */
    const val CURRENT: Int = SCHEMA_VERSION

    /** Registered migrations, in any order (the runner selects by [Migration.fromVersion]). */
    val ALL: List<Migration> = listOf(M1to2, M2to3, M3to4, M4to5, M5to6, M6to7)

    /** Reads `schemaVersion` from a parsed document, or null if absent/non-numeric. */
    fun readVersion(document: JsonObject): Int? = (document["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()

    /**
     * Applies chained migrations to bring [document] from [fromVersion] up to [CURRENT].
     * Callers must reject a document newer than [CURRENT] first (never migrate downward).
     *
     * @throws MigrationException if a step in the chain is missing or does not advance by exactly 1.
     */
    fun migrateToCurrent(document: JsonObject, fromVersion: Int, migrations: List<Migration> = ALL): JsonObject =
        run(document, fromVersion, CURRENT, migrations)

    /**
     * Chains [migrations] to advance [document] from [fromVersion] to [toVersion], one version at a
     * time. Exposed (rather than only [migrateToCurrent]) so the chaining logic is testable while
     * [CURRENT] is still 1 and there are no real migrations yet.
     *
     * @throws MigrationException if a step is missing or does not advance by exactly one version.
     */
    fun run(document: JsonObject, fromVersion: Int, toVersion: Int, migrations: List<Migration> = ALL): JsonObject {
        require(fromVersion <= toVersion) { "cannot migrate downward (from $fromVersion to $toVersion)" }
        var current = document
        var version = fromVersion
        while (version < toVersion) {
            val step = migrations.firstOrNull { it.fromVersion == version }
                ?: throw MigrationException("No migration from schema version $version to ${version + 1}")
            if (step.toVersion != version + 1) {
                throw MigrationException(
                    "Migration ${step.fromVersion}->${step.toVersion} must advance by exactly one version",
                )
            }
            current = step.migrate(current)
            version = step.toVersion
        }
        return current
    }
}

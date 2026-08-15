package viewforge.project

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import viewforge.model.Project
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Why a load failed. Each maps to a clear, user-facing diagnostic (ARCHITECTURE §9, PF-7). */
enum class LoadFailure {
    FILE_TOO_LARGE,
    IO_ERROR,
    MALFORMED,
    MISSING_VERSION,
    NEWER_SCHEMA,
    MIGRATION_FAILED,
    VALIDATION_FAILED,
}

/** Outcome of [ProjectStore.load]. A load never throws for an expected failure and never writes. */
sealed interface LoadResult {
    /**
     * A loaded project. [migratedFromVersion] is the on-disk `schemaVersion` when it was **older** than
     * [SchemaMigrations.CURRENT] and therefore migrated on the way in, or null when the file was already
     * current. The caller uses it to back up the original before the first save overwrites it with the
     * migrated form (DATA_MODEL §10 rule 6 / FEATURES D9).
     */
    data class Success(val project: Project, val migratedFromVersion: Int? = null) : LoadResult

    data class Failure(val kind: LoadFailure, val detail: String) : LoadResult
}

/**
 * Reads and writes `.vforge` files: size/version gating, migration, decode, and safety validation on
 * the way in; the guarded, atomic writer on the way out.
 *
 * A failed load reports *what* went wrong and leaves the source file untouched (PF-7) — it never
 * writes a partially-parsed document back.
 */
object ProjectStore {
    fun load(
        path: Path,
        limits: VforgeLimits = VforgeLimits.DEFAULT,
        migrations: List<Migration> = SchemaMigrations.ALL,
    ): LoadResult {
        val size =
            try {
                Files.size(path)
            } catch (e: IOException) {
                return LoadResult.Failure(LoadFailure.IO_ERROR, e.message ?: "could not stat file")
            }
        if (size > limits.maxFileBytes) {
            return LoadResult.Failure(
                LoadFailure.FILE_TOO_LARGE,
                "file is $size bytes; limit is ${limits.maxFileBytes} (PF-2)",
            )
        }

        val text =
            try {
                Files.readString(path, StandardCharsets.UTF_8)
            } catch (e: IOException) {
                return LoadResult.Failure(LoadFailure.IO_ERROR, e.message ?: "could not read file")
            }

        val root =
            try {
                VforgeJson.parseToJsonElement(text) as? JsonObject
                    ?: return LoadResult.Failure(LoadFailure.MALFORMED, "root is not a JSON object")
            } catch (e: SerializationException) {
                return LoadResult.Failure(LoadFailure.MALFORMED, e.message ?: "invalid JSON")
            }

        val version = SchemaMigrations.readVersion(root)
            ?: return LoadResult.Failure(LoadFailure.MISSING_VERSION, "document has no numeric schemaVersion")
        if (version > SchemaMigrations.CURRENT) {
            return LoadResult.Failure(
                LoadFailure.NEWER_SCHEMA,
                "file is schema v$version; this build supports up to v${SchemaMigrations.CURRENT}",
            )
        }

        val migrated =
            try {
                SchemaMigrations.migrateToCurrent(root, version, migrations)
            } catch (e: MigrationException) {
                return LoadResult.Failure(LoadFailure.MIGRATION_FAILED, e.message ?: "migration failed")
            }

        val project =
            try {
                VforgeJson.decodeFromJsonElement(Project.serializer(), migrated)
            } catch (e: SerializationException) {
                return LoadResult.Failure(LoadFailure.MALFORMED, e.message ?: "could not decode project")
            }

        return try {
            ProjectValidator.validate(project, limits)
            // Record the pre-migration version so a save can back up the original first (D9).
            LoadResult.Success(project, migratedFromVersion = version.takeIf { it < SchemaMigrations.CURRENT })
        } catch (e: ProjectValidationException) {
            LoadResult.Failure(LoadFailure.VALIDATION_FAILED, e.message ?: "validation failed")
        }
    }

    /**
     * Serializes [project] and writes it through the [GuardedWriter] (atomic). When [root] is null
     * the destination's own directory is used as the permitted root.
     */
    fun save(project: Project, path: Path, root: Path? = null, backup: Boolean = false) {
        val text = ProjectCodec.encode(project)
        val effectiveRoot = root ?: path.toAbsolutePath().normalize().parent
        GuardedWriter.write(path, text, root = effectiveRoot, backup = backup)
    }
}

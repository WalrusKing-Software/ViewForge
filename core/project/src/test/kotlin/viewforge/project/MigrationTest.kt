package viewforge.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    /** A migration that stamps a marker key so chaining is observable. */
    private fun stamp(from: Int, to: Int, key: String) = object : Migration {
        override val fromVersion = from
        override val toVersion = to

        override fun migrate(document: JsonObject): JsonObject = JsonObject(document + (key to JsonPrimitive(true)))
    }

    private fun docAt(version: Int) = JsonObject(mapOf("schemaVersion" to JsonPrimitive(version)))

    @Test
    fun `CURRENT mirrors the model schema version`() {
        assertEquals(SCHEMA_VERSION, SchemaMigrations.CURRENT)
    }

    @Test
    fun `no migration runs when already at the target version`() {
        val doc = docAt(1)
        assertEquals(doc, SchemaMigrations.run(doc, fromVersion = 1, toVersion = 1, migrations = emptyList()))
    }

    @Test
    fun `migrations chain one version at a time in order`() {
        val result =
            SchemaMigrations.run(
                docAt(1),
                fromVersion = 1,
                toVersion = 3,
                migrations = listOf(stamp(2, 3, "b"), stamp(1, 2, "a")),
            )
        assertTrue("a" in result.keys && "b" in result.keys, "both migrations should have run")
    }

    @Test
    fun `a missing step in the chain fails loudly`() {
        assertFailsWith<MigrationException> {
            SchemaMigrations.run(docAt(1), 1, 3, listOf(stamp(1, 2, "a"))) // no 2->3
        }
    }

    @Test
    fun `a step that advances by more than one version is rejected`() {
        assertFailsWith<MigrationException> {
            SchemaMigrations.run(docAt(1), 1, 3, listOf(stamp(1, 3, "jump")))
        }
    }

    @Test
    fun `readVersion parses the field and returns null when absent`() {
        assertEquals(7, SchemaMigrations.readVersion(docAt(7)))
        assertNull(SchemaMigrations.readVersion(JsonObject(emptyMap())))
    }

    @Test
    fun `loading a newer schema than supported fails with a clear result`() {
        val tmp = Files.createTempDirectory("vforge-newer").resolve("future.vforge")
        Files.writeString(tmp, """{"schemaVersion": 999, "id": "x", "name": "x"}""")
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Failure && result.kind == LoadFailure.NEWER_SCHEMA, "got $result")
    }

    @Test
    fun `M1to2 stamps the version and leaves the rest of the document untouched`() {
        val v1 = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "id" to JsonPrimitive("x")))
        val v2 = viewforge.project.migrations.M1to2.migrate(v1)
        assertEquals(2, SchemaMigrations.readVersion(v2))
        assertEquals(JsonPrimitive("x"), v2["id"])
    }

    @Test
    fun `M2to3 stamps the version and leaves the rest of the document untouched`() {
        val v2 = JsonObject(mapOf("schemaVersion" to JsonPrimitive(2), "id" to JsonPrimitive("x")))
        val v3 = viewforge.project.migrations.M2to3.migrate(v2)
        assertEquals(3, SchemaMigrations.readVersion(v3))
        assertEquals(JsonPrimitive("x"), v3["id"])
    }

    @Test
    fun `M3to4 rewrites record fields and sample cells to the recursive v4 shape`() {
        // A v3 list-of-record state field: flat `{name, scalar}` record fields and bare-primitive sample cells.
        val v3 = VforgeJson.parseToJsonElement(
            """
            {
              "schemaVersion": 3,
              "id": "x",
              "screens": [
                {
                  "id": "s",
                  "state": [
                    {
                      "name": "rows",
                      "type": { "kind": "listOfRecord", "fields": [ { "name": "label", "scalar": "STRING" } ] },
                      "sample": { "kind": "rows", "rows": [ { "label": "Ada" } ] }
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        ) as JsonObject
        val v4 = viewforge.project.migrations.M3to4.migrate(v3)

        assertEquals(4, SchemaMigrations.readVersion(v4))
        // The record field is now `{name, type:{kind:"scalar", scalar}}`; the cell is `{kind:"scalar", value}`.
        val state = ((v4["screens"] as JsonArray)[0] as JsonObject)["state"] as JsonArray
        val stateField = state[0] as JsonObject
        val record = ((stateField["type"] as JsonObject)["fields"] as JsonArray)[0] as JsonObject
        assertNull(record["scalar"], "the flat `scalar` key must be gone")
        assertEquals("scalar", ((record["type"] as JsonObject)["kind"] as JsonPrimitive).content)
        val rows = (stateField["sample"] as JsonObject)["rows"] as JsonArray
        val cell = (rows[0] as JsonObject)["label"] as JsonObject
        assertEquals("scalar", (cell["kind"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive("Ada"), cell["value"])
    }

    @Test
    fun `M3to4 stamps a stateless document without altering it`() {
        val v3 = JsonObject(mapOf("schemaVersion" to JsonPrimitive(3), "id" to JsonPrimitive("x")))
        val v4 = viewforge.project.migrations.M3to4.migrate(v3)
        assertEquals(4, SchemaMigrations.readVersion(v4))
        assertEquals(JsonPrimitive("x"), v4["id"])
    }

    @Test
    fun `M4to5 stamps the version and leaves the rest of the document untouched`() {
        // Component-local state (ADR-034 Amendment) is purely additive — a v4 document has no component
        // `state`, so the 4->5 step only stamps the version, exactly like M1to2/M2to3.
        val v4 = JsonObject(mapOf("schemaVersion" to JsonPrimitive(4), "id" to JsonPrimitive("x")))
        val v5 = viewforge.project.migrations.M4to5.migrate(v4)
        assertEquals(5, SchemaMigrations.readVersion(v5))
        assertEquals(JsonPrimitive("x"), v5["id"])
    }

    @Test
    fun `M5to6 stamps the version and leaves the rest of the document untouched`() {
        // Interactive state & events (ADR-035) is purely additive — a v5 document has no node `handlers`,
        // so the 5->6 step only stamps the version, exactly like M1to2/M2to3/M4to5.
        val v5 = JsonObject(mapOf("schemaVersion" to JsonPrimitive(5), "id" to JsonPrimitive("x")))
        val v6 = viewforge.project.migrations.M5to6.migrate(v5)
        assertEquals(6, SchemaMigrations.readVersion(v6))
        assertEquals(JsonPrimitive("x"), v6["id"])
    }

    @Test
    fun `a schema-5 document with no handlers migrates through the store and loads at the current version`() {
        // A v5 file carries no node handlers, so the 5->6 step is a pure version stamp (M5to6): the document
        // is already a valid v6 and its node must load unchanged, reporting its on-disk version for backup.
        val tmp = Files.createTempDirectory("vforge-5to6").resolve("legacy.vforge")
        Files.writeString(
            tmp,
            """
            {
              "schemaVersion": 5,
              "id": "01LEGACYINT",
              "name": "Legacy",
              "framework": { "packageId": "compose-multiplatform", "packageVersion": "1.0.0" },
              "screens": [
                { "id": "s", "name": "S", "root": { "id": "n_1", "type": "compose.foundation.layout.Box" } }
              ]
            }
            """.trimIndent(),
        )
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(SchemaMigrations.CURRENT, result.project.schemaVersion)
        assertEquals(5, result.migratedFromVersion)
        // The node loads with defaulted empty handlers (interactivity is opt-in).
        assertTrue(result.project.screens.single().root.handlers.isEmpty())
    }

    @Test
    fun `a schema-4 document with a component migrates through the store and loads at the current version`() {
        // A v4 file carries no component state, so the 4->5 step is a pure version stamp (M4to5): the
        // document is already a valid v5 and its component must load unchanged, reporting its on-disk version.
        val tmp = Files.createTempDirectory("vforge-4to5").resolve("legacy.vforge")
        Files.writeString(
            tmp,
            """
            {
              "schemaVersion": 4,
              "id": "01LEGACYCMP",
              "name": "Legacy",
              "framework": { "packageId": "compose-multiplatform", "packageVersion": "1.0.0" },
              "components": [
                { "id": "cmp_1", "name": "Bare", "root": { "id": "n_1", "type": "compose.foundation.layout.Box" } }
              ]
            }
            """.trimIndent(),
        )
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(SchemaMigrations.CURRENT, result.project.schemaVersion)
        assertEquals(4, result.migratedFromVersion)
        // The component loads with a defaulted empty state (component-local state is opt-in).
        assertTrue(result.project.components.single().state.isEmpty())
    }

    @Test
    fun `the frozen schema-3 Dashboard fixture migrates through the store and equals the in-code v4 model`() {
        // Dashboard-v3.vforge is the pre-#256 committed serialization (flat record fields, bare cells). Loading
        // it must run M3to4 and yield exactly the recursive in-code fixture, proving the migration is correct.
        val v3 = javaClass.getResource("/migrations/Dashboard-v3.vforge")!!.readText()
        val tmp = Files.createTempDirectory("vforge-3to4").resolve("Dashboard.vforge")
        Files.writeString(tmp, v3)
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(Fixtures.stateProject(), result.project)
        assertEquals(3, result.migratedFromVersion)
    }

    @Test
    fun `a schema-2 document with no state migrates through the store and loads at the current version`() {
        // A v2 file carries no screen state, so the 2->3 step is a pure version stamp (M2to3): the
        // document is already a valid v3 and must load as-is, reporting its on-disk version for backup.
        val tmp = Files.createTempDirectory("vforge-2to3").resolve("legacy.vforge")
        Files.writeString(
            tmp,
            """
            {
              "schemaVersion": 2,
              "id": "01LEGACY",
              "name": "Legacy",
              "framework": { "packageId": "compose-multiplatform", "packageVersion": "1.0.0" }
            }
            """.trimIndent(),
        )
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(SchemaMigrations.CURRENT, result.project.schemaVersion)
        assertEquals(2, result.migratedFromVersion)
    }

    @Test
    fun `the committed schema-1 Demo_vforge fixture migrates and loads at the current version`() {
        // Demo.vforge is intentionally pinned at schema 1 (samples/README) so the 1->2 chain is
        // exercised end to end against a real committed file (DATA_MODEL rule 3).
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")
        val result = ProjectStore.load(Paths.get(samplesDir, "Demo.vforge"))
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(SchemaMigrations.CURRENT, result.project.schemaVersion)
    }

    @Test
    fun `a migrated older-schema load reports its on-disk version and a current file reports null`() {
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")

        // Demo.vforge is pinned at schema 1 → migrated on load, so the pre-migration version is reported.
        val older = ProjectStore.load(Paths.get(samplesDir, "Demo.vforge"))
        assertTrue(older is LoadResult.Success, "expected Success but got $older")
        assertEquals(1, older.migratedFromVersion)

        // Gallery.vforge is already at the current schema → nothing migrated, so no backup is needed.
        val current = ProjectStore.load(Paths.get(samplesDir, "Gallery.vforge"))
        assertTrue(current is LoadResult.Success, "expected Success but got $current")
        assertNull(current.migratedFromVersion)
    }

    @Test
    fun `saving a migrated older-schema file backs up the untouched original first (D9)`() {
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")
        val dir = Files.createTempDirectory("vforge-migrate-backup")
        val target = dir.resolve("Demo.vforge")
        Files.copy(Paths.get(samplesDir, "Demo.vforge"), target)
        val originalBytes = Files.readAllBytes(target)

        val loaded = ProjectStore.load(target)
        assertTrue(loaded is LoadResult.Success && loaded.migratedFromVersion == 1, "got $loaded")

        // Mirror the shell's save path: request a backup because the load migrated (DocumentController).
        ProjectStore.save(loaded.project, target, backup = loaded.migratedFromVersion != null)

        val backup = dir.resolve("Demo.vforge.bak")
        assertTrue(Files.exists(backup), "a .bak of the pre-migration original must be written (D9)")
        assertContentEquals(
            originalBytes,
            Files.readAllBytes(backup),
            "the backup must be the original file, byte-for-byte",
        )

        // The saved file is now current-schema, so a subsequent load migrates nothing.
        val reloaded = ProjectStore.load(target)
        assertTrue(reloaded is LoadResult.Success && reloaded.migratedFromVersion == null, "got $reloaded")
    }

    @Test
    fun `loading a document without a schemaVersion is rejected`() {
        val tmp = Files.createTempDirectory("vforge-nover").resolve("bad.vforge")
        Files.writeString(tmp, """{"id": "x", "name": "x"}""")
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Failure && result.kind == LoadFailure.MISSING_VERSION, "got $result")
    }
}

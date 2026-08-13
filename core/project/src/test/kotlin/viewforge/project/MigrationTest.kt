package viewforge.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
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
    fun `loading a document without a schemaVersion is rejected`() {
        val tmp = Files.createTempDirectory("vforge-nover").resolve("bad.vforge")
        Files.writeString(tmp, """{"id": "x", "name": "x"}""")
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Failure && result.kind == LoadFailure.MISSING_VERSION, "got $result")
    }
}

package viewforge.model

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test: proves the JDK 21 toolchain, kotlin-test wiring, and `allTests` aggregation work
 * end-to-end from M0. Real IR/round-trip tests arrive at M1.
 */
class SchemaVersionTest {
    @Test
    fun `schema version is positive`() {
        assertTrue(SCHEMA_VERSION >= 1)
    }
}

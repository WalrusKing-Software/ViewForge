package viewforge.editor.panels

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.RecordField
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure logic behind the screen-state editor (ADR-034): scalar parsing/defaults, retyping, row
 * reconciliation, and name validation. Tested without a composition, so the composable stays thin.
 */
class ScreenStateEditingTest {
    @Test
    fun `parseScalar accepts valid literals and withholds invalid numbers and booleans`() {
        assertEquals(JsonPrimitive("hi"), parseScalar("hi", ScalarType.STRING))
        assertEquals(JsonPrimitive(42), parseScalar(" 42 ", ScalarType.INT))
        assertNull(parseScalar("x", ScalarType.INT))
        assertEquals(JsonPrimitive(1.5), parseScalar("1.5", ScalarType.FLOAT))
        assertEquals(JsonPrimitive(true), parseScalar("true", ScalarType.BOOL))
        assertNull(parseScalar("yes", ScalarType.BOOL))
    }

    @Test
    fun `scalarDefault gives the zero value per type`() {
        assertEquals(JsonPrimitive(""), scalarDefault(ScalarType.STRING))
        assertEquals(JsonPrimitive(0), scalarDefault(ScalarType.INT))
        assertEquals(JsonPrimitive(false), scalarDefault(ScalarType.BOOL))
    }

    @Test
    fun `retypeScalar switches type and resets the sample to that type's default`() {
        val field = StateField("n", StateType.Scalar(ScalarType.STRING), SampleValue.Scalar(JsonPrimitive("hi")))
        val out = retypeScalar(field, ScalarType.INT)
        assertEquals(StateType.Scalar(ScalarType.INT), out.type)
        assertEquals(SampleValue.Scalar(JsonPrimitive(0)), out.sample)
    }

    @Test
    fun `reconcileRows drops unknown keys and seeds missing cells with defaults`() {
        val fields = listOf(RecordField("name", ScalarType.STRING), RecordField("age", ScalarType.INT))
        val rows = listOf(mapOf("name" to JsonPrimitive("Ada"), "gone" to JsonPrimitive("x")))
        assertEquals(
            listOf(mapOf("name" to JsonPrimitive("Ada"), "age" to JsonPrimitive(0))),
            reconcileRows(rows, fields),
        )
    }

    @Test
    fun `emptyRow has a default cell per field`() {
        val fields = listOf(RecordField("name", ScalarType.STRING), RecordField("on", ScalarType.BOOL))
        assertEquals(mapOf("name" to JsonPrimitive(""), "on" to JsonPrimitive(false)), emptyRow(fields))
    }

    @Test
    fun `isValidStateName rejects bad identifiers and duplicates but allows a field's own name`() {
        val fields = listOf(
            StateField("users", StateType.Scalar(ScalarType.STRING), SampleValue.Scalar(JsonPrimitive(""))),
            StateField("count", StateType.Scalar(ScalarType.INT), SampleValue.Scalar(JsonPrimitive(0))),
        )
        assertTrue(isValidStateName("total", 1, fields)) // fresh, valid
        assertTrue(isValidStateName("count", 1, fields)) // its own name is fine
        assertTrue(!isValidStateName("users", 1, fields)) // taken by field 0
        assertTrue(!isValidStateName("2bad", 1, fields)) // leading digit
        assertTrue(!isValidStateName("has space", 1, fields))
    }
}

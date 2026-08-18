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
    fun `reconcileSampleRows drops unknown keys and seeds missing cells with defaults`() {
        val fields = listOf(RecordField("name", ScalarType.STRING), RecordField("age", ScalarType.INT))
        val rows = listOf(
            mapOf<String, SampleValue>(
                "name" to SampleValue.Scalar(JsonPrimitive("Ada")),
                "gone" to SampleValue.Scalar(JsonPrimitive("x")),
            ),
        )
        assertEquals(
            listOf(
                mapOf<String, SampleValue>(
                    "name" to SampleValue.Scalar(JsonPrimitive("Ada")),
                    "age" to SampleValue.Scalar(JsonPrimitive(0)),
                ),
            ),
            reconcileSampleRows(rows, fields),
        )
    }

    @Test
    fun `emptySampleRow has a default cell per field`() {
        val fields = listOf(RecordField("name", ScalarType.STRING), RecordField("on", ScalarType.BOOL))
        assertEquals(
            mapOf<String, SampleValue>(
                "name" to SampleValue.Scalar(JsonPrimitive("")),
                "on" to SampleValue.Scalar(JsonPrimitive(false)),
            ),
            emptySampleRow(fields),
        )
    }

    @Test
    fun `reconcileSampleRows reconciles a nested list cell to its sub-shape (#255)`() {
        val teams = RecordField("teams", StateType.ListOfRecord(listOf(RecordField("label", ScalarType.STRING))))
        val fields = listOf(RecordField("name", ScalarType.STRING), teams)
        val rows = listOf(
            mapOf<String, SampleValue>(
                "name" to SampleValue.Scalar(JsonPrimitive("Eng")),
                "teams" to SampleValue.Rows(
                    listOf(
                        mapOf(
                            "label" to SampleValue.Scalar(JsonPrimitive("Core")),
                            "gone" to SampleValue.Scalar(JsonPrimitive("x")), // unknown sub-key → dropped
                        ),
                    ),
                ),
            ),
            mapOf<String, SampleValue>("name" to SampleValue.Scalar(JsonPrimitive("Design"))), // no teams cell
        )
        val out = reconcileSampleRows(rows, fields)
        assertEquals(
            SampleValue.Rows(listOf(mapOf("label" to SampleValue.Scalar(JsonPrimitive("Core"))))),
            out[0]["teams"],
        )
        assertEquals(SampleValue.Rows(emptyList()), out[1]["teams"]) // missing nested cell → empty sub-rows
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

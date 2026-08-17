package viewforge.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The PropType <-> parameter type-name mapping (parameters slice 4a, ADR-028). */
class ParameterTypeTest {
    @Test
    fun `maps each value-like PropType to a parameter type name`() {
        assertEquals("String", ParameterType.nameFor(PropType.String))
        assertEquals("Int", ParameterType.nameFor(PropType.Int))
        assertEquals("Float", ParameterType.nameFor(PropType.Float))
        assertEquals("Boolean", ParameterType.nameFor(PropType.Bool))
        assertEquals("Dp", ParameterType.nameFor(PropType.Dp))
        assertEquals("Color", ParameterType.nameFor(PropType.Color))
    }

    @Test
    fun `non-value types are not representable as parameters`() {
        listOf(PropType.Enum, PropType.Typography, PropType.Shape, PropType.Resource).forEach {
            assertNull(ParameterType.nameFor(it), "$it should not be a parameter type")
            assertFalse(ParameterType.isPromotable(it))
        }
        assertNull(ParameterType.propTypeFor("Bitmap"))
    }

    @Test
    fun `every promotable type round-trips PropType to name to PropType`() {
        listOf(PropType.String, PropType.Int, PropType.Float, PropType.Bool, PropType.Dp, PropType.Color).forEach {
            assertTrue(ParameterType.isPromotable(it))
            assertEquals(it, ParameterType.propTypeFor(ParameterType.nameFor(it)!!))
        }
    }

    @Test
    fun `propDefinition synthesizes an inspector definition for a supported parameter`() {
        val def = ParameterType.propDefinition(Parameter("label", "String", PropValue.Literal(JsonPrimitive("Hi"))))
        assertEquals("label", def?.name)
        assertEquals(PropType.String, def?.type)
        assertEquals(PropValue.Literal(JsonPrimitive("Hi")), def?.default)
        assertFalse(def!!.themeable)
    }

    @Test
    fun `propDefinition makes a Color parameter themeable and is null for an unrepresentable type`() {
        assertTrue(ParameterType.propDefinition(Parameter("bg", "Color"))!!.themeable)
        assertNull(ParameterType.propDefinition(Parameter("x", "Bitmap")))
    }
}

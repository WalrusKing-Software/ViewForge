package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.PropValue
import viewforge.model.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Parameters slice 2 (ADR-028): the parameter type map and ParamRef value emission. */
class ParameterTypesTest {
    private fun literal(v: Any): PropValue.Literal = PropValue.Literal(
        when (v) {
            is Boolean -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        },
    )

    @Test
    fun `signatureType maps the supported types`() {
        assertEquals(STRING, ParameterTypes.signatureType("String"))
        assertEquals(INT, ParameterTypes.signatureType("Int"))
        assertEquals(BOOLEAN, ParameterTypes.signatureType("Boolean"))
        assertEquals(ComposeNames.Dp, ParameterTypes.signatureType("Dp"))
        assertEquals(ComposeNames.Color, ParameterTypes.signatureType("Color"))
    }

    @Test
    fun `an unsupported parameter type fails loudly`() {
        assertFailsWith<CodegenException> { ParameterTypes.signatureType("Bitmap") }
        assertFailsWith<CodegenException> { ParameterTypes.argValue("Bitmap", literal("x"), Theme()) }
    }

    @Test
    fun `argValue emits a literal per its declared type`() {
        assertEquals("\"Hi\"", ParameterTypes.argValue("String", literal("Hi"), Theme()).toString())
        assertEquals("5", ParameterTypes.argValue("Int", literal(5), Theme()).toString())
        assertEquals("false", ParameterTypes.argValue("Boolean", literal(false), Theme()).toString())
    }

    @Test
    fun `a ParamRef emits the bare parameter identifier`() {
        assertEquals("label", CodegenValues.text(PropValue.ParamRef("label")).toString())
        assertEquals("enabled", CodegenValues.bool(PropValue.ParamRef("enabled")).toString())
        assertEquals("count", CodegenValues.int(PropValue.ParamRef("count")).toString())
    }
}

package viewforge.packages.compose.codegen

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure corners of responsive-override codegen (ADR-037, #222) that the `Responsive` golden + compile
 * gate don't isolate: the `BoxWithConstraints` hoist happens only when a value actually differs across
 * breakpoints, an unknown breakpoint id is inert, and the two fail-loud boundaries (a base-absent override,
 * and responsive on an emitter that doesn't support it yet) throw rather than emit broken code.
 */
class ResponsiveCodegenTest {
    private fun lit(v: Any): PropValue.Literal = PropValue.Literal(
        when (v) {
            is Int -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            else -> error("unsupported")
        },
    )

    private fun screenOf(node: Node): String = ComposeCodeGenerator().generateScreen(
        Screen(
            id = "s",
            name = "S",
            root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(node)),
        ),
        Theme(),
        sourceName = "P",
        schemaVersion = 7,
    )

    @Test
    fun `an overridden prop hoists into a BoxWithConstraints val selected largest-first by maxWidth`() {
        val text = Node(
            NodeId("t"),
            "compose.material3.Text",
            props = mapOf("text" to lit("Hi"), "fontSize" to lit(14)),
            responsive = mapOf(
                "medium" to mapOf("fontSize" to lit(16)),
                "expanded" to mapOf("fontSize" to lit(20)),
            ),
        )
        val out = screenOf(text)
        assertTrue("BoxWithConstraints {" in out, out)
        assertTrue("val fontSize = if (maxWidth >= 840.dp) {" in out, out)
        assertTrue("} else if (maxWidth >= 600.dp) {" in out, out)
        // The call references the hoisted local, not an inline literal.
        assertTrue("fontSize = fontSize," in out, out)
        // Largest-first: 20.sp (expanded) precedes 16.sp (medium) precedes the 14.sp base.
        assertTrue(out.indexOf("20.sp") < out.indexOf("16.sp"), out)
        assertTrue(out.indexOf("16.sp") < out.indexOf("14.sp"), out)
    }

    @Test
    fun `an override equal to the base emits no wrapper, since nothing differs to branch on`() {
        val text = Node(
            NodeId("t"),
            "compose.material3.Text",
            props = mapOf("text" to lit("Hi"), "fontSize" to lit(14)),
            responsive = mapOf("medium" to mapOf("fontSize" to lit(14))),
        )
        assertFalse("BoxWithConstraints" in screenOf(text), "an override matching the base must not wrap")
    }

    @Test
    fun `an override under an unknown breakpoint id is inert (no target threshold to branch on)`() {
        val text = Node(
            NodeId("t"),
            "compose.material3.Text",
            props = mapOf("text" to lit("Hi"), "fontSize" to lit(14)),
            responsive = mapOf("gigantic" to mapOf("fontSize" to lit(40))),
        )
        assertFalse("BoxWithConstraints" in screenOf(text), "an unknown breakpoint id must not wrap")
    }

    @Test
    fun `an override that introduces a prop absent at the base breakpoint fails loud`() {
        val text = Node(
            NodeId("t"),
            "compose.material3.Text",
            props = mapOf("text" to lit("Hi")), // no base fontSize
            responsive = mapOf("expanded" to mapOf("fontSize" to lit(20))),
        )
        val ex = assertFailsWith<CodegenException> { screenOf(text) }
        assertTrue("not set at the base" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `responsive on an emitter that does not support it yet fails loud`() {
        val scaffold = Node(
            NodeId("sc"),
            "compose.material3.Scaffold",
            responsive = mapOf("expanded" to mapOf("x" to lit(1))),
        )
        assertFailsWith<CodegenException> { screenOf(scaffold) }
    }
}

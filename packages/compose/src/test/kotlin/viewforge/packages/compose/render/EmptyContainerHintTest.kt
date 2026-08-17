package viewforge.packages.compose.render

import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate for the editor-only empty-container drop affordance (#191): it must fire for a container that
 * accepts default children and has none, and for nothing else — so codegen, export, and fidelity (which
 * never set `editorAffordances`) are untouched and non-containers never get a min size. The visual min-size
 * itself is a render-layer effect verified by running the app; this pins the decision.
 */
class EmptyContainerHintTest {
    private fun node(type: String, children: List<Node> = emptyList()) =
        Node(id = NodeId.random(), type = type, children = children)

    @Test
    fun `an empty container that accepts children qualifies`() {
        assertTrue(isEmptyDefaultChildContainer(node("compose.foundation.layout.Column")))
        assertTrue(isEmptyDefaultChildContainer(node("compose.foundation.layout.Row")))
        assertTrue(isEmptyDefaultChildContainer(node("compose.foundation.layout.Box")))
        assertTrue(isEmptyDefaultChildContainer(node("compose.foundation.lazy.LazyColumn")))
    }

    @Test
    fun `a container that already has children does not qualify`() {
        val filled = node(
            "compose.foundation.layout.Column",
            children = listOf(node("compose.material3.Text")),
        )
        assertFalse(isEmptyDefaultChildContainer(filled))
    }

    @Test
    fun `a leaf component never qualifies, even when empty`() {
        assertFalse(isEmptyDefaultChildContainer(node("compose.material3.Text")))
        assertFalse(isEmptyDefaultChildContainer(node("compose.material3.Button")))
        assertFalse(isEmptyDefaultChildContainer(node("compose.foundation.layout.Spacer")))
    }

    @Test
    fun `an unknown type does not qualify`() {
        assertFalse(isEmptyDefaultChildContainer(node("compose.unknown.Widget")))
    }
}

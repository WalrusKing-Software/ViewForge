package viewforge.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Detecting an interactive document (ADR-035, #277): any node with a handler, anywhere in any screen or component. */
class InteractivityTest {
    private val handler = mapOf("onClick" to listOf(Action.Toggle("open")))

    private fun project(root: Node, componentRoot: Node? = null) = Project(
        id = "p",
        name = "P",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = listOf(Screen("s", "S", root = root)),
        components = componentRoot?.let { listOf(ComponentDef(id = "c", name = "C", root = it)) }.orEmpty(),
    )

    @Test
    fun `a handler-free document is not interactive`() {
        val root = Node(NodeId("col"), "Column", children = listOf(Node(NodeId("t"), "Text")))
        assertFalse(project(root).hasInteractiveNodes())
    }

    @Test
    fun `a handler on a nested slot child makes the document interactive`() {
        val button = Node(NodeId("b"), "Button", handlers = handler)
        val root = Node(NodeId("col"), "Column", slots = mapOf("content" to listOf(button)))
        assertTrue(project(root).hasInteractiveNodes())
    }

    @Test
    fun `a handler only in a component root still counts`() {
        val plainScreen = Node(NodeId("col"), "Column")
        val comp = Node(NodeId("cb"), "Button", handlers = handler)
        assertTrue(project(plainScreen, componentRoot = comp).hasInteractiveNodes())
    }

    @Test
    fun `anyInTree finds a match in itself, children, and slots`() {
        val leaf = Node(NodeId("leaf"), "Text", props = mapOf("text" to PropValue.Literal(JsonPrimitive("hi"))))
        val tree = Node(NodeId("row"), "Row", children = listOf(leaf))
        assertTrue(tree.anyInTree { it.type == "Text" })
        assertFalse(tree.anyInTree { it.type == "Button" })
    }
}

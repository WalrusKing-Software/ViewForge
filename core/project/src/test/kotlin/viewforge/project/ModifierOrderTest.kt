package viewforge.project

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Modifier order is semantic (ADR-005, TECHNICAL_NOTES §1). A round-trip must preserve it exactly,
 * and two different orders must remain distinguishable — a suite that only checked *which*
 * modifiers are present would pass while emitting wrong output.
 */
class ModifierOrderTest {
    private fun nodeWith(vararg modifiers: ModifierEntry): Node =
        Node(id = NodeId("n"), type = "compose.foundation.layout.Box", modifiers = modifiers.toList())

    private val padding =
        ModifierEntry("m_pad", "compose.padding", mapOf("all" to PropValue.Literal(JsonPrimitive(8))))
    private val background =
        ModifierEntry("m_bg", "compose.background", mapOf("color" to PropValue.ThemeRef("colors.primary")))
    private val size =
        ModifierEntry("m_size", "compose.size", mapOf("value" to PropValue.Literal(JsonPrimitive(48))))

    @Test
    fun `modifier order is preserved exactly across a round-trip`() {
        val node = nodeWith(padding, background, size)
        val decoded = ProjectCodec.decode(ProjectCodec.encode(node.wrap())).firstNode()
        assertEquals(listOf("m_pad", "m_bg", "m_size"), decoded.modifiers.map { it.id })
        assertEquals(node.modifiers, decoded.modifiers)
    }

    @Test
    fun `reordering modifiers produces a different, non-equal document`() {
        val a = nodeWith(padding, background)
        val b = nodeWith(background, padding)
        assertNotEquals(a, b)
        assertNotEquals(ProjectCodec.encode(a.wrap()), ProjectCodec.encode(b.wrap()))
    }
}

// Small helpers to serialize a bare Node by wrapping it in a minimal project.
private fun Node.wrap() = Fixtures.minimalProject().copy(screens = listOf(screenOf(this)))

private fun screenOf(root: Node) = viewforge.model.Screen(id = "s", name = "S", root = root)

private fun viewforge.model.Project.firstNode(): Node = screens.first().root

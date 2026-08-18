package viewforge.packages.compose.codegen

import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Repeater
import viewforge.model.Screen
import viewforge.model.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The pure, non-golden corners of state codegen (ADR-034, #21): the generated element-type name, the
 * member-access binding path, and the loud failure a malformed repeat produces. The end-to-end shape is
 * pinned by the `StateBinding` golden + the compile gate; these lock the units it doesn't isolate.
 */
class StateEmitterTest {
    @Test
    fun `a list field's element type name is singularised and capitalised`() {
        assertEquals("Member", StateEmitter.recordTypeName("members"))
        assertEquals("Person", StateEmitter.recordTypeName("person")) // no trailing s → just capitalised
        assertEquals("TeamMember", StateEmitter.recordTypeName("teamMembers")) // camelCase preserved
    }

    @Test
    fun `a binding path emits as dotted member access, never a string literal`() {
        assertEquals("title", CodegenValues.bindingPath("title").toString())
        assertEquals("item.name", CodegenValues.bindingPath("item.name").toString())
    }

    @Test
    fun `a repeat node with no source binding fails loudly rather than emitting broken code`() {
        val repeat = Node(NodeId("rep"), Repeater.TYPE) // no `source` prop
        val screen =
            Screen(
                id = "s",
                name = "Broken",
                root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat)),
            )
        assertFailsWith<CodegenException> {
            ComposeCodeGenerator().generateScreen(screen, Theme(), sourceName = "P", schemaVersion = 3)
        }
    }
}

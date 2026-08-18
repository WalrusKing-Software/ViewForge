package viewforge.packages.compose.codegen

import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.ScalarType
import viewforge.model.Screen
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.Theme
import viewforge.model.scalarRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `recordTypes emits a data class for a nested list field, typed List of its element (#255)`() {
        val state = listOf(
            StateField(
                "departments",
                StateType.ListOfRecord(
                    listOf(
                        RecordField("name", ScalarType.STRING),
                        RecordField(
                            "teams",
                            StateType.ListOfRecord(listOf(RecordField("label", ScalarType.STRING))),
                        ),
                    ),
                ),
                scalarRows(emptyList()),
            ),
        )
        val types = StateEmitter.recordTypes(state)
        // Both the outer and the nested element type are emitted, outer first (depth-first collection).
        assertEquals(listOf("Department", "Team"), types.map { it.name })
        // A bare TypeSpec prints fully-qualified names; the FileSpec resolves this to `List<Team>` on emit.
        val department = types.first { it.name == "Department" }.toString()
        assertTrue(
            "teams: kotlin.collections.List<Team>" in department,
            "nested field must be typed as a List of the element type, got:\n$department",
        )
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

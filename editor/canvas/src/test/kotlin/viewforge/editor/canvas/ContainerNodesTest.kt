package viewforge.editor.canvas

import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure collection of the layout containers the debug "show borders" overlay outlines (#117). No Compose
 * UI harness — the container predicate is supplied directly, mirroring what the catalog reports.
 */
class ContainerNodesTest {
    // A container is any type prefixed "layout." here (Box/Row/Column stand-ins); Text is a leaf.
    private val isContainer: (String) -> Boolean = { it.startsWith("layout.") }

    private fun node(id: String, type: String, children: List<Node> = emptyList(), hidden: Boolean = false) =
        Node(id = NodeId(id), type = type, children = children, hidden = hidden)

    // root(Box) > [ column(Column) > [ text, row(Row) > [ text ] ], text ]
    private fun tree(columnHidden: Boolean = false) = node(
        "root",
        "layout.Box",
        children =
        listOf(
            node(
                "column",
                "layout.Column",
                hidden = columnHidden,
                children =
                listOf(
                    node("t1", "text.Text"),
                    node("row", "layout.Row", children = listOf(node("t2", "text.Text"))),
                ),
            ),
            node("t3", "text.Text"),
        ),
    )

    @Test
    fun `collects every container including the root, skipping leaves`() {
        val ids = containerNodes(tree(), isContainer).map { it.id.value }
        assertEquals(listOf("root", "column", "row"), ids)
    }

    @Test
    fun `a hidden container and its descendants are skipped entirely`() {
        // Hiding the Column drops it and its nested Row; only the root container remains.
        val ids = containerNodes(tree(columnHidden = true), isContainer).map { it.id.value }
        assertEquals(listOf("root"), ids)
    }

    @Test
    fun `a non-container root yields no entries when it has no container descendants`() {
        val leafOnly = node("root", "text.Text", children = listOf(node("t", "text.Text")))
        assertEquals(emptyList(), containerNodes(leafOnly, isContainer).map { it.id.value })
    }
}

package viewforge.editor.panels

import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tree-search logic (T6, #122) — no Compose UI harness. The tree mixes named and unnamed nodes so
 * both match paths (name and short type) are exercised.
 */
class TreeSearchTest {
    // root(Column) > [ header(Text "Title"), row(Row) > [ btn(Button), lbl(Text "Label") ] ]
    private val header = Node(id = NodeId("header"), type = "compose.material3.Text", name = "Title")
    private val btn = Node(id = NodeId("btn"), type = "compose.material3.Button")
    private val lbl = Node(id = NodeId("lbl"), type = "compose.material3.Text", name = "Label")
    private val row = Node(id = NodeId("row"), type = "compose.foundation.layout.Row", children = listOf(btn, lbl))
    private val root =
        Node(id = NodeId("root"), type = "compose.foundation.layout.Column", children = listOf(header, row))

    @Test
    fun `matches by user name, case-insensitively`() {
        assertTrue(nodeMatchesQuery(header, "titl"))
        assertTrue(nodeMatchesQuery(header, "TITLE"))
        assertFalse(nodeMatchesQuery(btn, "title"))
    }

    @Test
    fun `matches by short type name when unnamed`() {
        assertTrue(nodeMatchesQuery(btn, "button")) // btn has no name; matches its type
        assertTrue(nodeMatchesQuery(header, "text")) // a named node still matches its type
    }

    @Test
    fun `a blank query matches nothing and applies no filter`() {
        assertFalse(nodeMatchesQuery(header, "   "))
        assertNull(searchKeepSet(root, ""))
        assertNull(searchKeepSet(root, "  "))
    }

    @Test
    fun `keep set is the match plus all its ancestors`() {
        // "button" matches btn only; its path is btn -> row -> root.
        assertEquals(setOf("btn", "row", "root"), searchKeepSet(root, "button"))
    }

    @Test
    fun `a type query keeps every match and its path`() {
        // "text" matches header and lbl; kept = both + their ancestors (row, root).
        assertEquals(setOf("header", "lbl", "row", "root"), searchKeepSet(root, "text"))
    }

    @Test
    fun `no match yields an empty keep set (not null)`() {
        assertEquals(emptySet(), searchKeepSet(root, "zzz"))
    }
}

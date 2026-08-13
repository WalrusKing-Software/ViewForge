package viewforge.editor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure click-code→select lookup [nodeAt] (#103, reverse of #51): given the node→source-range map the
 * seam already produces (#51), map a caret offset back to the innermost enclosing node. Tested without a
 * composition, mirroring the ranges the instrumented emitter builds (half-open, via `until`).
 */
class CodeSpanLookupTest {
    // root [0,100) contains child [20,60), which contains grandchild [30,45).
    private val spans = mapOf(
        "root" to (0 until 100),
        "child" to (20 until 60),
        "grandchild" to (30 until 45),
    )

    @Test
    fun `an offset inside only the root resolves to the root`() {
        assertEquals("root", spans.nodeAt(5))
    }

    @Test
    fun `an offset inside a child resolves to the child, not its parent`() {
        assertEquals("child", spans.nodeAt(25))
    }

    @Test
    fun `an offset inside the deepest span resolves to the innermost node`() {
        assertEquals("grandchild", spans.nodeAt(40))
    }

    @Test
    fun `an offset outside every span resolves to null`() {
        assertNull(spans.nodeAt(100))
        assertNull(emptyMap<String, IntRange>().nodeAt(0))
    }

    @Test
    fun `the span start is inclusive and its exclusive end is not covered`() {
        assertEquals("child", spans.nodeAt(20)) // first char of child
        assertEquals("root", spans.nodeAt(60)) // one past child's last char falls back to root
    }
}

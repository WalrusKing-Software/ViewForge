package viewforge.packages.compose.targets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The width → responsive-breakpoint mapping (#314, ADR-037): the render-time twin of codegen's
 * `BoxWithConstraints` branching. It must pick the largest Android threshold the width meets — `compact`
 * (null) below 600dp, `medium` at 600–839, `expanded` at 840+ — so the canvas previews the same breakpoint
 * codegen emits at that width. Boundaries are inclusive at the threshold, matching `maxWidth >= N.dp`.
 */
class BreakpointsTest {
    @Test
    fun `a width below the smallest threshold is the base (compact), reported as null`() {
        assertNull(breakpointForWidth(0f))
        assertNull(breakpointForWidth(359f))
        assertNull(breakpointForWidth(599f)) // just under medium
    }

    @Test
    fun `the medium threshold is inclusive and holds up to expanded`() {
        assertEquals("medium", breakpointForWidth(600f))
        assertEquals("medium", breakpointForWidth(700f))
        assertEquals("medium", breakpointForWidth(839f)) // just under expanded
    }

    @Test
    fun `the expanded threshold is inclusive and the widest wins`() {
        assertEquals("expanded", breakpointForWidth(840f))
        assertEquals("expanded", breakpointForWidth(1280f)) // a tablet / wide desktop frame
    }

    @Test
    fun `the Android target owns the thresholds it maps against`() {
        assertEquals(listOf("medium", "expanded"), AndroidTarget.breakpoints.map { it.id })
        assertEquals(listOf(600, 840), AndroidTarget.breakpoints.map { it.minWidthDp })
    }

    @Test
    fun `an empty breakpoint set always resolves to the base`() {
        assertNull(breakpointForWidth(4000f, breakpoints = emptyList()))
    }
}

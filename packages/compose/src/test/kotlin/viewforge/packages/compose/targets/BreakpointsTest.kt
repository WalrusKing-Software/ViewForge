package viewforge.packages.compose.targets

import viewforge.spi.breakpointForWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Android target's breakpoint thresholds (#314, ADR-037), mapped by the framework-neutral
 * `breakpointForWidth` (core/spi). It must pick the largest threshold the width meets — `compact` (null)
 * below 600dp, `medium` at 600–839, `expanded` at 840+ — so the canvas previews the same breakpoint codegen
 * emits at that width. Boundaries are inclusive at the threshold, matching `maxWidth >= N.dp`.
 */
class BreakpointsTest {
    private val bps = AndroidTarget.breakpoints

    @Test
    fun `a width below the smallest threshold is the base (compact), reported as null`() {
        assertNull(breakpointForWidth(0f, bps))
        assertNull(breakpointForWidth(359f, bps))
        assertNull(breakpointForWidth(599f, bps)) // just under medium
    }

    @Test
    fun `the medium threshold is inclusive and holds up to expanded`() {
        assertEquals("medium", breakpointForWidth(600f, bps))
        assertEquals("medium", breakpointForWidth(700f, bps))
        assertEquals("medium", breakpointForWidth(839f, bps)) // just under expanded
    }

    @Test
    fun `the expanded threshold is inclusive and the widest wins`() {
        assertEquals("expanded", breakpointForWidth(840f, bps))
        assertEquals("expanded", breakpointForWidth(1280f, bps)) // a tablet / wide desktop frame
    }

    @Test
    fun `the Android target owns the thresholds it maps against`() {
        assertEquals(listOf("medium", "expanded"), bps.map { it.id })
        assertEquals(listOf(600, 840), bps.map { it.minWidthDp })
    }

    @Test
    fun `an empty breakpoint set always resolves to the base`() {
        assertNull(breakpointForWidth(4000f, emptyList()))
    }
}

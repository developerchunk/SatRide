package com.developerstring.satride.feature.arsky.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Label placement shipped wrong once because it lived inside the draw lambda, where no test could
 * see it: names in the right-hand margin were simply cut off by the edge of the screen. These
 * cases are the reason it is a function now.
 */
class LabelPlacementTest {

    private val viewport = 1080f
    private val offset = 30f
    private val width = 200f

    @Test
    fun `a label sits to the right of its glyph when there is room`() {
        assertEquals(530f, labelLeft(glyphX = 500f, offset = offset, labelWidth = width, viewportWidth = viewport))
    }

    @Test
    fun `a label flips to the left rather than running off the right edge`() {
        // Glyph at 1000: to the right would end at 1230, well past the 1080 edge.
        val left = labelLeft(glyphX = 1000f, offset = offset, labelWidth = width, viewportWidth = viewport)

        assertEquals(770f, left)
        assertTrue(left + width <= viewport)
    }

    @Test
    fun `a label never starts off the left edge`() {
        // Glyph hard against the left edge: flipping would put the label at a negative x.
        val left = labelLeft(glyphX = 5f, offset = offset, labelWidth = width, viewportWidth = viewport)

        assertTrue(left >= 0f)
    }

    @Test
    fun `a label always ends inside the viewport`() {
        // Sweep the glyph across the screen and past both edges; nothing may overflow either side.
        var x = -200f
        while (x <= viewport + 200f) {
            val left = labelLeft(x, offset, width, viewport)
            assertTrue(left >= 0f, "label started off-screen at glyphX=$x: $left")
            assertTrue(left + width <= viewport, "label ended off-screen at glyphX=$x: ${left + width}")
            x += 17f
        }
    }

    @Test
    fun `a label wider than the screen is pinned to the left rather than pushed off`() {
        // Degenerate, but a very long name on a narrow screen must still show its beginning.
        assertEquals(0f, labelLeft(glyphX = 500f, offset = offset, labelWidth = 2000f, viewportWidth = viewport))
    }

    @Test
    fun `the flip happens exactly at the edge and not before`() {
        // glyphX 850 puts the label at 880 ending at exactly 1080 — flush with the edge, so it
        // stays on the right.
        assertEquals(880f, labelLeft(glyphX = 850f, offset = offset, labelWidth = width, viewportWidth = viewport))
        // One pixel further and it no longer fits, so it flips to the left of the glyph.
        assertEquals(621f, labelLeft(glyphX = 851f, offset = offset, labelWidth = width, viewportWidth = viewport))
    }
}

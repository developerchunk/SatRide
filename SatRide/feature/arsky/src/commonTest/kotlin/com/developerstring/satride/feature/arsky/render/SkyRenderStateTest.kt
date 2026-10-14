package com.developerstring.satride.feature.arsky.render

import com.developerstring.satride.feature.arsky.projection.ProjectedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Hit-testing works off whatever the last frame drew, so these tests record a frame and then tap
 * it — the same sequence the screen performs.
 */
class SkyRenderStateTest {

    private val radius = 24f

    private fun frameOf(vararg entries: Triple<Int, Pair<Float, Float>, Double>) = SkyRenderState().apply {
        beginFrame()
        entries.forEach { (id, position, elevation) ->
            record(id, ProjectedPoint.of(position.first, position.second), elevation)
        }
    }

    @Test
    fun `a tap on a glyph selects it`() {
        val state = frameOf(Triple(25544, 500f to 500f, 40.0))

        assertEquals(25544, state.hitTest(502f, 498f, radius))
    }

    @Test
    fun `a tap in empty sky selects nothing`() {
        val state = frameOf(Triple(25544, 500f to 500f, 40.0))

        assertNull(state.hitTest(900f, 900f, radius))
    }

    @Test
    fun `the tap radius is a hard boundary`() {
        val state = frameOf(Triple(1, 100f to 100f, 30.0))

        assertEquals(1, state.hitTest(100f + radius - 1f, 100f, radius))
        assertNull(state.hitTest(100f + radius + 1f, 100f, radius))
    }

    @Test
    fun `the nearest glyph wins — not the first one found`() {
        // Satellites cluster, and a fingertip covers far more sky than a glyph does. Taking the
        // first hit would make the result depend on iteration order, so tapping the same pair
        // twice could select different satellites.
        val state = frameOf(
            Triple(1, 500f to 500f, 40.0),
            Triple(2, 510f to 500f, 35.0),
            Triple(3, 520f to 500f, 30.0),
        )

        assertEquals(3, state.hitTest(519f, 500f, radius))
        assertEquals(1, state.hitTest(501f, 500f, radius))
    }

    @Test
    fun `an exact tie keeps the higher-priority satellite`() {
        // The list arrives in label-priority order, so "the earlier one" means "the one that
        // matters more". Without a strict comparison this would depend on loop direction.
        val state = frameOf(
            Triple(11, 300f to 300f, 40.0),
            Triple(22, 300f to 300f, 40.0),
        )

        assertEquals(11, state.hitTest(300f, 300f, radius))
    }

    @Test
    fun `a satellite below the horizon can be tapped`() {
        // With the whole catalog tracked, most of the sky below the horizon is ghosts. A screenful
        // of glyphs that ignore being touched is its own kind of broken.
        val state = frameOf(Triple(999, 400f to 400f, -12.0))

        assertEquals(999, state.hitTest(400f, 400f, radius))
    }

    @Test
    fun `a ghost does not steal a tap from a satellite that is actually up`() {
        // Even though the ghost is nearer the finger. Above-horizon satellites are searched first
        // and win outright, which is why this is two passes rather than one comparison.
        val state = frameOf(
            Triple(999, 400f to 400f, -5.0),
            Triple(25544, 410f to 400f, 20.0),
        )

        assertEquals(25544, state.hitTest(401f, 400f, radius))
    }

    @Test
    fun `the nearest ghost wins when nothing above the horizon is in range`() {
        val state = frameOf(
            Triple(1, 400f to 400f, -5.0),
            Triple(2, 410f to 400f, -30.0),
            // Up, but far outside the tap radius, so it must not be dragged in by the fallback.
            Triple(25544, 900f to 900f, 40.0),
        )

        assertEquals(2, state.hitTest(409f, 400f, radius))
    }

    @Test
    fun `the tap radius still applies to ghosts`() {
        val state = frameOf(Triple(999, 100f to 100f, -20.0))

        assertEquals(999, state.hitTest(100f + radius - 1f, 100f, radius))
        assertNull(state.hitTest(100f + radius + 1f, 100f, radius))
    }

    @Test
    fun `a culled satellite cannot be tapped`() {
        // Behind the camera: it has no screen position at all, and the packed sentinel must not
        // be read as coordinates.
        val state = SkyRenderState().apply {
            beginFrame()
            record(7, ProjectedPoint.CULLED, elevationDeg = 45.0)
        }

        assertNull(state.hitTest(0f, 0f, radius))
        assertNull(state.hitTest(500f, 500f, 100_000f))
    }

    @Test
    fun `a new frame forgets the previous one`() {
        val state = frameOf(Triple(1, 500f to 500f, 40.0))
        assertEquals(1, state.hitTest(500f, 500f, radius))

        state.beginFrame()

        assertNull(state.hitTest(500f, 500f, radius), "a tap must never hit last frame's positions")
        assertEquals(0, state.count)
    }

    @Test
    fun `more satellites than the initial capacity are recorded`() {
        val state = SkyRenderState().apply {
            beginFrame()
            repeat(200) { index ->
                record(index, ProjectedPoint.of(index * 50f, 100f), elevationDeg = 30.0)
            }
        }

        assertEquals(200, state.count)
        assertEquals(199, state.hitTest(199 * 50f, 100f, radius))
    }

    @Test
    fun `recorded geometry is readable back by index`() {
        // The label pass walks the recorded frame rather than re-reading positions, so the
        // accessors have to agree with what was written.
        val state = frameOf(Triple(42, 123f to 456f, 10.0))

        assertEquals(42, state.idAt(0))
        assertEquals(123f, state.xAt(0))
        assertEquals(456f, state.yAt(0))
        assertEquals(true, state.isProjectedAt(0))
        assertEquals(true, state.isAboveHorizonAt(0))
    }
}

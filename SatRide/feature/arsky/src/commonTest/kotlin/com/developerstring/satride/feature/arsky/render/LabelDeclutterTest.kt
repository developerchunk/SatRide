package com.developerstring.satride.feature.arsky.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelDeclutterTest {

    private val second = LabelDeclutter.DEFAULT_HOLD_NANOS

    @Test
    fun `the first label offered always fits`() {
        val declutter = LabelDeclutter()
        declutter.beginFrame()

        assertTrue(declutter.offer(id = 1, left = 0f, top = 0f, right = 100f, bottom = 20f, nowNanos = 0))
    }

    @Test
    fun `an overlapping label is refused`() {
        val declutter = LabelDeclutter()
        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, 0)

        assertFalse(declutter.offer(2, 50f, 10f, 150f, 30f, 0))
    }

    @Test
    fun `a label that clears the first is allowed`() {
        val declutter = LabelDeclutter()
        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, 0)

        assertTrue(declutter.offer(2, 101f, 0f, 200f, 20f, 0))
    }

    @Test
    fun `boxes that merely touch do not count as overlapping`() {
        // Labels laid out edge to edge are the common case in a dense field; treating a shared
        // boundary as a collision would hide every second one for no visible benefit.
        val declutter = LabelDeclutter()
        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, 0)

        assertTrue(declutter.offer(2, 100f, 0f, 200f, 20f, 0))
    }

    @Test
    fun `priority order decides who wins — because offers are greedy`() {
        val declutter = LabelDeclutter()
        declutter.beginFrame()

        // The caller offers the important label first; the contested one loses.
        assertTrue(declutter.offer(id = 99, left = 40f, top = 0f, right = 140f, bottom = 20f, nowNanos = 0))
        assertFalse(declutter.offer(id = 1, left = 0f, top = 0f, right = 100f, bottom = 20f, nowNanos = 0))
    }

    @Test
    fun `a newly seen label appears at once rather than waiting out the hysteresis window`() {
        val declutter = LabelDeclutter()
        declutter.beginFrame()

        // Opening the sky view must not show a second of nameless glyphs.
        assertTrue(declutter.offer(7, 0f, 0f, 100f, 20f, nowNanos = 12345))
    }

    @Test
    fun `a brief collision does not hide a label that is already shown`() {
        val declutter = LabelDeclutter()

        declutter.beginFrame()
        assertTrue(declutter.offer(1, 0f, 0f, 100f, 20f, 0))

        // A later frame where a higher-priority label now covers it. This is the hand-tremor
        // case: the geometry says hide, but the decision has not been held long enough.
        declutter.beginFrame()
        declutter.offer(99, 0f, 0f, 100f, 20f, second / 4)
        assertTrue(
            declutter.offer(1, 10f, 0f, 110f, 20f, second / 4),
            "a label must not vanish the instant something clips it",
        )
    }

    @Test
    fun `a sustained collision does hide the label once the window has passed`() {
        val declutter = LabelDeclutter()

        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, 0)

        var now = 0L
        repeat(4) {
            now += second / 2
            declutter.beginFrame()
            declutter.offer(99, 0f, 0f, 100f, 20f, now)
            declutter.offer(1, 10f, 0f, 110f, 20f, now)
        }

        assertFalse(declutter.isShown(1), "a collision held for two seconds should win")
    }

    @Test
    fun `the hysteresis clock restarts when the verdict flips back`() {
        val declutter = LabelDeclutter()

        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, 0)

        // Collides, clears, collides again — each flip resets the timer, so it never accumulates
        // the full second and the label stays put. This is exactly the tremor case.
        var now = 0L
        repeat(10) { index ->
            now += second / 3
            declutter.beginFrame()
            if (index % 2 == 0) declutter.offer(99, 0f, 0f, 100f, 20f, now)
            declutter.offer(1, 10f, 0f, 110f, 20f, now)
        }

        assertTrue(declutter.isShown(1), "an alternating collision must not strobe the label")
    }

    @Test
    fun `a hidden label reappears once the space is clear again for long enough`() {
        val declutter = LabelDeclutter()
        var now = 0L

        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, now)

        repeat(4) {
            now += second
            declutter.beginFrame()
            declutter.offer(99, 0f, 0f, 100f, 20f, now)
            declutter.offer(1, 10f, 0f, 110f, 20f, now)
        }
        assertFalse(declutter.isShown(1))

        repeat(4) {
            now += second
            declutter.beginFrame()
            declutter.offer(1, 10f, 0f, 110f, 20f, now)
        }
        assertTrue(declutter.isShown(1))
    }

    @Test
    fun `space is reserved by what is drawn — not by what geometry alone allows`() {
        val declutter = LabelDeclutter()
        var now = 0L

        // Get label 1 hidden by a sustained collision.
        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, now)
        repeat(4) {
            now += second
            declutter.beginFrame()
            declutter.offer(99, 0f, 0f, 100f, 20f, now)
            declutter.offer(1, 0f, 0f, 100f, 20f, now)
        }
        assertFalse(declutter.isShown(1))

        // Now offer a third label over the space label 1 is *not* using. It must be allowed:
        // an invisible label reserving space would leave a hole nothing could ever fill.
        now += second
        declutter.beginFrame()
        declutter.offer(1, 300f, 300f, 400f, 320f, now)
        assertTrue(declutter.offer(2, 300f, 300f, 400f, 320f, now))
    }

    @Test
    fun `forgetting satellites that have set keeps the state from growing all session`() {
        val declutter = LabelDeclutter()
        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, 0)
        declutter.offer(2, 200f, 0f, 300f, 20f, 0)
        assertTrue(declutter.isShown(1))

        declutter.retainOnly(intArrayOf(2), count = 1)

        assertFalse(declutter.isShown(1), "a forgotten satellite should carry no state back")
        assertTrue(declutter.isShown(2))
    }

    @Test
    fun `a satellite that rises again is treated as new rather than resuming its old state`() {
        val declutter = LabelDeclutter()
        var now = 0L

        declutter.beginFrame()
        declutter.offer(1, 0f, 0f, 100f, 20f, now)
        repeat(4) {
            now += second
            declutter.beginFrame()
            declutter.offer(99, 0f, 0f, 100f, 20f, now)
            declutter.offer(1, 0f, 0f, 100f, 20f, now)
        }
        assertFalse(declutter.isShown(1))

        declutter.retainOnly(IntArray(0), count = 0)

        now += second
        declutter.beginFrame()
        assertTrue(declutter.offer(1, 0f, 0f, 100f, 20f, now), "hours later, it starts fresh")
    }

    @Test
    fun `many more labels than the initial capacity are handled`() {
        val declutter = LabelDeclutter()
        declutter.beginFrame()

        // Laid out so none collide; the point is that growing the arrays does not lose state.
        repeat(100) { index ->
            val top = index * 30f
            assertTrue(declutter.offer(index, 0f, top, 100f, top + 20f, 0))
        }
        repeat(100) { index -> assertTrue(declutter.isShown(index)) }
    }
}

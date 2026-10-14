package com.developerstring.satride.feature.arsky.render

/**
 * Decides which satellite names can be drawn without turning the sky into a wall of text.
 *
 * ## The two problems
 *
 * **Overlap.** Satellites cluster — a polar-orbit train, a constellation of CubeSats deployed on
 * the same launch — and their labels then sit on top of each other and none of them can be read.
 * Solved greedily: offer labels in priority order and keep the ones that fit.
 *
 * **Flicker.** Purely geometric decluttering flickers badly, and the reason is worth stating
 * because the fix looks like superstition otherwise. The phone is never quite still: a degree of
 * hand tremor moves a label by tens of pixels, so two labels near the collision threshold swap
 * between fitting and not fitting several times a second. The result is text strobing on and off
 * across the sky, which is far more distracting than a hidden label. So a decision, once made, is
 * held for [holdNanos] before it may be reversed.
 *
 * ## Shape
 *
 * Mutable and reused across frames. It has to remember what it showed last frame to apply
 * hysteresis at all, and the render loop cannot afford to allocate — so state lives in parallel
 * arrays that grow only when the tracked set does.
 *
 * Not thread-safe; it belongs to the frame loop.
 */
internal class LabelDeclutter(private val holdNanos: Long = DEFAULT_HOLD_NANOS) {

    /** Bounding boxes already granted this frame, as [left, top, right, bottom] runs. */
    private var placed = FloatArray(INITIAL_CAPACITY * 4)
    private var placedCount = 0

    private var ids = IntArray(INITIAL_CAPACITY)
    private var shown = BooleanArray(INITIAL_CAPACITY)

    /** Whether a slot has ever been offered, so first sight can skip the hysteresis window. */
    private var seen = BooleanArray(INITIAL_CAPACITY)

    /** When the *current* geometric verdict for each label first held. */
    private var pendingSince = LongArray(INITIAL_CAPACITY)
    private var pendingVerdict = BooleanArray(INITIAL_CAPACITY)
    private var known = 0

    /** Starts a frame. Call once before the frame's [offer] calls. */
    fun beginFrame() {
        placedCount = 0
    }

    /**
     * Asks whether a label may be drawn, and reserves its space if so.
     *
     * Must be called in descending priority order: the greedy choice is only as good as the order
     * it is offered in, and the order encodes what matters — a satellite the user is guiding to
     * beats one that is merely transmitting, which beats one that is merely high in the sky.
     *
     * @return true if the label should be drawn this frame
     */
    fun offer(id: Int, left: Float, top: Float, right: Float, bottom: Float, nowNanos: Long): Boolean {
        val fits = !collidesWithPlaced(left, top, right, bottom)
        val slot = slotFor(id)

        if (!seen[slot]) {
            // First sight: adopt the verdict at once. Hysteresis exists to damp *changes*, and
            // applying it here instead would mean opening the sky view to a second of nameless
            // glyphs — the flicker cure producing a worse symptom than the disease.
            seen[slot] = true
            shown[slot] = fits
            pendingVerdict[slot] = fits
            pendingSince[slot] = nowNanos
        } else {
            // A change of verdict must survive holdNanos before it takes effect.
            if (pendingVerdict[slot] != fits) {
                pendingVerdict[slot] = fits
                pendingSince[slot] = nowNanos
            }
            if (shown[slot] != fits && nowNanos - pendingSince[slot] >= holdNanos) {
                shown[slot] = fits
            }
        }

        // Space is reserved by what is actually drawn, not by what geometry alone would allow.
        // Reserving on `fits` instead would let a label that hysteresis is still hiding block a
        // visible one, and the gap it left would be a hole nothing could fill.
        if (shown[slot]) reserve(left, top, right, bottom)
        return shown[slot]
    }

    /**
     * Forgets labels for satellites that are no longer tracked.
     *
     * Without this the arrays grow for the whole session — every satellite that has ever risen
     * keeps a slot — and a satellite that sets and rises again hours later would come back
     * carrying its stale visibility.
     */
    fun retainOnly(liveIds: IntArray, count: Int) {
        var write = 0
        for (read in 0 until known) {
            var live = false
            for (i in 0 until count) {
                if (liveIds[i] == ids[read]) {
                    live = true
                    break
                }
            }
            if (!live) continue
            ids[write] = ids[read]
            shown[write] = shown[read]
            seen[write] = seen[read]
            pendingSince[write] = pendingSince[read]
            pendingVerdict[write] = pendingVerdict[read]
            write++
        }
        known = write
    }

    /** Whether a label was drawn on the last frame it was offered. Test and hit-test support. */
    fun isShown(id: Int): Boolean {
        for (i in 0 until known) if (ids[i] == id) return shown[i]
        return false
    }

    private fun collidesWithPlaced(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        for (i in 0 until placedCount) {
            val base = i * 4
            // Standard separating-axis test for two axis-aligned boxes: they overlap unless one
            // is entirely to one side of the other.
            if (left < placed[base + 2] && right > placed[base] &&
                top < placed[base + 3] && bottom > placed[base + 1]
            ) {
                return true
            }
        }
        return false
    }

    private fun reserve(left: Float, top: Float, right: Float, bottom: Float) {
        if ((placedCount + 1) * 4 > placed.size) {
            placed = placed.copyOf(placed.size * 2)
        }
        val base = placedCount * 4
        placed[base] = left
        placed[base + 1] = top
        placed[base + 2] = right
        placed[base + 3] = bottom
        placedCount++
    }

    private fun slotFor(id: Int): Int {
        for (i in 0 until known) if (ids[i] == id) return i
        if (known == ids.size) {
            ids = ids.copyOf(ids.size * 2)
            shown = shown.copyOf(shown.size * 2)
            seen = seen.copyOf(seen.size * 2)
            pendingSince = pendingSince.copyOf(pendingSince.size * 2)
            pendingVerdict = pendingVerdict.copyOf(pendingVerdict.size * 2)
        }
        ids[known] = id
        seen[known] = false
        shown[known] = false
        pendingVerdict[known] = false
        pendingSince[known] = 0L
        return known++
    }

    companion object {
        /**
         * How long a label must want to change state before it is allowed to.
         *
         * A second: long enough that hand tremor cannot drive it, short enough that deliberately
         * panning to a satellite reveals its name without feeling like a wait.
         */
        const val DEFAULT_HOLD_NANOS: Long = 1_000_000_000L

        private const val INITIAL_CAPACITY = 32
    }
}

package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.orbit.math.Vector3
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filter that decides whether the AR view feels solid or cheap.
 *
 * The two requirements pull against each other — reject jitter, but do not lag the phone — so
 * both are measured here rather than eyeballed. The numbers come from P3.3's acceptance criteria.
 */
class AttitudeSmootherTest {

    private val sampleRateHz = 50
    private val nanosPerSample = 1_000_000_000L / sampleRateHz

    private fun yaw(degrees: Double): Quaternion =
        Quaternion.fromAxisAngle(AttitudeFrames.UP, degrees * Constants.DEG_TO_RAD)

    private fun sampleAt(index: Int, rotation: Quaternion) = AttitudeSample(rotation, index * nanosPerSample)

    private fun angleDeg(a: Quaternion, b: Quaternion) = a.angleTo(b) * Constants.RAD_TO_DEG

    @Test
    fun theFirstSampleIsPassedStraightThrough() {
        val smoother = AttitudeSmoother()
        val first = yaw(42.0)

        assertEquals(first, smoother.update(sampleAt(0, first)))
    }

    /**
     * A hard slew has to be tracked, not filtered away. 400 ms is the point at which lag stops
     * reading as smoothing and starts reading as the app being slow.
     */
    @Test
    fun aLargeMovementIsFollowedWithinFourHundredMilliseconds() {
        val smoother = AttitudeSmoother()
        val target = yaw(90.0)
        smoother.update(sampleAt(0, yaw(0.0)))

        val settleWithinDeg = 1.0
        var settledAtSample = -1
        for (i in 1..(sampleRateHz * 2)) {
            val output = smoother.update(sampleAt(i, target))
            if (settledAtSample < 0 && angleDeg(output, target) < settleWithinDeg) settledAtSample = i
        }

        println("90 deg step settled to within 1 deg after ${settledAtSample * 1000 / sampleRateHz} ms")
        assertTrue(settledAtSample in 1..(sampleRateHz * 4 / 10), "settled after $settledAtSample samples")
    }

    /**
     * Slerp is a convex interpolation, so it cannot overshoot — but only while it always
     * interpolates *towards* the sample. This checks the property rather than assuming it, since
     * a sign error in the shorter-arc handling would produce exactly an overshoot.
     */
    @Test
    fun aLargeMovementDoesNotOvershootOrOscillate() {
        val smoother = AttitudeSmoother()
        val target = yaw(90.0)
        smoother.update(sampleAt(0, yaw(0.0)))

        var previousError = 90.0
        for (i in 1..(sampleRateHz * 2)) {
            val error = angleDeg(smoother.update(sampleAt(i, target)), target)
            assertTrue(error <= previousError + 1e-9, "error grew at sample $i: $previousError -> $error")
            previousError = error
        }
        assertTrue(previousError < 0.1, "never converged; final error $previousError")
    }

    /**
     * The requirement the whole design exists for: a still phone must not shimmer.
     *
     * A magnetometer at rest wanders by a degree or so. Attenuating that tenfold is what makes
     * a satellite glyph sit still enough to tap.
     */
    @Test
    fun jitterIsAttenuatedAtLeastTenfold() {
        val smoother = AttitudeSmoother()
        val random = Random(seed = 20260802)
        val truth = yaw(120.0)
        val noiseAmplitudeDeg = 1.0

        var inputSumSquares = 0.0
        var outputSumSquares = 0.0
        var counted = 0

        for (i in 0..(sampleRateHz * 20)) {
            val noisy = truth * randomSmallRotation(random, noiseAmplitudeDeg)
            val output = smoother.update(sampleAt(i, noisy))

            // Skip the first two seconds: the filter starts on the first noisy sample and needs
            // to converge before its steady-state noise floor means anything.
            if (i > sampleRateHz * 2) {
                inputSumSquares += angleDeg(noisy, truth).let { it * it }
                outputSumSquares += angleDeg(output, truth).let { it * it }
                counted++
            }
        }

        val inputRms = sqrt(inputSumSquares / counted)
        val outputRms = sqrt(outputSumSquares / counted)
        val attenuation = inputRms / outputRms

        println(
            "jitter: input $inputRms deg RMS -> output $outputRms deg RMS (${attenuation}x)",
        )
        assertTrue(
            attenuation >= 10.0,
            "attenuated only ${attenuation}x (input $inputRms deg RMS, output $outputRms deg RMS)",
        )
    }

    /**
     * The two regimes have to be genuinely different, otherwise the adaptation is decorative.
     * Sustained motion should put the filter in its fast regime; noise should not.
     */
    @Test
    fun sustainedMotionSelectsAShorterTimeConstantThanNoise() {
        val moving = AttitudeSmoother()
        // A steady 60 deg/s slew, which is a brisk but ordinary sweep across the sky.
        for (i in 0..(sampleRateHz * 2)) {
            moving.update(sampleAt(i, yaw(i * 60.0 / sampleRateHz)))
        }
        val movingTimeConstant = moving.timeConstantSeconds

        val still = AttitudeSmoother()
        val random = Random(seed = 7)
        val truth = yaw(10.0)
        for (i in 0..(sampleRateHz * 2)) {
            still.update(sampleAt(i, truth * randomSmallRotation(random, 1.0)))
        }
        val stillTimeConstant = still.timeConstantSeconds

        assertTrue(
            movingTimeConstant < stillTimeConstant / 5.0,
            "moving $movingTimeConstant s vs still $stillTimeConstant s — the filter is not adapting",
        )
    }

    @Test
    fun trackingLagDuringASlewStaysBelowAFewDegrees() {
        val smoother = AttitudeSmoother()
        val degreesPerSecond = 60.0
        var worstLag = 0.0

        for (i in 0..(sampleRateHz * 3)) {
            val truth = yaw(i * degreesPerSecond / sampleRateHz)
            val output = smoother.update(sampleAt(i, truth))
            // Ignore the first half second, which is the filter catching up from rest.
            if (i > sampleRateHz / 2) worstLag = maxOf(worstLag, angleDeg(output, truth))
        }

        println("worst tracking lag at $degreesPerSecond deg/s: $worstLag deg")
        assertTrue(worstLag < 5.0, "lagged by $worstLag degrees at $degreesPerSecond deg/s")
    }

    /**
     * A phone whose orientation crosses the point where the quaternion sign flips must not
     * lurch. q and -q are the same rotation, and a filter that does not account for it takes the
     * long way round the sphere.
     */
    @Test
    fun isStableAcrossQuaternionSignFlips() {
        val smoother = AttitudeSmoother()
        val target = yaw(30.0)
        smoother.update(sampleAt(0, target))

        val negated = Quaternion(-target.w, -target.x, -target.y, -target.z)
        val output = smoother.update(sampleAt(1, negated))

        assertTrue(
            angleDeg(output, target) < 1e-6,
            "a sign-flipped duplicate of the same rotation moved the filter by ${angleDeg(output, target)} deg",
        )
    }

    /**
     * A dropped frame or a return from background can deliver a gap of seconds. Without a clamp
     * the blend factor saturates and the filter snaps to the raw sample, undoing exactly the
     * jitter rejection it exists for.
     */
    @Test
    fun aLongGapBetweenSamplesDoesNotDefeatTheFilter() {
        val config = AttitudeSmoothingConfig()
        val smoother = AttitudeSmoother(config)
        smoother.update(AttitudeSample(yaw(0.0), 0L))

        val output = smoother.update(AttitudeSample(yaw(1.0), 30_000_000_000L))

        // With the interval clamped, a one-degree step is still mostly rejected on one sample.
        assertTrue(angleDeg(output, yaw(0.0)) < 0.5, "moved ${angleDeg(output, yaw(0.0))} deg on one sample")
    }

    @Test
    fun aRepeatedTimestampDoesNotFreezeOrJumpTheOutput() {
        val smoother = AttitudeSmoother()
        smoother.update(AttitudeSample(yaw(0.0), 1_000L))

        val output = smoother.update(AttitudeSample(yaw(45.0), 1_000L))

        assertEquals(0.0, angleDeg(output, yaw(0.0)), 1e-9)
    }

    @Test
    fun resetClearsTheFilterState() {
        val smoother = AttitudeSmoother()
        smoother.update(sampleAt(0, yaw(0.0)))
        smoother.update(sampleAt(1, yaw(10.0)))

        smoother.reset()
        val afterReset = smoother.update(sampleAt(0, yaw(200.0)))

        assertEquals(yaw(200.0), afterReset)
    }

    /** A small random rotation about a random axis, for synthetic sensor noise. */
    private fun randomSmallRotation(random: Random, amplitudeDeg: Double): Quaternion {
        val axis = Vector3(
            random.nextDouble(-1.0, 1.0),
            random.nextDouble(-1.0, 1.0),
            random.nextDouble(-1.0, 1.0),
        )
        val safeAxis = if (axis.magnitude < 1e-6) AttitudeFrames.UP else axis
        return Quaternion.fromAxisAngle(safeAxis, random.nextDouble(-amplitudeDeg, amplitudeDeg) * Constants.DEG_TO_RAD)
    }
}

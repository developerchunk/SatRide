package com.developerstring.satride.core.orbit.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Vector3Test {

    @Test
    fun arithmeticBehavesAsExpected() {
        val a = Vector3(1.0, 2.0, 3.0)
        val b = Vector3(4.0, -5.0, 6.0)

        assertEquals(Vector3(5.0, -3.0, 9.0), a + b)
        assertEquals(Vector3(-3.0, 7.0, -3.0), a - b)
        assertEquals(Vector3(2.0, 4.0, 6.0), a * 2.0)
        assertEquals(Vector3(-1.0, -2.0, -3.0), -a)
        assertEquals(12.0, a dot b, 1e-12)
    }

    @Test
    fun crossProductIsPerpendicularToBothInputs() {
        val a = Vector3(1.0, 2.0, 3.0)
        val b = Vector3(-2.0, 0.5, 4.0)

        val c = a cross b

        assertEquals(0.0, c dot a, 1e-12)
        assertEquals(0.0, c dot b, 1e-12)
        assertEquals(Vector3(0.0, 0.0, 1.0), Vector3(1.0, 0.0, 0.0) cross Vector3(0.0, 1.0, 0.0))
    }

    @Test
    fun normalizationHandlesTheZeroVector() {
        assertEquals(Vector3.ZERO, Vector3.ZERO.normalized())
        assertEquals(1.0, Vector3(3.0, 4.0, 0.0).normalized().magnitude, 1e-12)
    }

    @Test
    fun angleBetweenIsStableAtTheDomainEdges() {
        val v = Vector3(1.0, 0.0, 0.0)

        // Parallel and antiparallel vectors are where a naive acos overflows its domain.
        assertEquals(0.0, v.angleTo(v), 1e-9)
        assertEquals(PI, v.angleTo(-v), 1e-9)
        assertEquals(PI / 2, v.angleTo(Vector3(0.0, 1.0, 0.0)), 1e-12)
    }

    @Test
    fun rotationAboutZPreservesLengthAndHeight() {
        val v = Vector3(3.0, 4.0, 5.0)

        val rotated = v.rotateZ(0.7)

        assertEquals(v.magnitude, rotated.magnitude, 1e-12)
        assertEquals(v.z, rotated.z, 1e-12)
        // Rotating back must return the original.
        assertEquals(v.x, rotated.rotateZ(-0.7).x, 1e-12)
        assertEquals(v.y, rotated.rotateZ(-0.7).y, 1e-12)
    }
}

class QuaternionTest {

    @Test
    fun identityRotatesNothing() {
        val v = Vector3(1.0, 2.0, 3.0)

        val rotated = Quaternion.IDENTITY.rotate(v)

        assertEquals(v.x, rotated.x, 1e-12)
        assertEquals(v.y, rotated.y, 1e-12)
        assertEquals(v.z, rotated.z, 1e-12)
    }

    @Test
    fun quarterTurnAboutZMapsXToY() {
        val q = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), PI / 2)

        val rotated = q.rotate(Vector3(1.0, 0.0, 0.0))

        assertEquals(0.0, rotated.x, 1e-12)
        assertEquals(1.0, rotated.y, 1e-12)
        assertEquals(0.0, rotated.z, 1e-12)
    }

    @Test
    fun conjugateUndoesTheRotation() {
        val q = Quaternion.fromAxisAngle(Vector3(1.0, 2.0, 3.0), 1.1)
        val v = Vector3(4.0, -1.0, 2.0)

        val roundTripped = q.conjugate().rotate(q.rotate(v))

        assertEquals(v.x, roundTripped.x, 1e-12)
        assertEquals(v.y, roundTripped.y, 1e-12)
        assertEquals(v.z, roundTripped.z, 1e-12)
    }

    @Test
    fun compositionAppliesTheRightHandOperandFirst() {
        val z90 = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), PI / 2)
        val x90 = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), PI / 2)

        val combined = (x90 * z90).rotate(Vector3(1.0, 0.0, 0.0))
        val sequential = x90.rotate(z90.rotate(Vector3(1.0, 0.0, 0.0)))

        assertEquals(sequential.x, combined.x, 1e-12)
        assertEquals(sequential.y, combined.y, 1e-12)
        assertEquals(sequential.z, combined.z, 1e-12)
    }

    @Test
    fun slerpEndsMatchTheInputs() {
        val a = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 0.2)
        val b = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 1.4)

        assertTrue(abs((a.slerp(b, 0.0) dot a)) > 0.999999)
        assertTrue(abs((a.slerp(b, 1.0) dot b)) > 0.999999)
    }

    /**
     * q and -q are the same rotation. Without the sign fix, interpolation takes the long way
     * round — which in the AR view reads as the whole sky lurching.
     */
    @Test
    fun slerpTakesTheShortPathBetweenOppositeSignQuaternions() {
        val a = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 0.1)
        val negated = Quaternion(-a.w, -a.x, -a.y, -a.z)

        val midpoint = a.slerp(negated, 0.5)

        // Halfway between a rotation and itself must still be that rotation.
        assertTrue(
            abs(midpoint dot a) > 0.999999,
            "Interpolation drifted away from the identical rotation",
        )
    }

    @Test
    fun slerpStaysNormalisedThroughout() {
        val a = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), 0.3)
        val b = Quaternion.fromAxisAngle(Vector3(0.0, 1.0, 0.4), 2.4)

        for (step in 0..10) {
            val q = a.slerp(b, step / 10.0)

            assertEquals(1.0, q.magnitude, 1e-9, "magnitude at t=${step / 10.0}")
        }
    }

    @Test
    fun nearlyIdenticalRotationsDoNotDivideByZero() {
        val a = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 1.0)
        val b = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 1.0 + 1e-12)

        val q = a.slerp(b, 0.5)

        assertTrue(q.magnitude.isFinite() && abs(q.magnitude - 1.0) < 1e-9)
    }
}

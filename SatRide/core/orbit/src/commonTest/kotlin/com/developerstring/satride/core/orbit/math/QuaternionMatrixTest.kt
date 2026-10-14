package com.developerstring.satride.core.orbit.math

import com.developerstring.satride.core.orbit.Constants
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bridge from Android's sensor stack, which reports orientation as a rotation matrix.
 *
 * Verified by rotating vectors rather than by comparing components, because a quaternion and its
 * negation are the same rotation with different numbers — a component-wise comparison would fail
 * on a correct result half the time.
 */
class QuaternionMatrixTest {

    /** Row-major rotation matrix about an axis, built independently of the code under test. */
    private fun rotationMatrix(axis: Vector3, radians: Double): DoubleArray {
        val n = axis.normalized()
        val c = cos(radians)
        val s = sin(radians)
        val t = 1.0 - c
        return doubleArrayOf(
            t * n.x * n.x + c, t * n.x * n.y - s * n.z, t * n.x * n.z + s * n.y,
            t * n.x * n.y + s * n.z, t * n.y * n.y + c, t * n.y * n.z - s * n.x,
            t * n.x * n.z - s * n.y, t * n.y * n.z + s * n.x, t * n.z * n.z + c,
        )
    }

    private val probes = listOf(
        Vector3(1.0, 0.0, 0.0),
        Vector3(0.0, 1.0, 0.0),
        Vector3(0.0, 0.0, 1.0),
        Vector3(0.37, -0.82, 0.44).normalized(),
    )

    private fun assertSameRotation(matrix: DoubleArray, quaternion: Quaternion, message: String) {
        probes.forEach { v ->
            val byMatrix = Vector3(
                matrix[0] * v.x + matrix[1] * v.y + matrix[2] * v.z,
                matrix[3] * v.x + matrix[4] * v.y + matrix[5] * v.z,
                matrix[6] * v.x + matrix[7] * v.y + matrix[8] * v.z,
            )
            val byQuaternion = quaternion.rotate(v)
            assertTrue(
                abs(byMatrix.x - byQuaternion.x) < 1e-9 &&
                    abs(byMatrix.y - byQuaternion.y) < 1e-9 &&
                    abs(byMatrix.z - byQuaternion.z) < 1e-9,
                "$message: $v -> matrix $byMatrix but quaternion $byQuaternion",
            )
        }
    }

    @Test
    fun reproducesRotationsAboutEachAxis() {
        val axes = mapOf(
            "x" to Vector3(1.0, 0.0, 0.0),
            "y" to Vector3(0.0, 1.0, 0.0),
            "z" to Vector3(0.0, 0.0, 1.0),
            "oblique" to Vector3(0.5, -0.3, 0.81),
        )
        val angles = listOf(0.0, 15.0, 90.0, 179.0, 270.0, 359.0)

        axes.forEach { (name, axis) ->
            angles.forEach { degrees ->
                val matrix = rotationMatrix(axis, degrees * Constants.DEG_TO_RAD)
                assertSameRotation(matrix, Quaternion.fromRotationMatrix(matrix), "$name at $degrees deg")
            }
        }
    }

    /**
     * The case the branchless formula gets wrong. Near 180 degrees the trace approaches -1 and
     * the single-branch derivation divides by nearly zero — and a phone held up to look at the
     * sky is very close to a 180-degree rotation from lying face up.
     */
    @Test
    fun staysAccurateForHalfTurnsWhereTheNaiveFormulaBreaksDown() {
        listOf(
            Vector3(1.0, 0.0, 0.0),
            Vector3(0.0, 1.0, 0.0),
            Vector3(0.0, 0.0, 1.0),
            Vector3(1.0, 1.0, 0.0),
        ).forEach { axis ->
            val matrix = rotationMatrix(axis, Constants.TWO_PI / 2.0)
            val quaternion = Quaternion.fromRotationMatrix(matrix)

            assertTrue(abs(quaternion.magnitude - 1.0) < 1e-9, "not a unit quaternion for axis $axis")
            assertSameRotation(matrix, quaternion, "half turn about $axis")
        }
    }

    @Test
    fun theIdentityMatrixIsTheIdentityRotation() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val quaternion = Quaternion.fromRotationMatrix(identity)

        assertTrue(abs(abs(quaternion.w) - 1.0) < 1e-12, "expected identity, got $quaternion")
    }

    @Test
    fun alwaysReturnsAUnitQuaternion() {
        listOf(0.0, 37.0, 90.0, 143.0, 180.0, 217.0, 359.0).forEach { degrees ->
            val matrix = rotationMatrix(Vector3(0.3, 0.9, -0.2), degrees * Constants.DEG_TO_RAD)
            val magnitude = Quaternion.fromRotationMatrix(matrix).magnitude
            assertTrue(abs(magnitude - 1.0) < 1e-9, "magnitude $magnitude at $degrees deg")
        }
    }

    @Test
    fun angleToIgnoresTheSignAmbiguity() {
        val q = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 1.0)
        val negated = Quaternion(-q.w, -q.x, -q.y, -q.z)

        assertTrue(q.angleTo(negated) < 1e-9, "q and -q are the same rotation")
        assertTrue(q.angleTo(q) < 1e-9)
    }
}

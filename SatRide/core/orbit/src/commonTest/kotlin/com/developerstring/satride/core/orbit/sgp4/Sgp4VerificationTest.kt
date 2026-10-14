package com.developerstring.satride.core.orbit.sgp4

import com.developerstring.satride.core.orbit.TestFixtures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The authoritative SGP4 correctness check: the AIAA 2006-6753 verification set.
 *
 * Thirty-three element sets chosen to exercise every branch of the model — near-Earth and deep
 * space, synchronous and 12-hour resonance, near-zero inclination and eccentricity, backwards
 * propagation, and orbits that decay mid-run. Each is propagated across the time range the
 * verification file specifies and compared against published output.
 *
 * If this test fails, the propagator is wrong. Nothing downstream — pass prediction, Doppler,
 * the AR overlay — can be trusted until it passes again.
 */
class Sgp4VerificationTest {

    private data class TestCase(
        val line1: String,
        val line2: String,
        val startMin: Double,
        val stopMin: Double,
        val stepMin: Double,
    )

    private data class Expected(
        val noradId: Int,
        val tsince: Double,
        val x: Double,
        val y: Double,
        val z: Double,
        val vx: Double,
        val vy: Double,
        val vz: Double,
    )

    private val opsMode = Sgp4OpsMode.IMPROVED

    /**
     * Satellites where the published file and the current algorithm legitimately disagree.
     *
     * 33334 is a deliberately pathological element set whose perturbed eccentricity comes out
     * around -118. `tcppver.out` was generated before the range check that now rejects it, so
     * the file still carries a position for it. Later revisions of the reference — and the
     * Python implementation these fixtures were cross-checked against — both return error 3
     * here, and so do we. Emitting a state vector from a physically impossible eccentricity
     * would be the wrong behaviour, so this is recorded as an expected divergence rather than
     * quietly tolerated.
     */
    private val expectedPropagationErrors = mapOf(33334 to 3)

    // The golden file prints 8 decimal places for position and 9 for velocity, so agreement
    // cannot be demonstrated below roughly 1e-8. These bounds sit just above that floor.
    private val positionToleranceKm = 1e-6
    private val velocityToleranceKmS = 1e-7

    @Test
    fun matchesPublishedVerificationVectors() {
        val cases = parseTestCases()
        val expectedBySatellite = parseExpectedOutput()

        assertTrue(cases.size >= 30, "Expected the full verification set, found ${cases.size}")

        var comparisons = 0
        var worstPosition = 0.0
        var worstVelocity = 0.0
        var worstPositionLabel = ""
        val failures = mutableListOf<String>()

        for (case in cases) {
            val elements = TleParser.parse("verification", case.line1, case.line2)
            val expected = expectedBySatellite[elements.noradId]
                ?: fail("No expected output for satellite ${elements.noradId}")

            val sgp4 = try {
                Sgp4(elements, opsMode)
            } catch (e: Exception) {
                failures += "sat ${elements.noradId}: initialisation threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            for (row in expected) {
                val actual = try {
                    sgp4.propagate(row.tsince)
                } catch (e: PropagationException) {
                    val expected = expectedPropagationErrors[elements.noradId]
                    if (expected == null) {
                        failures += "sat ${elements.noradId} t=${row.tsince}: " +
                            "propagation failed (code ${e.code}) but reference has a value"
                    } else if (expected != e.code) {
                        failures += "sat ${elements.noradId} t=${row.tsince}: " +
                            "expected error $expected, got ${e.code}"
                    }
                    continue
                }

                val dx = abs(actual.position.x - row.x)
                val dy = abs(actual.position.y - row.y)
                val dz = abs(actual.position.z - row.z)
                val dvx = abs(actual.velocity.x - row.vx)
                val dvy = abs(actual.velocity.y - row.vy)
                val dvz = abs(actual.velocity.z - row.vz)

                val worstPos = maxOf(dx, dy, dz)
                val worstVel = maxOf(dvx, dvy, dvz)
                if (worstPos > worstPosition) {
                    worstPosition = worstPos
                    worstPositionLabel = "sat ${elements.noradId} t=${row.tsince}"
                }
                if (worstVel > worstVelocity) worstVelocity = worstVel

                if (worstPos > positionToleranceKm || worstVel > velocityToleranceKmS) {
                    if (failures.size < 10) {
                        failures += "sat ${elements.noradId} t=${row.tsince}: " +
                            "dpos=$worstPos km dvel=$worstVel km/s\n" +
                            "    expected r=(${row.x}, ${row.y}, ${row.z})\n" +
                            "    actual   r=(${actual.position.x}, ${actual.position.y}, ${actual.position.z})"
                    }
                }
                comparisons++
            }
        }

        println(
            "SGP4 verification: ${cases.size} satellites, $comparisons comparisons; " +
                "worst position error $worstPosition km ($worstPositionLabel), " +
                "worst velocity error $worstVelocity km/s",
        )

        if (failures.isNotEmpty()) {
            fail(
                "SGP4 does not match the AIAA verification vectors " +
                    "(${failures.size} problems, first 10 shown):\n" + failures.joinToString("\n"),
            )
        }
        assertTrue(comparisons > 500, "Only $comparisons comparisons ran; the fixture looks truncated")
    }

    @Test
    fun rejectsTheKnownPathologicalElementSet() {
        val case = parseTestCases().first {
            TleParser.parse("t", it.line1, it.line2).noradId == 33334
        }
        val sgp4 = Sgp4(TleParser.parse("t", case.line1, case.line2), opsMode)

        val error = assertFailsWith<PropagationException> { sgp4.propagate(0.0) }

        assertEquals(3, error.code, "Expected the eccentricity-out-of-range error")
    }

    @Test
    fun deepSpaceCasesUseTheSdp4Branch() {
        val deepSpace = parseTestCases()
            .map { TleParser.parse("verification", it.line1, it.line2) }
            .map { Sgp4(it, opsMode) }
            .filter { it.method == Sgp4Method.DEEP_SPACE }

        // The verification set deliberately includes Molniya, geosynchronous and other
        // long-period orbits; if none of them take the deep-space path, the 225-minute
        // threshold is broken and SDP4 is never exercised.
        assertTrue(
            deepSpace.size >= 5,
            "Expected several deep-space cases, found ${deepSpace.size}",
        )
    }

    /** Reads the TLEs plus the trailing start/stop/step columns the verification file adds. */
    private fun parseTestCases(): List<TestCase> {
        val lines = TestFixtures.lines("SGP4-VER.TLE")
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val cases = mutableListOf<TestCase>()
        var i = 0
        while (i < lines.size - 1) {
            val l1 = lines[i]
            val l2 = lines[i + 1]
            if (l1.startsWith("1 ") && l2.startsWith("2 ")) {
                // Columns past the standard 69 carry the test time range.
                val extra = if (l2.length > 69) {
                    l2.substring(69).trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                } else {
                    emptyList()
                }
                cases += TestCase(
                    line1 = l1,
                    line2 = l2,
                    startMin = extra.getOrElse(0) { 0.0 },
                    stopMin = extra.getOrElse(1) { 1440.0 },
                    stepMin = extra.getOrElse(2) { 360.0 },
                )
                i += 2
            } else {
                i += 1
            }
        }
        return cases
    }

    /** Reads `tcppver.out`, which is grouped into `<satnum> xx` blocks of time steps. */
    private fun parseExpectedOutput(): Map<Int, List<Expected>> {
        val result = mutableMapOf<Int, MutableList<Expected>>()
        var current = -1
        for (raw in TestFixtures.lines("tcppver.out")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.endsWith(" xx")) {
                current = line.removeSuffix(" xx").trim().toIntOrNull() ?: -1
                if (current > 0) result.getOrPut(current) { mutableListOf() }
                continue
            }
            if (current <= 0) continue
            val parts = line.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (parts.size < 7) continue
            result.getValue(current) += Expected(
                noradId = current,
                tsince = parts[0],
                x = parts[1],
                y = parts[2],
                z = parts[3],
                vx = parts[4],
                vy = parts[5],
                vz = parts[6],
            )
        }
        return result
    }
}

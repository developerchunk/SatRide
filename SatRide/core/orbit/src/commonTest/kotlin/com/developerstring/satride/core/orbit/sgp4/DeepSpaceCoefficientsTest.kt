package com.developerstring.satride.core.orbit.sgp4

import com.developerstring.satride.core.orbit.TestFixtures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the lunar-solar coefficients that `dscom` produces, against values taken from the
 * python-sgp4 reference implementation.
 *
 * These exist because of a real defect. The published SGP4 vectors propagate by elapsed
 * minutes, so an error in the *absolute* epoch cancels out completely and every one of the 762
 * golden comparisons still passed while `julianDateOfYearStart` was half a day out. The lunar
 * terms are the first place that mistake becomes visible, because they are driven by the days
 * elapsed since 1949 rather than by time since epoch. Checking them directly turns a subtle,
 * far-downstream symptom into an immediate and obvious failure.
 */
class DeepSpaceCoefficientsTest {

    private val expected: Map<Int, Map<String, Double>> = mapOf(
        // Molniya-class, 12-hour resonance.
        8195 to mapOf(
            "se2" to 0.002501941522642427,
            "se3" to 0.001007241988530131,
            "si2" to -0.0011105699174389213,
            "si3" to 2.7357459043652322e-05,
            "sl2" to 0.004083492666433326,
            "sl3" to -0.0021191158730878203,
            "sl4" to 0.00028883025414443435,
            "sgh2" to -0.001261915509700673,
            "sgh3" to 0.0024557462815410934,
            "sgh4" to -7.471990261373487e-05,
            "sh2" to 0.000945803727082508,
            "sh3" to -0.003970223690619602,
            "ee2" to -0.00010934280539374914,
            "e3" to -0.0004408046133189363,
            "xi2" to 0.00011424082985422759,
            "xi3" to 0.0001200089628145214,
            "xl2" to -0.0007862643771994418,
            "xl3" to -0.0004559423560477026,
            "xl4" to 0.00015205224896677633,
            "xgh2" to 0.00045492024438304233,
            "xgh3" to -2.4648151078814794e-05,
            "xgh4" to -3.933566193974774e-05,
            "xh2" to -0.0006438340814881557,
            "xh3" to 0.00030794292793485343,
            "zmol" to 1.703291771990898,
            "zmos" to 2.972158040325546,
            "gsto" to 0.574180126904011,
        ),
        // Highly eccentric deep-space orbit.
        23333 to mapOf(
            "se2" to 0.04629809809050963,
            "se3" to 0.04271626851496853,
            "si2" to 0.08750068533753627,
            "si3" to 0.035116477158559256,
            "sl2" to 0.3692252174203697,
            "sl3" to -0.4025541103841882,
            "sl4" to 0.009259903790801008,
            "sgh2" to -0.043914801636940455,
            "sgh3" to 0.04776472532865026,
            "sgh4" to -0.0006536713592880533,
            "sh2" to 0.0508705566995819,
            "sh3" to 0.021941234461940802,
            "ee2" to -0.007503198877722854,
            "e3" to 0.006618130467595364,
            "xi2" to -0.019565898713799226,
            "xi3" to 0.02039566495960478,
            "xl2" to 0.0582532842331969,
            "xl3" to 0.06572632496891886,
            "xl4" to 0.004874798177836254,
            "xgh2" to -0.00687829292973857,
            "xgh3" to -0.007775516402908015,
            "xgh4" to -0.0003441197687525333,
            "xh2" to -0.011795764942147208,
            "xh3" to 0.011690374638795888,
            "zmol" to 5.607868196209495,
            "zmos" to 5.196058843771105,
            "gsto" to 3.84921956856428,
        ),
    )

    @Test
    fun lunarAndSolarCoefficientsMatchTheReferenceImplementation() {
        val tles = verificationTles()

        for ((noradId, expectedCoefficients) in expected) {
            val lines = tles[noradId] ?: error("Satellite $noradId missing from the fixture")
            val sgp4 = Sgp4(
                TleParser.parse("verification", lines.first, lines.second),
                Sgp4OpsMode.IMPROVED,
            )
            val actual = sgp4.debugCoefficients()

            for ((name, expectedValue) in expectedCoefficients) {
                val actualValue = assertNotNull(actual[name], "sat $noradId: missing $name")
                // Relative comparison: these span six orders of magnitude.
                val scale = maxOf(abs(expectedValue), 1e-9)
                val relativeError = abs(actualValue - expectedValue) / scale
                assertTrue(
                    relativeError < 1e-10,
                    "sat $noradId $name: expected $expectedValue, got $actualValue " +
                        "(relative error $relativeError)",
                )
            }
        }
    }

    private fun verificationTles(): Map<Int, Pair<String, String>> {
        val lines = TestFixtures.lines("SGP4-VER.TLE")
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val result = mutableMapOf<Int, Pair<String, String>>()
        for (i in 0 until lines.size - 1) {
            if (lines[i].startsWith("1 ") && lines[i + 1].startsWith("2 ")) {
                val id = lines[i].substring(2, 7).trim().toIntOrNull() ?: continue
                result[id] = lines[i] to lines[i + 1]
            }
        }
        return result
    }
}

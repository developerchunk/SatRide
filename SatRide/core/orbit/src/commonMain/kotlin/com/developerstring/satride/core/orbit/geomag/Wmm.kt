package com.developerstring.satride.core.orbit.geomag

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.time.AstroTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/** The magnetic field at a point, in the local horizontal frame. */
public data class MagneticField(
    /** Northward component, nT. */
    public val northNt: Double,
    /** Eastward component, nT. */
    public val eastNt: Double,
    /** Downward component, nT. */
    public val downNt: Double,
) {
    /**
     * Angle from true north to magnetic north, degrees, positive east.
     *
     * This is the number the AR view needs: a phone's compass reports magnetic north, and the
     * sky is indexed to true north. In parts of the world the two differ by more than 20
     * degrees, which would put every satellite in visibly the wrong place.
     */
    public val declinationDeg: Double get() = atan2(eastNt, northNt) * Constants.RAD_TO_DEG

    /** Angle below horizontal, degrees. */
    public val inclinationDeg: Double
        get() = atan2(downNt, sqrt(northNt * northNt + eastNt * eastNt)) * Constants.RAD_TO_DEG

    /** Total field strength, nT. */
    public val intensityNt: Double
        get() = sqrt(northNt * northNt + eastNt * eastNt + downNt * downNt)
}

/**
 * World Magnetic Model evaluation.
 *
 * Implements the spherical-harmonic expansion described in the NOAA/NCEI WMM technical report.
 * SatRide uses it in one place and for one reason: converting the magnetic heading a phone
 * reports into a true heading. Doing this in shared code rather than through each platform's
 * own geomagnetic API means Android and iOS agree to the last decimal, which matters when the
 * same satellite has to appear in the same place on both.
 */
public object Wmm {

    /** The model expires five years after its epoch and must be replaced, not extrapolated. */
    public const val VALID_UNTIL_YEAR: Double = WmmCoefficients.EPOCH_YEAR + 5.0

    private const val MAX_DEGREE = WmmCoefficients.MAX_DEGREE
    private const val ARRAY_SIZE = (MAX_DEGREE + 1) * (MAX_DEGREE + 2) / 2

    /** Geomagnetic reference radius, km. */
    private const val EARTH_RADIUS_KM = 6371.2

    /** Declination in degrees, positive east of true north. */
    public fun declinationDeg(position: GeoPos, time: Instant): Double =
        field(position, AstroTime.decimalYear(time)).declinationDeg

    /** Declination at an explicit [decimalYear], e.g. 2026.5. */
    public fun declinationDeg(position: GeoPos, decimalYear: Double): Double =
        field(position, decimalYear).declinationDeg

    /** True heading for a [magneticHeadingDeg] reported by a device compass. */
    public fun toTrueHeadingDeg(magneticHeadingDeg: Double, position: GeoPos, time: Instant): Double {
        val trueHeading = magneticHeadingDeg + declinationDeg(position, time)
        return ((trueHeading % 360.0) + 360.0) % 360.0
    }

    /** Whether [decimalYear] falls inside the model's validity window. */
    public fun isValidFor(decimalYear: Double): Boolean =
        decimalYear >= WmmCoefficients.EPOCH_YEAR && decimalYear <= VALID_UNTIL_YEAR

    /**
     * Full field vector at [position] and [decimalYear].
     *
     * Times outside the validity window are still evaluated — a stale model is far better than
     * no correction at all — but [isValidFor] lets the caller warn.
     */
    public fun field(position: GeoPos, decimalYear: Double): MagneticField {
        val dt = decimalYear - WmmCoefficients.EPOCH_YEAR

        // Geodetic to geocentric: the model is expressed about the Earth's centre, while the
        // observer's latitude is relative to the ellipsoid's surface normal.
        val latRad = position.latDeg * Constants.DEG_TO_RAD
        val lonRad = position.lonDeg * Constants.DEG_TO_RAD
        val altKm = position.altM / 1000.0
        val a = Constants.WGS84_SEMI_MAJOR_KM
        val e2 = Constants.WGS84_ECCENTRICITY_SQ
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val rc = a / sqrt(1.0 - e2 * sinLat * sinLat)
        val p = (rc + altKm) * cosLat
        val z = (rc * (1.0 - e2) + altKm) * sinLat
        val r = sqrt(p * p + z * z)
        val geocentricLatRad = kotlin.math.asin(z / r)

        val sinGeocentricLat = sin(geocentricLatRad)
        val cosGeocentricLat = cos(geocentricLatRad)

        val legendre = legendreSchmidt(sinGeocentricLat, cosGeocentricLat)

        val cosMLambda = DoubleArray(MAX_DEGREE + 1)
        val sinMLambda = DoubleArray(MAX_DEGREE + 1)
        cosMLambda[0] = 1.0
        sinMLambda[0] = 0.0
        if (MAX_DEGREE >= 1) {
            cosMLambda[1] = cos(lonRad)
            sinMLambda[1] = sin(lonRad)
            for (m in 2..MAX_DEGREE) {
                cosMLambda[m] = cosMLambda[m - 1] * cosMLambda[1] - sinMLambda[m - 1] * sinMLambda[1]
                sinMLambda[m] = cosMLambda[m - 1] * sinMLambda[1] + sinMLambda[m - 1] * cosMLambda[1]
            }
        }

        val relativeRadiusPower = DoubleArray(MAX_DEGREE + 1)
        val ratio = EARTH_RADIUS_KM / r
        relativeRadiusPower[0] = ratio * ratio
        for (n in 1..MAX_DEGREE) {
            relativeRadiusPower[n] = relativeRadiusPower[n - 1] * ratio
        }

        var bx = 0.0
        var by = 0.0
        var bz = 0.0
        for (n in 1..MAX_DEGREE) {
            for (m in 0..n) {
                val index = n * (n + 1) / 2 + m
                val g = WmmCoefficients.G[index] + dt * WmmCoefficients.DG[index]
                val h = WmmCoefficients.H[index] + dt * WmmCoefficients.DH[index]

                bz -= relativeRadiusPower[n] *
                    (g * cosMLambda[m] + h * sinMLambda[m]) *
                    (n + 1).toDouble() * legendre.p[index]
                by += relativeRadiusPower[n] *
                    (g * sinMLambda[m] - h * cosMLambda[m]) *
                    m.toDouble() * legendre.p[index]
                bx -= relativeRadiusPower[n] *
                    (g * cosMLambda[m] + h * sinMLambda[m]) * legendre.dp[index]
            }
        }

        by = if (abs(cosGeocentricLat) > 1.0e-10) {
            by / cosGeocentricLat
        } else {
            // At the geographic poles the 1/cos term is singular, so the eastward component
            // gets its own limiting expansion.
            polarEastComponent(
                sinGeocentricLat,
                relativeRadiusPower,
                cosMLambda,
                sinMLambda,
                dt,
            )
        }

        // Rotate from the geocentric frame back to the observer's local horizontal.
        val psi = geocentricLatRad - latRad
        return MagneticField(
            northNt = bx * cos(psi) - bz * sin(psi),
            eastNt = by,
            downNt = bx * sin(psi) + bz * cos(psi),
        )
    }

    private class Legendre(val p: DoubleArray, val dp: DoubleArray)

    /**
     * Schmidt semi-normalised associated Legendre functions and their derivatives.
     *
     * Computed with the Gauss-normalised recursion and rescaled afterwards, which is what the
     * reference implementation does: the direct Schmidt recursion overflows at high degree.
     */
    private fun legendreSchmidt(x: Double, cosPhi: Double): Legendre {
        val p = DoubleArray(ARRAY_SIZE)
        val dp = DoubleArray(ARRAY_SIZE)
        val norm = DoubleArray(ARRAY_SIZE)

        p[0] = 1.0
        dp[0] = 0.0
        val z = cosPhi

        for (n in 1..MAX_DEGREE) {
            for (m in 0..n) {
                val index = n * (n + 1) / 2 + m
                when {
                    n == m -> {
                        val index1 = (n - 1) * n / 2 + m - 1
                        p[index] = z * p[index1]
                        dp[index] = z * dp[index1] + x * p[index1]
                    }

                    n == 1 && m == 0 -> {
                        val index1 = (n - 1) * n / 2 + m
                        p[index] = x * p[index1]
                        dp[index] = x * dp[index1] - z * p[index1]
                    }

                    else -> {
                        val index1 = (n - 2) * (n - 1) / 2 + m
                        val index2 = (n - 1) * n / 2 + m
                        if (m > n - 2) {
                            p[index] = x * p[index2]
                            dp[index] = x * dp[index2] - z * p[index2]
                        } else {
                            val k = (((n - 1) * (n - 1)).toDouble() - (m * m).toDouble()) /
                                ((2 * n - 1).toDouble() * (2 * n - 3).toDouble())
                            p[index] = x * p[index2] - k * p[index1]
                            dp[index] = x * dp[index2] - z * p[index2] - k * dp[index1]
                        }
                    }
                }
            }
        }

        norm[0] = 1.0
        for (n in 1..MAX_DEGREE) {
            val index = n * (n + 1) / 2
            val index1 = (n - 1) * n / 2
            norm[index] = norm[index1] * (2 * n - 1).toDouble() / n.toDouble()
            for (m in 1..n) {
                val idx = n * (n + 1) / 2 + m
                val idx1 = n * (n + 1) / 2 + m - 1
                norm[idx] = norm[idx1] * sqrt(
                    ((n - m + 1).toDouble() * (if (m == 1) 2.0 else 1.0)) / (n + m).toDouble(),
                )
            }
        }

        for (n in 1..MAX_DEGREE) {
            for (m in 0..n) {
                val index = n * (n + 1) / 2 + m
                p[index] *= norm[index]
                // Sign flip: the recursion produces d/dx, the summation wants d/d(latitude).
                dp[index] *= -norm[index]
            }
        }

        return Legendre(p, dp)
    }

    private fun polarEastComponent(
        sinPhi: Double,
        relativeRadiusPower: DoubleArray,
        cosMLambda: DoubleArray,
        sinMLambda: DoubleArray,
        dt: Double,
    ): Double {
        val pcupS = DoubleArray(MAX_DEGREE + 1)
        pcupS[0] = 1.0
        var schmidt1 = 1.0
        var by = 0.0

        for (n in 1..MAX_DEGREE) {
            val index = n * (n + 1) / 2 + 1
            val schmidt2 = schmidt1 * (2 * n - 1).toDouble() / n.toDouble()
            val schmidt3 = schmidt2 * sqrt((n * 2).toDouble() / (n + 1).toDouble())
            schmidt1 = schmidt2
            pcupS[n] = if (n == 1) {
                pcupS[n - 1]
            } else {
                val k = (((n - 1) * (n - 1)).toDouble() - 1.0) /
                    ((2 * n - 1).toDouble() * (2 * n - 3).toDouble())
                sinPhi * pcupS[n - 1] - k * pcupS[n - 2]
            }
            val g = WmmCoefficients.G[index] + dt * WmmCoefficients.DG[index]
            val h = WmmCoefficients.H[index] + dt * WmmCoefficients.DH[index]
            by += relativeRadiusPower[n] *
                (g * sinMLambda[1] - h * cosMLambda[1]) * pcupS[n] * schmidt3
        }
        return by
    }
}

package com.developerstring.satride.core.orbit.sgp4

import com.developerstring.satride.core.orbit.Constants
import com.developerstring.satride.core.orbit.math.Vector3
import com.developerstring.satride.core.orbit.time.AstroTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Position and velocity in the True Equator Mean Equinox frame — what SGP4 natively produces.
 *
 * TEME is inertial and rotates with neither the Earth nor the stars, so it is not directly
 * useful to a user; [com.developerstring.satride.core.orbit.frames.Frames] converts it to
 * Earth-fixed and then to an observer's horizon.
 */
public data class TemeState(public val position: Vector3, public val velocity: Vector3, public val time: Instant)

/** Singly-averaged mean elements, in the propagator's internal units (earth radii, radians). */
public data class MeanElements(
    public val semiMajorAxisEr: Double,
    public val eccentricity: Double,
    public val inclinationRad: Double,
    public val raanRad: Double,
    public val argPerigeeRad: Double,
    public val meanAnomalyRad: Double,
    public val meanMotionRadPerMin: Double,
)

/** Which sub-model to run; chosen automatically from the orbital period. */
public enum class Sgp4Method {
    /** Near-Earth, period under 225 minutes. */
    NEAR_EARTH,

    /** Deep space (SDP4): lunar and solar perturbations plus resonance terms. */
    DEEP_SPACE,
}

/**
 * Two variants of the algorithm that differ in a handful of places.
 *
 * [IMPROVED] is the modern default and what operational software should use. [AFSPC] reproduces
 * the original Air Force Space Command behaviour bit for bit, which is what the published
 * verification output was generated with — so the golden-vector tests run in that mode.
 */
public enum class Sgp4OpsMode { IMPROVED, AFSPC }

/**
 * SGP4/SDP4 orbit propagator.
 *
 * Ported from the canonical Vallado reference implementation (public domain, AIAA 2006-6753).
 * The structure and variable names deliberately track the reference rather than being
 * idiomatic Kotlin: this code is verified line-by-line against published output, and a tidier
 * arrangement would make that impossible to review. Do not "clean it up" — the tests that
 * guard it check numbers, not style.
 *
 * One instance holds the initialised state for one element set and is safe to reuse across
 * many propagations. It is not thread-safe: the deep-space integrator carries state between
 * calls, so give each thread its own instance.
 */
public class Sgp4(public val elements: OrbitalElements, public val opsMode: Sgp4OpsMode = Sgp4OpsMode.IMPROVED) {
    public val method: Sgp4Method

    /**
     * Mean elements recovered from the last [propagate] call, after secular and resonance
     * effects but before the short-period corrections.
     *
     * The reference implementation exposes these too. They are the natural bisection point
     * when comparing against another implementation: if these agree and the final vector does
     * not, the fault is in the short-period or orientation stage rather than the model.
     */
    public var lastMeanElements: MeanElements? = null
        private set

    // --- Near-Earth secular and periodic coefficients ---
    private var isimp = 0
    private var aycof = 0.0
    private var con41 = 0.0
    private var cc1 = 0.0
    private var cc4 = 0.0
    private var cc5 = 0.0
    private var d2 = 0.0
    private var d3 = 0.0
    private var d4 = 0.0
    private var delmo = 0.0
    private var eta = 0.0
    private var argpdot = 0.0
    private var omgcof = 0.0
    private var sinmao = 0.0
    private var t2cof = 0.0
    private var t3cof = 0.0
    private var t4cof = 0.0
    private var t5cof = 0.0
    private var x1mth2 = 0.0
    private var x7thm1 = 0.0
    private var mdot = 0.0
    private var nodedot = 0.0
    private var xlcof = 0.0
    private var xmcof = 0.0
    private var nodecf = 0.0

    // --- Deep-space resonance flags and terms ---
    private var irez = 0
    private var d2201 = 0.0
    private var d2211 = 0.0
    private var d3210 = 0.0
    private var d3222 = 0.0
    private var d4410 = 0.0
    private var d4422 = 0.0
    private var d5220 = 0.0
    private var d5232 = 0.0
    private var d5421 = 0.0
    private var d5433 = 0.0
    private var dedt = 0.0
    private var del1 = 0.0
    private var del2 = 0.0
    private var del3 = 0.0
    private var didt = 0.0
    private var dmdt = 0.0
    private var dnodt = 0.0
    private var domdt = 0.0
    private var e3 = 0.0
    private var ee2 = 0.0
    private var peo = 0.0
    private var pgho = 0.0
    private var pho = 0.0
    private var pinco = 0.0
    private var plo = 0.0
    private var se2 = 0.0
    private var se3 = 0.0
    private var sgh2 = 0.0
    private var sgh3 = 0.0
    private var sgh4 = 0.0
    private var sh2 = 0.0
    private var sh3 = 0.0
    private var si2 = 0.0
    private var si3 = 0.0
    private var sl2 = 0.0
    private var sl3 = 0.0
    private var sl4 = 0.0
    private var gsto = 0.0
    private var xfact = 0.0
    private var xgh2 = 0.0
    private var xgh3 = 0.0
    private var xgh4 = 0.0
    private var xh2 = 0.0
    private var xh3 = 0.0
    private var xi2 = 0.0
    private var xi3 = 0.0
    private var xl2 = 0.0
    private var xl3 = 0.0
    private var xl4 = 0.0
    private var xlamo = 0.0
    private var zmol = 0.0
    private var zmos = 0.0
    private var atime = 0.0
    private var xli = 0.0
    private var xni = 0.0

    // --- Working copies of the mean elements ---
    private val bstar = elements.bstar
    private val ecco = elements.eccentricity
    private val argpo = elements.argPerigeeRad
    private val inclo = elements.inclinationRad
    private val mo = elements.meanAnomalyRad
    private val nodeo = elements.raanRad
    private var noUnkozai = elements.meanMotionRadPerMin

    private val radiusEarthKm = Constants.WGS72_RADIUS_KM
    private val xke = Constants.WGS72_XKE
    private val j2 = Constants.WGS72_J2
    private val j3oj2 = Constants.WGS72_J3OJ2
    private val j4 = Constants.WGS72_J4
    private val x2o3 = 2.0 / 3.0
    private val twoPi = Constants.TWO_PI

    init {
        method = initialise()
    }

    /** Propagates to [time]. */
    public fun propagate(time: Instant): TemeState {
        val minutes = (time - elements.epoch).inWholeMicroseconds / 60_000_000.0
        return propagate(minutes, time)
    }

    /** Propagates to [minutesSinceEpoch] past the element set's epoch. */
    public fun propagate(minutesSinceEpoch: Double): TemeState {
        val time = elements.epoch + (minutesSinceEpoch * 60.0).seconds
        return propagate(minutesSinceEpoch, time)
    }

    private fun propagate(tsince: Double, time: Instant): TemeState {
        val temp4 = 1.5e-12
        val vkmpersec = radiusEarthKm * xke / 60.0

        // --- Secular gravity and atmospheric drag ---
        val xmdf = mo + mdot * tsince
        val argpdf = argpo + argpdot * tsince
        val nodedf = nodeo + nodedot * tsince
        var argpm = argpdf
        var mm = xmdf
        val t2 = tsince * tsince
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * tsince
        var tempe = bstar * cc4 * tsince
        var templ = t2cof * t2

        if (isimp != 1) {
            val delomg = omgcof * tsince
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * tsince
            val t4 = t3 * tsince
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + tsince * t5cof)
        }

        var nm = noUnkozai
        var em = ecco
        var inclm = inclo

        if (method == Sgp4Method.DEEP_SPACE) {
            val ds = dspace(tsince, tsince, em, argpm, inclm, mm, nodem, nm)
            em = ds.em
            argpm = ds.argpm
            inclm = ds.inclm
            mm = ds.mm
            nodem = ds.nodem
            nm = ds.nm
        }

        if (nm <= 0.0) {
            throw PropagationException("Mean motion is not positive", 2, elements.noradId)
        }

        var am = (xke / nm).pow(x2o3) * tempa * tempa
        nm = xke / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) {
            throw PropagationException("Eccentricity $em out of range", 1, elements.noradId)
        }
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem

        nodem = nodem.fmod(twoPi)
        argpm = argpm.fmod(twoPi)
        xlm = xlm.fmod(twoPi)
        mm = (xlm - argpm - nodem).fmod(twoPi)

        val sinim = sin(inclm)
        val cosim = cos(inclm)

        lastMeanElements = MeanElements(
            semiMajorAxisEr = am,
            eccentricity = em,
            inclinationRad = inclm,
            raanRad = nodem,
            argPerigeeRad = argpm,
            meanAnomalyRad = mm,
            meanMotionRadPerMin = nm,
        )

        // --- Long-period periodics ---
        var ep = em
        var xincp = inclm
        var argpp = argpm
        var nodep = nodem
        var mp = mm
        var sinip = sinim
        var cosip = cosim
        var localAycof = aycof
        var localXlcof = xlcof
        var localCon41 = con41
        var localX1mth2 = x1mth2
        var localX7thm1 = x7thm1

        if (method == Sgp4Method.DEEP_SPACE) {
            val p = dpper(tsince, ep, xincp, nodep, argpp, mp, init = false)
            ep = p.ep
            xincp = p.inclp
            nodep = p.nodep
            argpp = p.argpp
            mp = p.mp
            if (xincp < 0.0) {
                xincp = -xincp
                nodep += PI
                argpp -= PI
            }
            if (ep < 0.0 || ep > 1.0) {
                throw PropagationException("Perturbed eccentricity $ep out of range", 3, elements.noradId)
            }
            sinip = sin(xincp)
            cosip = cos(xincp)
            localAycof = -0.5 * j3oj2 * sinip
            localXlcof = if (abs(cosip + 1.0) > 1.5e-12) {
                -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip)
            } else {
                -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / temp4
            }
        }

        val axnl = ep * cos(argpp)
        var temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * localAycof
        val xl = mp + argpp + nodep + temp * localXlcof * axnl

        // --- Kepler's equation, Newton-Raphson with the reference's damping ---
        val u = (xl - nodep).fmod(twoPi)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        // --- Short-period periodics ---
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) {
            throw PropagationException("Semi-latus rectum is negative", 4, elements.noradId)
        }

        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * j2 * temp
        val temp2 = temp1 * temp

        if (method == Sgp4Method.DEEP_SPACE) {
            val cosisq = cosip * cosip
            localCon41 = 3.0 * cosisq - 1.0
            localX1mth2 = 1.0 - cosisq
            localX7thm1 = 7.0 * cosisq - 1.0
        }

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * localCon41) + 0.5 * temp1 * localX1mth2 * cos2u
        su -= 0.25 * temp2 * localX7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * localX1mth2 * sin2u / xke
        val rvdot = rvdotl + nm * temp1 * (localX1mth2 * cos2u + 1.5 * localCon41) / xke

        // --- Orientation vectors ---
        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        val position = Vector3(mrt * ux, mrt * uy, mrt * uz) * radiusEarthKm
        val velocity = Vector3(
            mvt * ux + rvdot * vx,
            mvt * uy + rvdot * vy,
            mvt * uz + rvdot * vz,
        ) * vkmpersec

        if (mrt < 1.0) {
            throw PropagationException(
                "Satellite ${elements.noradId} has decayed (radius $mrt earth radii)",
                6,
                elements.noradId,
            )
        }

        return TemeState(position, velocity, time)
    }

    /** Deep-space coefficients, exposed for cross-implementation comparison in tests. */
    internal fun debugCoefficients(): Map<String, Double> = mapOf(
        "se2" to se2, "se3" to se3, "si2" to si2, "si3" to si3,
        "sl2" to sl2, "sl3" to sl3, "sl4" to sl4,
        "sgh2" to sgh2, "sgh3" to sgh3, "sgh4" to sgh4, "sh2" to sh2, "sh3" to sh3,
        "ee2" to ee2, "e3" to e3, "xi2" to xi2, "xi3" to xi3,
        "xl2" to xl2, "xl3" to xl3, "xl4" to xl4,
        "xgh2" to xgh2, "xgh3" to xgh3, "xgh4" to xgh4, "xh2" to xh2, "xh3" to xh3,
        "zmol" to zmol, "zmos" to zmos, "gsto" to gsto,
    )

    // ------------------------------------------------------------------------------------
    // Initialisation (sgp4init + initl in the reference)
    // ------------------------------------------------------------------------------------

    private fun initialise(): Sgp4Method {
        val ss = 78.0 / radiusEarthKm + 1.0
        val qzms2ttemp = (120.0 - 78.0) / radiusEarthKm
        val qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp

        // --- initl ---
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio

        // Recover the original (un-Kozai'd) mean motion: TLE mean motion is Kozai-mean, and
        // feeding it in directly biases the semi-major axis.
        val ak = (xke / elements.meanMotionRadPerMin).pow(x2o3)
        val d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = elements.meanMotionRadPerMin / (1.0 + del)

        val ao = (xke / noUnkozai).pow(x2o3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        val epochDays = julianEpoch() - 2433281.5
        gsto = when (opsMode) {
            Sgp4OpsMode.AFSPC -> afspcGst(epochDays)
            Sgp4OpsMode.IMPROVED -> AstroTime.gmstRadians(epochDays + 2433281.5)
        }

        if (omeosq < 0.0 && noUnkozai < 0.0) return Sgp4Method.NEAR_EARTH

        isimp = if (rp < 220.0 / radiusEarthKm + 1.0) 1 else 0
        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * radiusEarthKm

        // Drag model degrades for very low perigees; the reference clamps rather than diverges.
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            val qzms24temp = (120.0 - sfour) / radiusEarthKm
            qzms24 = qzms24temp.pow(4.0)
            sfour = sfour / radiusEarthKm + 1.0
        }
        val pinvsq = 1.0 / posq

        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4.0)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * noUnkozai * (
            ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq))
            )
        cc1 = bstar * cc2
        var cc3 = 0.0
        if (ecco > 1.0e-4) {
            cc3 = -2.0 * coef * tsi * j3oj2 * noUnkozai * sinio / ecco
        }
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (
            eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                j2 * tsi / (ao * psisq) * (
                    -3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                        0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)
                    )
            )
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * j2 * pinvsq * noUnkozai
        val temp2 = 0.5 * temp1 * j2 * pinvsq
        val temp3 = -0.46875 * j4 * pinvsq * pinvsq * noUnkozai
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 +
            0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 +
            0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
            temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (
            0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)
            ) * cosio
        val xpidot = argpdot + nodedot
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = 0.0
        if (ecco > 1.0e-4) xmcof = -x2o3 * coef * bstar / eeta
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        // Guard against the divide-by-zero at exactly 180 degrees inclination.
        xlcof = if (abs(cosio + 1.0) > 1.5e-12) {
            -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        } else {
            -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12
        }
        aycof = -0.5 * j3oj2 * sinio
        val delmotemp = 1.0 + eta * cos(mo)
        delmo = delmotemp * delmotemp * delmotemp
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0

        var resolvedMethod = Sgp4Method.NEAR_EARTH

        // Deep space when the period is 225 minutes or more.
        if (twoPi / noUnkozai >= 225.0) {
            resolvedMethod = Sgp4Method.DEEP_SPACE
            isimp = 1
            val tc = 0.0
            val inclm = inclo

            val ds = dscom(epochDays, ecco, argpo, tc, inclo, nodeo, noUnkozai)

            // The reference calls dpper with init='y' at this point. In that mode it computes
            // the periodic terms but applies nothing, because dscom has just zeroed the
            // peo/pinco/plo/pgho/pho offsets it would subtract. It is therefore a no-op here
            // and is deliberately omitted rather than called for its (absent) side effects.

            dsinit(
                ds = ds,
                tc = tc,
                xpidot = xpidot,
                eccsq = eccsq,
                inclm = inclm,
            )
        }

        if (isimp != 1) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (
                3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 +
                    15.0 * cc1sq * (2.0 * d2 + cc1sq)
                )
        }

        return resolvedMethod
    }

    private fun julianEpoch(): Double = AstroTime.julianDateOfYearStart(
        if (elements.epochYear < 57) 2000 + elements.epochYear else 1900 + elements.epochYear,
    ) + elements.epochDayOfYear - 1.0

    /** The pre-2000 Greenwich sidereal time formula, kept for bit-exact AFSPC reproduction. */
    private fun afspcGst(epochDays: Double): Double {
        val ts70 = epochDays - 7305.0
        val ds70 = floor(ts70 + 1.0e-8)
        val tfrac = ts70 - ds70
        val c1 = 1.72027916940703639e-2
        val thgr70 = 1.7321343856509374
        val fk5r = 5.07551419432269442e-15
        val c1p2p = c1 + twoPi
        var gsto = (thgr70 + c1 * ds70 + c1p2p * tfrac + ts70 * ts70 * fk5r).fmod(twoPi)
        if (gsto < 0.0) gsto += twoPi
        return gsto
    }

    // ------------------------------------------------------------------------------------
    // Deep space (SDP4)
    // ------------------------------------------------------------------------------------

    /** Intermediate lunar/solar terms shared between dscom, dsinit and dpper. */
    private class DsCom {
        var snodm = 0.0
        var cnodm = 0.0
        var sinim = 0.0
        var cosim = 0.0
        var sinomm = 0.0
        var cosomm = 0.0
        var day = 0.0
        var emsq = 0.0
        var gam = 0.0
        var rtemsq = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var s5 = 0.0
        var s6 = 0.0
        var s7 = 0.0
        var ss1 = 0.0
        var ss2 = 0.0
        var ss3 = 0.0
        var ss4 = 0.0
        var ss5 = 0.0
        var ss6 = 0.0
        var ss7 = 0.0
        var sz1 = 0.0
        var sz2 = 0.0
        var sz3 = 0.0
        var sz11 = 0.0
        var sz12 = 0.0
        var sz13 = 0.0
        var sz21 = 0.0
        var sz22 = 0.0
        var sz23 = 0.0
        var sz31 = 0.0
        var sz32 = 0.0
        var sz33 = 0.0
        var z1 = 0.0
        var z2 = 0.0
        var z3 = 0.0
        var z11 = 0.0
        var z12 = 0.0
        var z13 = 0.0
        var z21 = 0.0
        var z22 = 0.0
        var z23 = 0.0
        var z31 = 0.0
        var z32 = 0.0
        var z33 = 0.0
        var nm = 0.0
        var em = 0.0
    }

    private fun dscom(
        epoch: Double,
        ep: Double,
        argpp: Double,
        tc: Double,
        inclp: Double,
        nodep: Double,
        np: Double,
    ): DsCom {
        val zes = 0.01675
        val zel = 0.05490
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458

        val r = DsCom()
        r.nm = np
        r.em = ep
        r.snodm = sin(nodep)
        r.cnodm = cos(nodep)
        r.sinomm = sin(argpp)
        r.cosomm = cos(argpp)
        r.sinim = sin(inclp)
        r.cosim = cos(inclp)
        r.emsq = r.em * r.em
        val betasq = 1.0 - r.emsq
        r.rtemsq = sqrt(betasq)

        peo = 0.0
        pinco = 0.0
        plo = 0.0
        pgho = 0.0
        pho = 0.0

        r.day = epoch + 18261.5 + tc / 1440.0
        val xnodce = (4.5236020 - 9.2422029e-4 * r.day).fmod(twoPi)
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        r.gam = 5.8351514 + 0.0019443680 * r.day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx = r.gam + zx - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)

        var zcosg = zcosgs
        var zsing = zsings
        var zcosi = zcosis
        var zsini = zsinis
        var zcosh = r.cnodm
        var zsinh = r.snodm
        var cc = c1ss
        val xnoi = 1.0 / r.nm

        for (lsflg in 1..2) {
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = r.cosim * a7 + r.sinim * a8
            val a4 = r.cosim * a9 + r.sinim * a10
            val a5 = -r.sinim * a7 + r.cosim * a8
            val a6 = -r.sinim * a9 + r.cosim * a10

            val x1 = a1 * r.cosomm + a2 * r.sinomm
            val x2 = a3 * r.cosomm + a4 * r.sinomm
            val x3 = -a1 * r.sinomm + a2 * r.cosomm
            val x4 = -a3 * r.sinomm + a4 * r.cosomm
            val x5 = a5 * r.sinomm
            val x6 = a6 * r.sinomm
            val x7 = a5 * r.cosomm
            val x8 = a6 * r.cosomm

            r.z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            r.z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            r.z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4
            r.z1 = 3.0 * (a1 * a1 + a2 * a2) + r.z31 * r.emsq
            r.z2 = 6.0 * (a1 * a3 + a2 * a4) + r.z32 * r.emsq
            r.z3 = 3.0 * (a3 * a3 + a4 * a4) + r.z33 * r.emsq
            r.z11 = -6.0 * a1 * a5 + r.emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            r.z12 = -6.0 * (a1 * a6 + a3 * a5) +
                r.emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5))
            r.z13 = -6.0 * a3 * a6 + r.emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)
            r.z21 = 6.0 * a2 * a5 + r.emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            r.z22 = 6.0 * (a4 * a5 + a2 * a6) +
                r.emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            r.z23 = 6.0 * a4 * a6 + r.emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)
            r.z1 = r.z1 + r.z1 + betasq * r.z31
            r.z2 = r.z2 + r.z2 + betasq * r.z32
            r.z3 = r.z3 + r.z3 + betasq * r.z33
            r.s3 = cc * xnoi
            r.s2 = -0.5 * r.s3 / r.rtemsq
            r.s4 = r.s3 * r.rtemsq
            r.s1 = -15.0 * r.em * r.s4
            r.s5 = x1 * x3 + x2 * x4
            r.s6 = x2 * x3 + x1 * x4
            r.s7 = x2 * x4 - x1 * x3

            if (lsflg == 1) {
                r.ss1 = r.s1
                r.ss2 = r.s2
                r.ss3 = r.s3
                r.ss4 = r.s4
                r.ss5 = r.s5
                r.ss6 = r.s6
                r.ss7 = r.s7
                r.sz1 = r.z1
                r.sz2 = r.z2
                r.sz3 = r.z3
                r.sz11 = r.z11
                r.sz12 = r.z12
                r.sz13 = r.z13
                r.sz21 = r.z21
                r.sz22 = r.z22
                r.sz23 = r.z23
                r.sz31 = r.z31
                r.sz32 = r.z32
                r.sz33 = r.z33
                zcosg = zcosgl
                zsing = zsingl
                zcosi = zcosil
                zsini = zsinil
                zcosh = zcoshl * r.cnodm + zsinhl * r.snodm
                zsinh = r.snodm * zcoshl - r.cnodm * zsinhl
                cc = c1l
            }
        }

        zmol = (4.7199672 + 0.22997150 * r.day - r.gam).fmod(twoPi)
        zmos = (6.2565837 + 0.017201977 * r.day).fmod(twoPi)

        // Solar terms
        se2 = 2.0 * r.ss1 * r.ss6
        se3 = 2.0 * r.ss1 * r.ss7
        si2 = 2.0 * r.ss2 * r.sz12
        si3 = 2.0 * r.ss2 * (r.sz13 - r.sz11)
        sl2 = -2.0 * r.ss3 * r.sz2
        sl3 = -2.0 * r.ss3 * (r.sz3 - r.sz1)
        sl4 = -2.0 * r.ss3 * (-21.0 - 9.0 * r.emsq) * zes
        sgh2 = 2.0 * r.ss4 * r.sz32
        sgh3 = 2.0 * r.ss4 * (r.sz33 - r.sz31)
        sgh4 = -18.0 * r.ss4 * zes
        sh2 = -2.0 * r.ss2 * r.sz22
        sh3 = -2.0 * r.ss2 * (r.sz23 - r.sz21)

        // Lunar terms
        ee2 = 2.0 * r.s1 * r.s6
        e3 = 2.0 * r.s1 * r.s7
        xi2 = 2.0 * r.s2 * r.z12
        xi3 = 2.0 * r.s2 * (r.z13 - r.z11)
        xl2 = -2.0 * r.s3 * r.z2
        xl3 = -2.0 * r.s3 * (r.z3 - r.z1)
        xl4 = -2.0 * r.s3 * (-21.0 - 9.0 * r.emsq) * zel
        xgh2 = 2.0 * r.s4 * r.z32
        xgh3 = 2.0 * r.s4 * (r.z33 - r.z31)
        xgh4 = -18.0 * r.s4 * zel
        xh2 = -2.0 * r.s2 * r.z22
        xh3 = -2.0 * r.s2 * (r.z23 - r.z21)

        return r
    }

    private class DpperResult(val ep: Double, val inclp: Double, val nodep: Double, val argpp: Double, val mp: Double)

    private fun dpper(
        t: Double,
        epIn: Double,
        inclpIn: Double,
        nodepIn: Double,
        argppIn: Double,
        mpIn: Double,
        init: Boolean,
    ): DpperResult {
        val zns = 1.19459e-5
        val zes = 0.01675
        val znl = 1.5835218e-4
        val zel = 0.05490

        var ep = epIn
        var inclp = inclpIn
        var nodep = nodepIn
        var argpp = argppIn
        var mp = mpIn

        var zm = if (init) zmos else zmos + zns * t
        var zf = zm + 2.0 * zes * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)
        val ses = se2 * f2 + se3 * f3
        val sis = si2 * f2 + si3 * f3
        val sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf
        val sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf
        val shs = sh2 * f2 + sh3 * f3

        zm = if (init) zmol else zmol + znl * t
        zf = zm + 2.0 * zel * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)
        val sel = ee2 * f2 + e3 * f3
        val sil = xi2 * f2 + xi3 * f3
        val sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf
        val sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf
        val shll = xh2 * f2 + xh3 * f3

        var pe = ses + sel
        var pinc = sis + sil
        var pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll

        if (!init) {
            pe -= peo
            pinc -= pinco
            pl -= plo
            pgh -= pgho
            ph -= pho
            inclp += pinc
            ep += pe
            val sinip = sin(inclp)
            val cosip = cos(inclp)

            // Above 0.2 rad the direct formulation is stable; below it the reference switches
            // to a Lyddane-style form to avoid the small-inclination singularity.
            if (inclp >= 0.2) {
                ph /= sinip
                pgh -= cosip * ph
                argpp += pgh
                nodep += ph
                mp += pl
            } else {
                val sinop = sin(nodep)
                val cosop = cos(nodep)
                var alfdp = sinip * sinop
                var betdp = sinip * cosop
                val dalf = ph * cosop + pinc * cosip * sinop
                val dbet = -ph * sinop + pinc * cosip * cosop
                alfdp += dalf
                betdp += dbet
                nodep = nodep.fmod(twoPi)
                if (nodep < 0.0 && opsMode == Sgp4OpsMode.AFSPC) nodep += twoPi
                var xls = mp + argpp + cosip * nodep
                val dls = pl + pgh - pinc * nodep * sinip
                xls += dls
                val xnoh = nodep
                nodep = atan2(alfdp, betdp)
                if (nodep < 0.0 && opsMode == Sgp4OpsMode.AFSPC) nodep += twoPi
                if (abs(xnoh - nodep) > PI) {
                    nodep = if (nodep < xnoh) nodep + twoPi else nodep - twoPi
                }
                mp += pl
                argpp = xls - mp - cosip * nodep
            }
        }

        return DpperResult(ep, inclp, nodep, argpp, mp)
    }

    private fun dsinit(ds: DsCom, tc: Double, xpidot: Double, eccsq: Double, inclm: Double) {
        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val rptim = 4.37526908801129966e-3
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7
        val znl = 1.5835218e-4
        val zns = 1.19459e-5

        var em = ds.em
        var nm = ds.nm
        var emsq = ds.emsq
        val localInclm = inclm

        irez = 0
        if (nm < 0.0052359877 && nm > 0.0034906585) irez = 1
        if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) irez = 2

        // --- Solar terms ---
        val ses = ds.ss1 * zns * ds.ss5
        val sis = ds.ss2 * zns * (ds.sz11 + ds.sz13)
        val sls = -zns * ds.ss3 * (ds.sz1 + ds.sz3 - 14.0 - 6.0 * emsq)
        val sghs = ds.ss4 * zns * (ds.sz31 + ds.sz33 - 6.0)
        var shs = -zns * ds.ss2 * (ds.sz21 + ds.sz23)
        if (localInclm < 5.2359877e-2 || localInclm > PI - 5.2359877e-2) shs = 0.0
        if (ds.sinim != 0.0) shs /= ds.sinim
        val sgs = sghs - ds.cosim * shs

        // --- Lunar terms ---
        dedt = ses + ds.s1 * znl * ds.s5
        didt = sis + ds.s2 * znl * (ds.z11 + ds.z13)
        dmdt = sls - znl * ds.s3 * (ds.z1 + ds.z3 - 14.0 - 6.0 * emsq)
        val sghl = ds.s4 * znl * (ds.z31 + ds.z33 - 6.0)
        var shll = -znl * ds.s2 * (ds.z21 + ds.z23)
        if (localInclm < 5.2359877e-2 || localInclm > PI - 5.2359877e-2) shll = 0.0
        domdt = sgs + sghl
        dnodt = shs
        if (ds.sinim != 0.0) {
            domdt -= ds.cosim / ds.sinim * shll
            dnodt += shll / ds.sinim
        }

        val theta = (gsto + tc * rptim).fmod(twoPi)
        // The reference advances the mean elements by dedt*t etc. here; dsinit is only ever
        // called at initialisation where t is zero, so those terms vanish.

        if (irez != 0) {
            val aonv = (nm / xke).pow(x2o3)

            // --- 12-hour geopotential resonance (Molniya-class orbits) ---
            if (irez == 2) {
                val cosisq = ds.cosim * ds.cosim
                val emo = em
                em = ecco
                val emsqo = emsq
                emsq = eccsq
                val eoc = em * emsq
                val g201 = -0.306 - (em - 0.64) * 0.440
                val g211: Double
                val g310: Double
                val g322: Double
                val g410: Double
                val g422: Double
                val g520: Double
                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.11 * em - 24462.77 * emsq + 12422.52 * eoc
                    g520 = if (em > 0.715) {
                        -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
                    } else {
                        1464.74 - 4664.75 * em + 3763.64 * emsq
                    }
                }
                val g533: Double
                val g521: Double
                val g532: Double
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc
                }

                val sini2 = ds.sinim * ds.sinim
                val f220 = 0.75 * (1.0 + 2.0 * ds.cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * ds.sinim * (1.0 - 2.0 * ds.cosim - 3.0 * cosisq)
                val f322 = -1.875 * ds.sinim * (1.0 + 2.0 * ds.cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * ds.sinim * (
                    sini2 * (1.0 - 2.0 * ds.cosim - 5.0 * cosisq) +
                        0.33333333 * (-2.0 + 4.0 * ds.cosim + 6.0 * cosisq)
                    )
                val f523 = ds.sinim * (
                    4.92187512 * sini2 * (-2.0 - 4.0 * ds.cosim + 10.0 * cosisq) +
                        6.56250012 * (1.0 + 2.0 * ds.cosim - 3.0 * cosisq)
                    )
                val f542 = 29.53125 * ds.sinim * (
                    2.0 - 8.0 * ds.cosim + cosisq * (-12.0 + 8.0 * ds.cosim + 10.0 * cosisq)
                    )
                val f543 = 29.53125 * ds.sinim * (
                    -2.0 - 8.0 * ds.cosim + cosisq * (12.0 + 8.0 * ds.cosim - 10.0 * cosisq)
                    )
                val xno2 = nm * nm
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * root32
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 *= aonv
                temp = 2.0 * temp1 * root44
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * root52
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = (mo + nodeo + nodeo - theta - theta).fmod(twoPi)
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - noUnkozai
                em = emo
                emsq = emsqo
            }

            // --- 24-hour synchronous resonance (geostationary-class orbits) ---
            if (irez == 1) {
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + ds.cosim) * (1.0 + ds.cosim)
                val f311 = 0.9375 * ds.sinim * ds.sinim * (1.0 + 3.0 * ds.cosim) -
                    0.75 * (1.0 + ds.cosim)
                var f330 = 1.0 + ds.cosim
                f330 = 1.875 * f330 * f330 * f330
                del1 = 3.0 * nm * nm * aonv * aonv
                del2 = 2.0 * del1 * f220 * g200 * q22
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
                del1 = del1 * f311 * g310 * q31 * aonv
                xlamo = (mo + nodeo + argpo - theta).fmod(twoPi)
                xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - noUnkozai
            }

            xli = xlamo
            xni = noUnkozai
            atime = 0.0
            nm = noUnkozai
        }
    }

    private class DsSpaceResult(
        val em: Double,
        val argpm: Double,
        val inclm: Double,
        val mm: Double,
        val nodem: Double,
        val nm: Double,
    )

    private fun dspace(
        t: Double,
        tc: Double,
        emIn: Double,
        argpmIn: Double,
        inclmIn: Double,
        mmIn: Double,
        nodemIn: Double,
        @Suppress("UNUSED_PARAMETER") nmIn: Double,
    ): DsSpaceResult {
        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.0508330
        val g54 = 4.4108898
        val rptim = 4.37526908801129966e-3
        val stepp = 720.0
        val stepn = -720.0
        val step2 = 259200.0

        var em = emIn
        var argpm = argpmIn
        var inclm = inclmIn
        var mm = mmIn
        var nodem = nodemIn
        var dndt = 0.0
        var nm: Double

        val theta = (gsto + tc * rptim).fmod(twoPi)
        em += dedt * t
        inclm += didt * t
        argpm += domdt * t
        nodem += dnodt * t
        mm += dmdt * t

        // Resonance effects of Earth gravity, integrated with a fixed 720-minute step. The
        // reference uses this specific step size and the results are tuned to it.
        var ft = 0.0
        var xndt = 0.0
        var xnddt = 0.0
        var xldot = 0.0

        if (irez != 0) {
            if (atime == 0.0 || t * atime <= 0.0 || abs(t) < abs(atime)) {
                atime = 0.0
                xni = noUnkozai
                xli = xlamo
            }
            val delt = if (t > 0.0) stepp else stepn

            var iretn = 381
            while (iretn == 381) {
                if (irez != 2) {
                    xndt = del1 * sin(xli - fasx2) +
                        del2 * sin(2.0 * (xli - fasx4)) +
                        del3 * sin(3.0 * (xli - fasx6))
                    xldot = xni + xfact
                    xnddt = del1 * cos(xli - fasx2) +
                        2.0 * del2 * cos(2.0 * (xli - fasx4)) +
                        3.0 * del3 * cos(3.0 * (xli - fasx6))
                    xnddt *= xldot
                } else {
                    val xomi = argpo + argpdot * atime
                    val x2omi = xomi + xomi
                    val x2li = xli + xli
                    xndt = d2201 * sin(x2omi + xli - g22) +
                        d2211 * sin(xli - g22) +
                        d3210 * sin(xomi + xli - g32) +
                        d3222 * sin(-xomi + xli - g32) +
                        d4410 * sin(x2omi + x2li - g44) +
                        d4422 * sin(x2li - g44) +
                        d5220 * sin(xomi + xli - g52) +
                        d5232 * sin(-xomi + xli - g52) +
                        d5421 * sin(xomi + x2li - g54) +
                        d5433 * sin(-xomi + x2li - g54)
                    xldot = xni + xfact
                    xnddt = d2201 * cos(x2omi + xli - g22) +
                        d2211 * cos(xli - g22) +
                        d3210 * cos(xomi + xli - g32) +
                        d3222 * cos(-xomi + xli - g32) +
                        d5220 * cos(xomi + xli - g52) +
                        d5232 * cos(-xomi + xli - g52) +
                        2.0 * (
                            d4410 * cos(x2omi + x2li - g44) +
                                d4422 * cos(x2li - g44) +
                                d5421 * cos(xomi + x2li - g54) +
                                d5433 * cos(-xomi + x2li - g54)
                            )
                    xnddt *= xldot
                }

                if (abs(t - atime) >= stepp) {
                    iretn = 381
                } else {
                    ft = t - atime
                    iretn = 0
                }

                if (iretn == 381) {
                    xli += xldot * delt + xndt * step2
                    xni += xndt * delt + xnddt * step2
                    atime += delt
                }
            }

            nm = xni + xndt * ft + xnddt * ft * ft * 0.5
            val xl = xli + xldot * ft + xndt * ft * ft * 0.5
            if (irez != 1) {
                mm = xl - 2.0 * nodem + 2.0 * theta
                dndt = nm - noUnkozai
            } else {
                mm = xl - nodem - argpm + theta
                dndt = nm - noUnkozai
            }
            nm = noUnkozai + dndt
        } else {
            nm = noUnkozai
        }

        return DsSpaceResult(em, argpm, inclm, mm, nodem, nm)
    }
}

/**
 * Floating-point remainder with the sign of the dividend, matching C's `fmod`.
 *
 * This is not the same as Kotlin's `mod`, which always returns a non-negative result. The
 * difference is load-bearing: `dpper` tests `nodep < 0` and corrects it explicitly, so a
 * helper that never returns a negative value would silently disable that branch.
 */
private fun Double.fmod(m: Double): Double = this % m

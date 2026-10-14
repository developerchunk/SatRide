package com.developerstring.satride.feature.play.multiplay.solar

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.developerstring.satride.core.orbit.time.AstroTime
import com.developerstring.satride.feature.play.GlobeAssets
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.render.CameraPose
import com.developerstring.satride.feature.play.render.GlobeRenderer
import com.developerstring.satride.feature.play.render.SatelliteModels
import com.developerstring.satride.feature.play.render.SphereMesh
import com.developerstring.satride.feature.play.solar.Body
import com.developerstring.satride.feature.play.solar.BodyKind
import com.developerstring.satride.feature.play.solar.Ephemeris
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.Instant

/**
 * One frame of the Solar System arena.
 *
 * The game world is heliocentric and equatorial; the globe renderer's world is Earth-fixed. The
 * bridge is one rotation: every point is re-expressed relative to the Earth and turned by GMST
 * before it goes through [GlobeRenderer.projectWorld]. That buys the real vector Earth — with
 * its continents, city lights and atmosphere — for free whenever the ship is near home, and
 * leaves this class to draw everything the globe renderer has never heard of: the Sun, the other
 * planets as lit procedural spheres, Saturn's rings, orbit lines, the asteroid belt, and the
 * starships.
 */
class SolarRenderer(private val globe: GlobeRenderer) {

    /** Pre-measured names: pilots by uid, bodies by [Body.id]. */
    var labels: Map<String, TextLayoutResult> = emptyMap()
    var emoteLabels: Map<String, TextLayoutResult> = emptyMap()

    /** "MARS · 1.2 AU · 34 s", refreshed at HUD rate and drawn under the target reticle. */
    var targetLabel: TextLayoutResult? = null

    private val poseEcef = CameraPose()
    private val out = FloatArray(3)
    private val tmp = DoubleArray(3)
    private val tmp2 = DoubleArray(3)
    private val e1 = DoubleArray(3)
    private val e2 = DoubleArray(3)
    private val pole = DoubleArray(3)

    private var cosG = 1.0
    private var sinG = 0.0
    private var earthX = 0.0
    private var earthY = 0.0
    private var earthZ = 0.0
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var focal = 1.0
    private var width = 0f
    private var height = 0f
    private var density = 1f
    private var realEarth = false

    // The planet mesh: a unit sphere with per-vertex latitude/longitude precomputed.
    private val mesh = SphereMesh(rings = 30, segments = 44, radiusKm = 1.0)
    private val vLat = DoubleArray(mesh.vertexCount)
    private val vLon = DoubleArray(mesh.vertexCount)
    private val vOk = BooleanArray(mesh.vertexCount)
    private val positions = ArrayList<Offset>(mesh.vertexCount).apply { repeat(mesh.vertexCount) { add(Offset.Zero) } }
    private val colors = ArrayList<Color>(mesh.vertexCount).apply { repeat(mesh.vertexCount) { add(Color.White) } }
    private val uvZero = ArrayList<Offset>(mesh.vertexCount).apply { repeat(mesh.vertexCount) { add(Offset.Zero) } }
    private val indices = ArrayList<Int>(mesh.faceCount * 3)
    private val plainPaint = Paint().apply { color = Color.White }

    // Saturn's rings as quad strips.
    private val ringVertexCount = PlanetSurfaces.saturnRings.size * (RING_SEGMENTS + 1) * 2
    private val ringPositions = ArrayList<Offset>(ringVertexCount).apply {
        repeat(ringVertexCount) { add(Offset.Zero) }
    }
    private val ringColors = ArrayList<Color>(ringVertexCount).apply { repeat(ringVertexCount) { add(Color.White) } }
    private val ringUv = ArrayList<Offset>(ringVertexCount).apply { repeat(ringVertexCount) { add(Offset.Zero) } }
    private val ringOk = BooleanArray(ringVertexCount)
    private val ringDepth = DoubleArray(ringVertexCount)
    private val ringBack = ArrayList<Int>(ringVertexCount * 3)
    private val ringFront = ArrayList<Int>(ringVertexCount * 3)

    // Orbit lines, resampled every couple of seconds.
    private val orbitPts = Array(Body.entries.size) { DoubleArray(ORBIT_SAMPLES * 3) }
    private var orbitJd = 0.0
    private val orbitPath = Path()

    // The asteroid belt: a seeded scatter between Mars and Jupiter.
    private val belt = DoubleArray(BELT_COUNT * 3)

    private val bodyOrder = IntArray(Body.entries.size)
    private val bodyDepth = DoubleArray(Body.entries.size)
    private val guidePath = Path()

    init {
        for (i in 0 until mesh.vertexCount) {
            vLat[i] = asin(mesh.nz[i].coerceIn(-1.0, 1.0)) * 180.0 / PI
            vLon[i] = atan2(mesh.ny[i], mesh.nx[i]) * 180.0 / PI
        }
        var state = 0xA57E201DL
        fun next(): Double {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        val cosEps = cos(Ephemeris.OBLIQUITY_DEG * PI / 180.0)
        val sinEps = sin(Ephemeris.OBLIQUITY_DEG * PI / 180.0)
        for (i in 0 until BELT_COUNT) {
            val r = (2.15 + 1.15 * next()) * SolarSession.AU_KM
            val a = next() * 2.0 * PI
            val z = (next() + next() - 1.0) * 0.09 * SolarSession.AU_KM
            val x = r * cos(a)
            val y = r * sin(a)
            belt[i * 3] = x
            belt[i * 3 + 1] = cosEps * y - sinEps * z
            belt[i * 3 + 2] = sinEps * y + cosEps * z
        }
    }

    @Suppress("LongParameterList")
    fun render(
        scope: DrawScope,
        session: SolarSession,
        pose: CameraPose,
        assets: GlobeAssets,
        simTime: Instant,
        frameNanos: Long,
        nowMs: Long,
        localColor: Color,
    ) {
        width = scope.size.width
        height = scope.size.height
        if (width <= 0f || height <= 0f) return
        density = scope.density
        val timeSec = frameNanos / 1e9
        val world = session.world

        val gmst = AstroTime.gmstRadians(simTime)
        cosG = cos(gmst)
        sinG = sin(gmst)
        earthX = world.x(Body.EARTH)
        earthY = world.y(Body.EARTH)
        earthZ = world.z(Body.EARTH)
        camX = pose.x
        camY = pose.y
        camZ = pose.z
        focal = (height / 2.0) / tan(pose.fovDeg * PI / 180.0 / 2.0)

        // The camera, re-expressed for the globe renderer.
        toEcef(pose.x, pose.y, pose.z, tmp)
        toEcef(pose.targetX, pose.targetY, pose.targetZ, tmp2)
        rotEcef(pose.upX, pose.upY, pose.upZ, e1)
        poseEcef.set(tmp[0], tmp[1], tmp[2], tmp2[0], tmp2[1], tmp2[2], e1[0], e1[1], e1[2])
        poseEcef.fovDeg = pose.fovDeg

        // The real Earth is worth drawing once it is more than a couple of pixels across.
        val earthDist = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2])
        realEarth = focal * Body.EARTH.radiusKm / earthDist.coerceAtLeast(1.0) >= 1.6

        globe.vectorEarth = assets.vectorEarth
        globe.cityLights = assets.cityLights
        globe.setTextures(assets.dayTexture, assets.nightTexture)
        globe.beginWorld(scope, simTime, poseEcef, frameNanos, earth = realEarth, sky = false)
        focal = globe.focalPx

        refreshOrbits(world.jd)
        drawOrbits(scope, session)
        drawBelt(scope)
        drawBodies(scope, session, timeSec)
        drawOtherShips(scope, session, nowMs)
        drawLocalShip(scope, session, localColor, timeSec)
        drawTarget(scope, session, timeSec)
        drawWarpStreaks(scope, session, timeSec)
        drawHeat(scope, session, timeSec)
    }

    // region Frame transforms

    private fun toEcef(x: Double, y: Double, z: Double, out: DoubleArray) {
        val rx = x - earthX
        val ry = y - earthY
        out[0] = cosG * rx + sinG * ry
        out[1] = -sinG * rx + cosG * ry
        out[2] = z - earthZ
    }

    private fun rotEcef(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = cosG * x + sinG * y
        out[1] = -sinG * x + cosG * y
        out[2] = z
    }

    /** Projects a heliocentric point; false when behind the camera or (optionally) behind the Earth. */
    private fun project(x: Double, y: Double, z: Double, earthOcclusion: Boolean): Boolean {
        val rx = x - earthX
        val ry = y - earthY
        val ex = cosG * rx + sinG * ry
        val ey = -sinG * rx + cosG * ry
        return globe.projectWorld(ex, ey, z - earthZ, out, checkOcclusion = earthOcclusion && realEarth)
    }

    /** Whether the segment from the camera to the point passes through any body but [skip]. */
    private fun hiddenByBody(session: SolarSession, x: Double, y: Double, z: Double, skip: Body?): Boolean {
        val world = session.world
        val dx = x - camX
        val dy = y - camY
        val dz = z - camZ
        val a = dx * dx + dy * dy + dz * dz
        if (a < 1e-9) return false
        for (body in world.bodies) {
            if (body == skip) continue
            if (body == Body.EARTH && realEarth) continue // the globe renderer's own test covers it
            val bx = world.x(body)
            val by = world.y(body)
            val bz = world.z(body)
            val t = ((bx - camX) * dx + (by - camY) * dy + (bz - camZ) * dz) / a
            if (t <= 0.0 || t >= 1.0) continue
            val px = camX + dx * t - bx
            val py = camY + dy * t - by
            val pz = camZ + dz * t - bz
            val r = body.radiusKm * 0.995
            if (px * px + py * py + pz * pz < r * r) return true
        }
        return false
    }

    // endregion

    // region Scenery

    private fun refreshOrbits(jd: Double) {
        if (abs(jd - orbitJd) < 2.0 / 86_400.0) return
        orbitJd = jd
        for (body in Body.entries) {
            if (body == Body.SUN) continue
            val pts = orbitPts[body.ordinal]
            for (k in 0 until ORBIT_SAMPLES) {
                Ephemeris.orbitPoint(body, jd, 2.0 * PI * k / ORBIT_SAMPLES, tmp)
                pts[k * 3] = tmp[0]
                pts[k * 3 + 1] = tmp[1]
                pts[k * 3 + 2] = tmp[2]
            }
        }
    }

    /** Every world's path this year as a faint line: the map that turns dots into a solar system. */
    private fun drawOrbits(scope: DrawScope, session: SolarSession) {
        for (body in Body.entries) {
            if (body == Body.SUN) continue
            val pts = orbitPts[body.ordinal]
            val color = Color(body.colorArgb).copy(alpha = if (body == Body.MOON) 0.16f else 0.24f)
            orbitPath.reset()
            var penDown = false
            var any = false
            for (k in 0..ORBIT_SAMPLES) {
                val i = (k % ORBIT_SAMPLES) * 3
                val x = pts[i]
                val y = pts[i + 1]
                val z = pts[i + 2]
                if (project(x, y, z, earthOcclusion = true) && !hiddenByBody(session, x, y, z, null)) {
                    if (penDown) orbitPath.lineTo(out[0], out[1]) else orbitPath.moveTo(out[0], out[1])
                    penDown = true
                    any = true
                } else {
                    penDown = false
                }
            }
            if (any) scope.drawPath(orbitPath, color, style = Stroke(1.2f * density, cap = StrokeCap.Round))
        }
    }

    private fun drawBelt(scope: DrawScope) {
        for (i in 0 until BELT_COUNT) {
            if (!project(belt[i * 3], belt[i * 3 + 1], belt[i * 3 + 2], earthOcclusion = true)) continue
            val sx = out[0]
            val sy = out[1]
            if (sx < 0f || sx > width || sy < 0f || sy > height) continue
            val fade = (1.0 - out[2] / (6.0 * SolarSession.AU_KM)).toFloat().coerceIn(0f, 1f)
            scope.drawCircle(BeltDust.copy(alpha = 0.12f + 0.4f * fade), 1.1f * density, Offset(sx, sy))
        }
    }

    /** Farthest first, so a moon behind its planet is painted over by it. */
    private fun drawBodies(scope: DrawScope, session: SolarSession, timeSec: Double) {
        val world = session.world
        val n = world.bodies.size
        for (i in 0 until n) {
            val body = world.bodies[i]
            val dx = world.x(body) - camX
            val dy = world.y(body) - camY
            val dz = world.z(body) - camZ
            bodyDepth[i] = sqrt(dx * dx + dy * dy + dz * dz)
            bodyOrder[i] = i
        }
        for (a in 1 until n) {
            val o = bodyOrder[a]
            val d = bodyDepth[o]
            var b = a - 1
            while (b >= 0 && bodyDepth[bodyOrder[b]] < d) {
                bodyOrder[b + 1] = bodyOrder[b]
                b--
            }
            bodyOrder[b + 1] = o
        }
        for (slot in 0 until n) {
            val i = bodyOrder[slot]
            val body = world.bodies[i]
            if (body == Body.EARTH && realEarth) continue
            drawBody(scope, session, body, bodyDepth[i], timeSec)
        }
    }

    private fun drawBody(scope: DrawScope, session: SolarSession, body: Body, dist: Double, timeSec: Double) {
        val world = session.world
        val bx = world.x(body)
        val by = world.y(body)
        val bz = world.z(body)
        val r = body.radiusKm
        val centreVisible = project(bx, by, bz, earthOcclusion = true)
        val pxRadius = (focal * r / dist.coerceAtLeast(1.0)).toFloat()
        val near = dist < r * 8.0
        if (!centreVisible && !near) return
        if (centreVisible && !near && hiddenByBody(session, bx, by, bz, body)) return
        val cx = out[0]
        val cy = out[1]
        val c = Offset(cx, cy)

        // Sunlight comes from the origin.
        val sm = sqrt(bx * bx + by * by + bz * bz)
        val sunX = if (sm > 1.0) -bx / sm else 0.0
        val sunY = if (sm > 1.0) -by / sm else 0.0
        val sunZ = if (sm > 1.0) -bz / sm else 1.0

        val color = Color(body.colorArgb)
        if (pxRadius < 2.5f && centreVisible) {
            // A point: colour, glow and name. This is most of the sky most of the time.
            val dot = when (body.kind) {
                BodyKind.STAR -> 6f
                BodyKind.PLANET -> if (body == Body.JUPITER || body == Body.VENUS) 3.2f else 2.6f
                BodyKind.MOON -> 2.0f
                BodyKind.DWARF -> 1.8f
            } * density
            if (body == Body.SUN) {
                drawSunGlow(scope, c, dot, 1f)
            }
            scope.drawCircle(color.copy(alpha = 0.3f), dot * 2.6f, c)
            scope.drawCircle(color, dot, c)
            scope.drawCircle(Color.White.copy(alpha = 0.85f), dot * 0.45f, c)
            drawBodyLabel(scope, body, cx + dot + 5f * density, cy + 3f * density)
            return
        }

        // A disc or bigger: atmosphere glow beneath, the lit mesh, rings for Saturn.
        val discView = dist > r * 2.2 && centreVisible
        if (body == Body.SUN) {
            if (centreVisible) drawSunGlow(scope, c, pxRadius, 1f)
        } else if (body.atmosphereArgb != 0L && discView) {
            val atmo = Color(body.atmosphereArgb)
            val outer = pxRadius * 1.32f
            val edge = pxRadius / outer
            scope.drawCircle(
                Brush.radialGradient(
                    (edge - 0.06f).coerceAtLeast(0f) to Color.Transparent,
                    edge to atmo.copy(alpha = 0.55f),
                    (edge + 0.14f).coerceAtMost(1f) to atmo.copy(alpha = 0.16f),
                    1f to Color.Transparent,
                    center = c,
                    radius = outer,
                ),
                outer,
                c,
            )
        }
        if (body == Body.SATURN) {
            buildRings(session, bx, by, bz, sunX, sunY, sunZ)
            drawRingHalf(scope, ringBack)
        }
        drawSphere(scope, body, bx, by, bz, r, sunX, sunY, sunZ, timeSec, session)
        if (body == Body.SATURN) drawRingHalf(scope, ringFront)
        if (body != Body.SUN && discView) {
            // The illustrated limb: a thin bright edge on the day side.
            scope.drawCircle(
                Color.White.copy(alpha = 0.22f),
                pxRadius,
                c,
                style = Stroke((pxRadius * 0.01f).coerceIn(1f, 2.5f)),
            )
        }
        if (pxRadius < 60f * density && centreVisible) {
            drawBodyLabel(scope, body, cx + pxRadius + 6f * density, cy - 4f * density)
        }
    }

    private fun drawBodyLabel(scope: DrawScope, body: Body, x: Float, y: Float) {
        val label = labels[body.id] ?: return
        if (x < -100f || x > width + 20f || y < -30f || y > height + 10f) return
        scope.drawText(label, topLeft = Offset(x, y))
    }

    /** Corona: a wide warm halo that reads as heat from across the system and as glare up close. */
    private fun drawSunGlow(scope: DrawScope, c: Offset, pxRadius: Float, strength: Float) {
        val outer = pxRadius * 3.4f
        scope.drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFFFF3C4).copy(alpha = 0.95f * strength),
                (pxRadius / outer * 0.98f).coerceIn(0.05f, 0.9f) to Color(0xFFFFD04D).copy(alpha = 0.7f * strength),
                (pxRadius / outer * 1.35f).coerceIn(0.1f, 0.95f) to Color(0xFFFF9A2E).copy(alpha = 0.22f * strength),
                1f to Color.Transparent,
                center = c,
                radius = outer,
            ),
            outer,
            c,
        )
    }

    /**
     * A lit sphere of the body's flat-vector surface, drawn as a GPU mesh with per-vertex colour:
     * the surface function on the day side, an indigo fall-off past the terminator, and for the
     * Sun a limb-darkened glow with no lighting at all.
     */
    @Suppress("LongParameterList")
    private fun drawSphere(
        scope: DrawScope,
        body: Body,
        bx: Double,
        by: Double,
        bz: Double,
        r: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        timeSec: Double,
        session: SolarSession,
    ) {
        val world = session.world
        val o = body.ordinal * 3
        pole[0] = world.pole[o]
        pole[1] = world.pole[o + 1]
        pole[2] = world.pole[o + 2]
        // Equatorial basis turned by the spin angle.
        if (abs(pole[2]) < 0.9) set(e1, 0.0, 0.0, 1.0) else set(e1, 1.0, 0.0, 0.0)
        tangentialize(e1, pole)
        normalize(e1)
        cross(pole, e1, e2)
        val spin = world.spin[body.ordinal]
        val cs = cos(spin)
        val ss = sin(spin)
        val e1x = e1[0] * cs + e2[0] * ss
        val e1y = e1[1] * cs + e2[1] * ss
        val e1z = e1[2] * cs + e2[2] * ss
        val e2x = -e1[0] * ss + e2[0] * cs
        val e2y = -e1[1] * ss + e2[1] * cs
        val e2z = -e1[2] * ss + e2[2] * cs
        val px = pole[0]
        val py = pole[1]
        val pz = pole[2]

        val camRelX = camX - bx
        val camRelY = camY - by
        val camRelZ = camZ - bz
        val camDist = sqrt(camRelX * camRelX + camRelY * camRelY + camRelZ * camRelZ).coerceAtLeast(1.0)
        val toCamX = camRelX / camDist
        val toCamY = camRelY / camDist
        val toCamZ = camRelZ / camDist
        val isSun = body == Body.SUN

        for (i in 0 until mesh.vertexCount) {
            val mx = mesh.nx[i]
            val my = mesh.ny[i]
            val mz = mesh.nz[i]
            val nx = e1x * mx + e2x * my + px * mz
            val ny = e1y * mx + e2y * my + py * mz
            val nz = e1z * mx + e2z * my + pz * mz
            val ok = project(bx + nx * r, by + ny * r, bz + nz * r, earthOcclusion = false)
            vOk[i] = ok
            if (ok) positions[i] = Offset(out[0], out[1])
            val base = PlanetSurfaces.color(body, vLat[i], vLon[i])
            colors[i] = if (isSun) {
                val mu = (nx * toCamX + ny * toCamY + nz * toCamZ).coerceIn(0.0, 1.0).toFloat()
                val k =
                    (0.35f + 0.65f * mu) +
                        0.03f * sin(vLat[i] * 0.4 + timeSec * 0.3).toFloat() * sin(vLon[i] * 0.35).toFloat()
                Color(
                    1f,
                    0.62f + 0.36f * k.coerceIn(0f, 1f),
                    0.18f + 0.6f * k.coerceIn(0f, 1f),
                )
            } else {
                val sunDot = nx * sunX + ny * sunY + nz * sunZ
                val wrap = smoothstep(-0.38, 0.42, sunDot)
                val lit = 0.22f + 0.78f * wrap
                Color(
                    base.red * lit + 0.03f * (1f - wrap),
                    base.green * lit + 0.04f * (1f - wrap),
                    base.blue * lit + 0.12f * (1f - wrap),
                )
            }
        }

        indices.clear()
        for (face in 0 until mesh.faceCount) {
            // Facing: the camera is above the face's plane, dot(centroid, camRel) > R |centroid|².
            val cwx = e1x * mesh.cx[face] + e2x * mesh.cy[face] + px * mesh.cz[face]
            val cwy = e1y * mesh.cx[face] + e2y * mesh.cy[face] + py * mesh.cz[face]
            val cwz = e1z * mesh.cx[face] + e2z * mesh.cy[face] + pz * mesh.cz[face]
            if (cwx * camRelX + cwy * camRelY + cwz * camRelZ <= r * mesh.c2[face]) continue
            val a = mesh.fa[face]
            val b = mesh.fb[face]
            val c = mesh.fc[face]
            if (!vOk[a] || !vOk[b] || !vOk[c]) continue
            indices.add(a)
            indices.add(b)
            indices.add(c)
        }
        if (indices.isEmpty()) return
        scope.drawContext.canvas.drawVertices(
            Vertices(VertexMode.Triangles, positions, uvZero, colors, indices),
            BlendMode.Modulate,
            plainPaint,
        )
    }

    /**
     * Saturn's rings as flat quad strips in the equatorial plane, lit from the Sun, darkened where
     * the planet's shadow falls across them, and split into the half behind the planet and the half
     * in front so the planet can be painted between the two.
     */
    @Suppress("LongParameterList")
    private fun buildRings(
        session: SolarSession,
        bx: Double,
        by: Double,
        bz: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
    ) {
        val world = session.world
        val o = Body.SATURN.ordinal * 3
        pole[0] = world.pole[o]
        pole[1] = world.pole[o + 1]
        pole[2] = world.pole[o + 2]
        if (abs(pole[2]) < 0.9) set(e1, 0.0, 0.0, 1.0) else set(e1, 1.0, 0.0, 0.0)
        tangentialize(e1, pole)
        normalize(e1)
        cross(pole, e1, e2)
        val r = Body.SATURN.radiusKm

        // Which side is lit and which side the camera is on: rings seen from the unlit side are dim.
        val sunSide = sunX * pole[0] + sunY * pole[1] + sunZ * pole[2]
        val camSide = (camX - bx) * pole[0] + (camY - by) * pole[1] + (camZ - bz) * pole[2]
        val sameSide = (sunSide >= 0.0) == (camSide >= 0.0)
        val lit = (0.55f + 0.45f * abs(sunSide).toFloat()) * (if (sameSide) 1f else 0.55f)

        // Planet centre depth splits back from front.
        project(bx, by, bz, earthOcclusion = false)
        val centreDepth = out[2].toDouble()

        var v = 0
        ringBack.clear()
        ringFront.clear()
        for (band in PlanetSurfaces.saturnRings) {
            val first = v
            for (k in 0..RING_SEGMENTS) {
                val a = 2.0 * PI * (k % RING_SEGMENTS) / RING_SEGMENTS
                val ca = cos(a)
                val sa = sin(a)
                val dx = e1[0] * ca + e2[0] * sa
                val dy = e1[1] * ca + e2[1] * sa
                val dz = e1[2] * ca + e2[2] * sa
                for (edge in 0..1) {
                    val rr = if (edge == 0) band.innerKm else band.outerKm
                    val wx = bx + dx * rr
                    val wy = by + dy * rr
                    val wz = bz + dz * rr
                    val ok = project(wx, wy, wz, earthOcclusion = false)
                    ringOk[v] = ok
                    if (ok) {
                        ringPositions[v] = Offset(out[0], out[1])
                        ringDepth[v] = out[2].toDouble()
                    }
                    // In the planet's shadow: on the anti-Sun side, inside the shadow cylinder.
                    val along = -(dx * sunX + dy * sunY + dz * sunZ) * rr
                    val perp2 = rr * rr - along * along
                    val shadow = along > 0.0 && perp2 < r * r
                    val shade = if (shadow) lit * 0.22f else lit
                    ringColors[v] = Color(
                        band.color.red * shade,
                        band.color.green * shade,
                        band.color.blue * shade,
                        band.alpha,
                    )
                    v++
                }
            }
            for (k in 0 until RING_SEGMENTS) {
                val i0 = first + k * 2
                val i1 = i0 + 1
                val i2 = i0 + 2
                val i3 = i0 + 3
                if (!ringOk[i0] || !ringOk[i1] || !ringOk[i2] || !ringOk[i3]) continue
                val behind = (ringDepth[i0] + ringDepth[i2]) * 0.5 > centreDepth
                val list = if (behind) ringBack else ringFront
                list.add(i0)
                list.add(i1)
                list.add(i2)
                list.add(i1)
                list.add(i3)
                list.add(i2)
            }
        }
    }

    private fun drawRingHalf(scope: DrawScope, half: ArrayList<Int>) {
        if (half.isEmpty()) return
        scope.drawContext.canvas.drawVertices(
            Vertices(VertexMode.Triangles, ringPositions, ringUv, ringColors, half),
            BlendMode.Modulate,
            plainPaint,
        )
    }

    // endregion

    // region Ships

    private fun drawOtherShips(scope: DrawScope, session: SolarSession, nowMs: Long) {
        for (bot in session.bots) {
            drawShip(scope, session, bot.ship, Color(session.botColor(bot)), session.botUid(bot), faded = false)
        }
        for (remote in session.remotes.values) {
            drawShip(scope, session, remote.ship, Color(remote.colorArgb), remote.uid, faded = remote.isSilent(nowMs))
            if (remote.emote.isNotEmpty() && nowMs - remote.emoteAtMs < EMOTE_MS) {
                val label = emoteLabels[remote.emote] ?: continue
                if (project(remote.ship.pos[0], remote.ship.pos[1], remote.ship.pos[2], earthOcclusion = true)) {
                    drawTag(scope, label, Offset(out[0], out[1] - 34f * density), EmoteFill)
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun drawShip(
        scope: DrawScope,
        session: SolarSession,
        ship: SolarShipState,
        color: Color,
        uid: String,
        faded: Boolean,
    ) {
        val x = ship.pos[0]
        val y = ship.pos[1]
        val z = ship.pos[2]
        if (!project(x, y, z, earthOcclusion = true)) return
        if (hiddenByBody(session, x, y, z, null)) return
        val depth = out[2].toDouble()
        val c = Offset(out[0], out[1])
        val px = globe.pixelsPerKm(depth) * SHIP_SPAN_KM
        val tint = if (faded) color.copy(alpha = 0.5f) else color
        if (px >= 4f * density && depth < MODEL_MAX_KM) {
            drawExhaust(scope, ship, tint)
            drawStarship(scope, ship, tint)
        } else {
            // Across the solar system a friend is a dot with a name: the multiplayer radar.
            val r = (px * 0.5).toFloat().coerceIn(2.4f * density, 6f * density)
            scope.drawCircle(tint.copy(alpha = 0.3f), r * 2.6f, c)
            scope.drawCircle(tint, r, c)
            scope.drawCircle(Color.White.copy(alpha = 0.9f), r * 0.4f, c)
        }
        labels[uid]?.let {
            drawTag(scope, it, Offset(c.x, c.y - (px * 0.6f).toFloat().coerceIn(18f * density, 70f * density)), tint)
        }
    }

    private fun drawStarship(scope: DrawScope, ship: SolarShipState, tint: Color) {
        toEcef(ship.pos[0], ship.pos[1], ship.pos[2], tmp)
        rotEcef(ship.fwd[0], ship.fwd[1], ship.fwd[2], e1)
        rotEcef(-ship.up[0], -ship.up[1], -ship.up[2], e2)
        globe.drawModelWorld(
            scope, SatelliteModels.starship, tmp[0], tmp[1], tmp[2],
            e1[0], e1[1], e1[2], e2[0], e2[1], e2[2],
            ship.rollRad, SHIP_SPAN_KM / 4.4, tint, 0.55f,
        )
    }

    private fun drawLocalShip(scope: DrawScope, session: SolarSession, color: Color, timeSec: Double) {
        val ship = session.local
        if (!project(ship.pos[0], ship.pos[1], ship.pos[2], earthOcclusion = false)) return
        if (ship.immuneS > 0.0 && ((timeSec * 12).toInt() and 1) == 0) return
        drawExhaust(scope, ship, color)
        drawStarship(scope, ship, color)
    }

    /** The engine flame, and at warp a long pale trail: speed you can see from outside the ship. */
    private fun drawExhaust(scope: DrawScope, ship: SolarShipState, tint: Color) {
        if (ship.flame < 0.05) return
        val back = SHIP_SPAN_KM * 0.5
        tmp[0] = ship.pos[0] - ship.fwd[0] * back
        tmp[1] = ship.pos[1] - ship.fwd[1] * back
        tmp[2] = ship.pos[2] - ship.fwd[2] * back
        if (!project(tmp[0], tmp[1], tmp[2], earthOcclusion = false)) return
        val tail = Offset(out[0], out[1])
        val decades = SolarPhysics.warpDecades((ship.speedKmS / SolarPhysics.C).coerceAtLeast(SolarPhysics.WARP_MIN))
        val warpFactor = if (ship.speedKmS > WARP_EFFECT_KM_S) decades else 0.0
        val flameLen = SHIP_SPAN_KM * (0.6 + ship.flame * 1.4) * (1.0 + warpFactor * 6.0)
        tmp2[0] = tmp[0] - ship.fwd[0] * flameLen
        tmp2[1] = tmp[1] - ship.fwd[1] * flameLen
        tmp2[2] = tmp[2] - ship.fwd[2] * flameLen
        if (!project(tmp2[0], tmp2[1], tmp2[2], earthOcclusion = false)) return
        val end = Offset(out[0], out[1])
        val widthPx = (globe.pixelsPerKm(out[2].toDouble()) * SHIP_SPAN_KM * 0.26).toFloat().coerceIn(
            2f * density,
            14f * density,
        )
        val core = if (warpFactor > 0.0) WarpFlame else tint
        scope.drawLine(core.copy(alpha = 0.3f), tail, end, strokeWidth = widthPx * 2.2f, cap = StrokeCap.Round)
        scope.drawLine(core.copy(alpha = 0.85f), tail, end, strokeWidth = widthPx, cap = StrokeCap.Round)
        scope.drawLine(
            Color.White.copy(alpha = 0.9f),
            tail,
            Offset((tail.x + end.x) / 2, (tail.y + end.y) / 2),
            strokeWidth = widthPx * 0.4f,
            cap = StrokeCap.Round,
        )
    }

    private fun drawTag(scope: DrawScope, label: TextLayoutResult, anchor: Offset, fill: Color) {
        val padX = 7f * density
        val padY = 3f * density
        val w = label.size.width + padX * 2
        val h = label.size.height + padY * 2
        val left = anchor.x - w / 2f
        val top = anchor.y - h
        scope.drawRoundRect(TagFill, Offset(left, top), Size(w, h), CornerRadius(h / 2f, h / 2f))
        scope.drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(h / 2f, h / 2f), style = Stroke(density))
        scope.drawText(label, topLeft = Offset(left + padX, top + padY))
    }

    // endregion

    // region HUD in world space

    /** Brackets around the destination, or an arrow on the screen edge toward it. */
    private fun drawTarget(scope: DrawScope, session: SolarSession, timeSec: Double) {
        val target = session.autopilot.target ?: return
        if (session.phase != GamePhase.RUNNING) return
        val world = session.world
        val x = world.x(target)
        val y = world.y(target)
        val z = world.z(target)
        val visible = project(x, y, z, earthOcclusion = false)
        val margin = 30f * density
        val onScreen =
            visible && out[0] > margin && out[0] < width - margin && out[1] > margin && out[1] < height - margin
        if (!onScreen) {
            drawGuide(scope, session, x, y, z, visible, timeSec)
            return
        }
        val c = Offset(out[0], out[1])
        val pxRadius = (globe.pixelsPerKm(out[2].toDouble()) * target.radiusKm).toFloat()
        val size = maxOf(pxRadius * 1.3f, 22f * density)
        if (size > maxOf(width, height)) return
        val arm = size * 0.35f
        val spin = (timeSec * 0.6).toFloat()
        val stroke = Stroke(2f * density, cap = StrokeCap.Round)
        for (k in 0 until 4) {
            val a = spin + k * (PI.toFloat() / 2f)
            val cxk = c.x + cos(a) * size
            val cyk = c.y + sin(a) * size
            // Two short strokes forming a corner, tangent to the ring.
            val tx = -sin(a)
            val ty = cos(a)
            scope.drawLine(
                Reticle,
                Offset(cxk - tx * arm, cyk - ty * arm),
                Offset(cxk + tx * arm, cyk + ty * arm),
                stroke.width,
                StrokeCap.Round,
            )
        }
        val label = targetLabel ?: return
        drawTag(scope, label, Offset(c.x, c.y + size + label.size.height + 14f * density), Reticle.copy(alpha = 0.8f))
    }

    @Suppress("LongParameterList")
    private fun drawGuide(
        scope: DrawScope,
        session: SolarSession,
        x: Double,
        y: Double,
        z: Double,
        visible: Boolean,
        timeSec: Double,
    ) {
        val cx = width / 2f
        val cy = height / 2f
        var dx: Float
        var dy: Float
        if (visible) {
            dx = out[0] - cx
            dy = out[1] - cy
        } else {
            // Behind the camera: aim a short way from the ship toward the target instead.
            val ship = session.local
            val vx = x - ship.pos[0]
            val vy = y - ship.pos[1]
            val vz = z - ship.pos[2]
            val m = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1.0)
            if (!project(
                    ship.pos[0] + vx / m * 30.0,
                    ship.pos[1] + vy / m * 30.0,
                    ship.pos[2] + vz / m * 30.0,
                    false,
                )
            ) {
                return
            }
            dx = out[0] - cx
            dy = out[1] - cy
        }
        val m = hypot(dx, dy)
        if (m < 1f) return
        dx /= m
        dy /= m
        val margin = 46f * density
        val tx = if (dx > 0) {
            (width - margin - cx) / dx
        } else if (dx < 0) {
            (margin - cx) / dx
        } else {
            Float.MAX_VALUE
        }
        val ty = if (dy > 0) {
            (height - margin - cy) / dy
        } else if (dy < 0) {
            (margin - cy) / dy
        } else {
            Float.MAX_VALUE
        }
        val t = minOf(tx, ty)
        val ax = cx + dx * t
        val ay = cy + dy * t
        val angle = atan2(dy, dx)
        val size = (16f + 3f * sin(timeSec * 6.0).toFloat()) * density
        guidePath.reset()
        guidePath.moveTo(ax + cos(angle) * size, ay + sin(angle) * size)
        guidePath.lineTo(ax + cos(angle + 2.5f) * size, ay + sin(angle + 2.5f) * size)
        guidePath.lineTo(ax + cos(angle - 2.5f) * size, ay + sin(angle - 2.5f) * size)
        guidePath.close()
        scope.drawPath(guidePath, Reticle.copy(alpha = 0.35f), style = Stroke(6f * density))
        scope.drawPath(guidePath, Reticle)
    }

    /** Radial streaks that grow with each decade of speed; blue-white past light speed. */
    private fun drawWarpStreaks(scope: DrawScope, session: SolarSession, timeSec: Double) {
        val speed = session.local.speedKmS
        if (speed < WARP_EFFECT_KM_S || session.autopilot.phase == PilotPhase.ORBIT) return
        val strength = SolarPhysics.warpDecades((speed / SolarPhysics.C).coerceAtLeast(SolarPhysics.WARP_MIN)).toFloat()
        if (strength <= 0.02f) return
        val cx = width / 2f
        val cy = height / 2f
        val count = 26
        val phase = (timeSec * 9.0).toFloat()
        val color = if (speed > SolarPhysics.C) WarpFlame else Color.White
        for (k in 0 until count) {
            val a = k * (2f * PI.toFloat() / count) + (k % 3) * 0.13f
            val r0 = (0.5f + 0.14f * sin(a * 7f + phase)) * minOf(cx, cy)
            val r1 = r0 + (50f + 160f * strength) * density
            val alpha = strength * (0.08f + 0.16f * ((sin(phase * 1.7f + k) + 1f) / 2f))
            scope.drawLine(
                color.copy(alpha = alpha),
                Offset(cx + cos(a) * r0, cy + sin(a) * r0),
                Offset(cx + cos(a) * r1, cy + sin(a) * r1),
                strokeWidth = 2f * density,
                cap = StrokeCap.Round,
            )
        }
    }

    /** Too close to the Sun: the edges of the picture glow and pulse. */
    private fun drawHeat(scope: DrawScope, session: SolarSession, timeSec: Double) {
        val heat = session.sunHeat()
        if (heat <= 0.02f) return
        val pulse = if (heat > 0.6f) 0.85f + 0.15f * sin(timeSec * 7.0).toFloat() else 1f
        scope.drawRect(
            Brush.radialGradient(
                0.45f to Color.Transparent,
                1f to HeatOrange.copy(alpha = 0.62f * heat * pulse),
                center = Offset(width / 2f, height / 2f),
                radius = maxOf(width, height) * 0.75f,
            ),
        )
    }

    // endregion

    // region Small vector helpers (allocation-free, on the scratch arrays)

    private fun set(v: DoubleArray, x: Double, y: Double, z: Double) {
        v[0] = x
        v[1] = y
        v[2] = z
    }

    private fun tangentialize(v: DoubleArray, n: DoubleArray) {
        val d = v[0] * n[0] + v[1] * n[1] + v[2] * n[2]
        v[0] -= n[0] * d
        v[1] -= n[1] * d
        v[2] -= n[2] * d
    }

    private fun normalize(v: DoubleArray) {
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (m > 1e-12) {
            v[0] /= m
            v[1] /= m
            v[2] /= m
        }
    }

    private fun cross(a: DoubleArray, b: DoubleArray, o: DoubleArray) {
        val x = a[1] * b[2] - a[2] * b[1]
        val y = a[2] * b[0] - a[0] * b[2]
        val z = a[0] * b[1] - a[1] * b[0]
        o[0] = x
        o[1] = y
        o[2] = z
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }

    // endregion

    companion object {
        const val SHIP_SPAN_KM: Double = 9.5
        private const val MODEL_MAX_KM = 6000.0

        /** Below a fifth of light speed the trail and streaks stay off: cruising near a world should feel calm. */
        private const val WARP_EFFECT_KM_S = 60_000.0
        private const val RING_SEGMENTS = 96
        private const val ORBIT_SAMPLES = 120
        private const val BELT_COUNT = 340
        private const val EMOTE_MS = 3200L

        private val Reticle = Color(0xFF3FE0FF)
        private val BeltDust = Color(0xFFB8C4DA)
        private val TagFill = Color(0xD0101B33)
        private val EmoteFill = Color(0xFFFFFFFF)
        private val WarpFlame = Color(0xFF8FD3FF)
        private val HeatOrange = Color(0xFFFF7A2E)
    }
}

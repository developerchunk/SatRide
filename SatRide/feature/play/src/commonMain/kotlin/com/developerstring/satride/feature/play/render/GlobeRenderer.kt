package com.developerstring.satride.feature.play.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.orbit.OrbitingSatellite
import com.developerstring.satride.core.orbit.frames.Frames
import com.developerstring.satride.core.orbit.time.AstroTime
import com.developerstring.satride.feature.play.PlayGroup
import com.developerstring.satride.feature.play.solar.Body
import com.developerstring.satride.feature.play.solar.Ephemeris
import com.developerstring.satride.feature.play.solar.SolarWorld
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/** Colors the renderer needs from the theme, captured once per composition. */
class GlobePalette(
    val station: Color,
    val weather: Color,
    val amateur: Color,
    val navigation: Color,
    val cubesat: Color,
    val other: Color,
    val selection: Color,
) {
    internal fun colorFor(classType: SatClass): Color = when (classType) {
        SatClass.CREWED -> station
        SatClass.WEATHER -> weather
        SatClass.AMATEUR -> amateur
        SatClass.NAVIGATION -> navigation
        SatClass.CUBESAT -> cubesat
        SatClass.ROCKET_BODY, SatClass.OTHER -> other
    }
}

/** One sampled revolution of the selected orbit, TEME km, computed off the frame path. */
class OrbitRingData(val noradId: Int, val teme: DoubleArray, val epochMs: Double)

/**
 * The Play screen's whole 3D pipeline, drawn through one Compose `Canvas`.
 *
 * ## Why not a "real" engine
 *
 * Filament, SceneKit and friends would each mean a per-platform bridge, an asset pipeline and a
 * desktop target with no story. This renderer is ~one file of projection math feeding
 * `Canvas.drawVertices`, which Skia and HWUI both run on the GPU — the CPU's share is a few
 * thousand dot products a frame, far under a 60 fps budget on anything that can run the app.
 * It matches how everything else in SatRide draws, and it runs identically on all three targets.
 *
 * ## The scene
 *
 * World space is ECEF in kilometres: the Earth mesh is static geometry, satellites arrive as
 * TEME positions rotated by GMST, and fast-forwarded time therefore spins the planet and its
 * constellation with no special cases. Per frame: stars (inertial, counter-rotated), outer
 * atmosphere halo, the lit day-texture pass, an additive night-lights pass, the inner
 * atmosphere rim, the selected satellite's orbit ring, then satellites front-most.
 *
 * All per-frame buffers are preallocated and reused; the only per-frame allocations are the
 * boxed list elements `Vertices` requires and a couple of gradient brushes.
 *
 * Public rather than internal so the app shell's offscreen render test can exercise it.
 */
class GlobeRenderer {

    private val mesh = SphereMesh()
    private val stars = Starfield()

    // Camera basis, rebuilt each frame.
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var rX = 0.0
    private var rY = 0.0
    private var rZ = 0.0
    private var uX = 0.0
    private var uY = 0.0
    private var uZ = 0.0
    private var fX = 0.0
    private var fY = 0.0
    private var fZ = 0.0
    private var focal = 1.0
    private var centerX = 0f
    private var centerY = 0f

    // Per-vertex scratch: camera-space depth and projected screen position.
    private val vertexDepth = DoubleArray(mesh.vertexCount)

    // Camera-space lateral coordinates per vertex, kept so a triangle that straddles the near
    // plane can be clipped (new vertices interpolated) rather than dropped.
    private val vertexCamX = DoubleArray(mesh.vertexCount)
    private val vertexCamY = DoubleArray(mesh.vertexCount)
    private val clipOut = IntArray(4)
    private val positions = ArrayList<Offset>(mesh.vertexCount).apply {
        repeat(mesh.vertexCount) { add(Offset.Zero) }
    }
    private val colorsDay = ArrayList<Color>(mesh.vertexCount).apply {
        repeat(mesh.vertexCount) { add(Color.White) }
    }
    private val colorsNight = ArrayList<Color>(mesh.vertexCount).apply {
        repeat(mesh.vertexCount) { add(Color.Transparent) }
    }
    private val indices = ArrayList<Int>(mesh.faceCount * 3)

    // The night pass only touches faces that are actually dark: its own index list keeps the
    // additive pass at roughly half the sphere's fill instead of repainting all of it.
    private val nightIndices = ArrayList<Int>(mesh.faceCount * 3)
    private val nightAlphaPerVertex = FloatArray(mesh.vertexCount)

    /** When set, the surface renders as vector geometry; the bitmap path is the fallback. */
    var vectorEarth: VectorEarth? = null
        set(value) {
            field = value
            if (value != null) {
                val maxPoints = value.lods.maxOf { it.maxRingPoints }
                if (clipScratch.size < (maxPoints + 8) * 6) {
                    clipScratch = FloatArray((maxPoints + 8) * 6)
                }
                // The near-plane pass runs on the cap clip's output, which can itself have grown,
                // so this buffer is sized for the worst case of both clips compounding.
                if (nearScratch.size < (maxPoints + 8) * 12) {
                    nearScratch = FloatArray((maxPoints + 8) * 12)
                }
            }
        }

    /** City lights as points. Null falls back to the night texture on the bitmap path. */
    var cityLights: CityLights? = null

    private var clipScratch = FloatArray(0)
    private var nearScratch = FloatArray(0)
    private val surfacePath = Path()

    // White, because drawVertices modulates vertex colors against the paint color when no
    // shader is set — the default black would multiply every vertex color to black.
    private val plainPaint = Paint().apply { color = Color.White }
    private val colorsShadow = ArrayList<Color>(mesh.vertexCount).apply {
        repeat(mesh.vertexCount) { add(Color.Transparent) }
    }
    private val shadowIndices = ArrayList<Int>(mesh.faceCount * 3)
    private val shadowAlphaPerVertex = FloatArray(mesh.vertexCount)
    private val uvZero = ArrayList<Offset>(mesh.vertexCount + EXTRA_VERTEX_CAPACITY).apply {
        repeat(mesh.vertexCount) { add(Offset.Zero) }
    }

    private var dayTexture: ImageBitmap? = null
    private var nightTexture: ImageBitmap? = null
    private var uvDay: ArrayList<Offset> = ArrayList()
    private var uvNight: ArrayList<Offset> = ArrayList()

    // High = bicubic sampling, which is what keeps ride mode's magnified ground smooth.
    private val dayPaint = Paint().apply { filterQuality = FilterQuality.High }
    private val nightPaint = Paint().apply {
        filterQuality = FilterQuality.High
        blendMode = BlendMode.Plus
    }

    // Satellite scratch.
    private val satTeme = DoubleArray(3)
    private val satPrev = DoubleArray(3)
    private var pickX = FloatArray(0)
    private var pickY = FloatArray(0)
    private var pickOn = BooleanArray(0)
    private var pickIds = IntArray(0)

    // Model-drawing scratch.
    private val quadPath = Path()
    private val quadDepthScratch = DoubleArray(MAX_MODEL_QUADS)
    private val quadOrderScratch = IntArray(MAX_MODEL_QUADS)
    private val quadSx = FloatArray(4)
    private val quadSy = FloatArray(4)
    private val cornerCam = DoubleArray(MAX_MODEL_QUADS * 12)

    private val ringPath = Path()

    var orbitRing: OrbitRingData? = null

    // Frame clock, for the ride transition and the small ambient animations.
    private var lastFrameNanos = 0L
    private var firstFrame = true

    /** 0 = orbit camera, 1 = riding; eased over ~a second so the POV *travels* to the satellite. */
    private var rideBlend = 0f
    private val lastRideCam = DoubleArray(9) // pos, target, up — held while blending out.
    private var rideCamValid = false

    // The followed satellite is propagated exactly every frame (one SGP4 call is microseconds),
    // so its motion — and the ride camera bolted to it — is perfectly continuous rather than
    // interpolated between background ticks.
    private var cachedSelectedIndex = -1
    private var cachedPropagator: OrbitingSatellite? = null
    private val selectedPosEcef = DoubleArray(3)
    private val selectedVelEcef = DoubleArray(3)
    private var selectedExact = false

    /** Latest screen-space position of the selected satellite, for HUD anchoring; NaN when off. */
    var selectedScreenX: Float = Float.NaN
        private set
    var selectedScreenY: Float = Float.NaN
        private set

    fun setTextures(day: ImageBitmap?, night: ImageBitmap?) {
        if (day !== dayTexture && day != null) {
            dayTexture = day
            uvDay = buildUv(day.width.toFloat(), day.height.toFloat())
            setSmoothImageShader(dayPaint, day)
        }
        if (night !== nightTexture && night != null) {
            nightTexture = night
            uvNight = buildUv(night.width.toFloat(), night.height.toFloat())
            setSmoothImageShader(nightPaint, night)
        }
    }

    private fun buildUv(width: Float, height: Float): ArrayList<Offset> {
        // ImageShader's local space is texture pixels, so UVs are scaled per texture size.
        val list = ArrayList<Offset>(mesh.vertexCount + EXTRA_VERTEX_CAPACITY)
        for (i in 0 until mesh.vertexCount) {
            list.add(Offset(mesh.u[i] * (width - 1f), mesh.v[i] * (height - 1f)))
        }
        return list
    }

    fun render(
        scope: DrawScope,
        field: com.developerstring.satride.feature.play.engine.SatelliteField,
        simTime: Instant,
        group: PlayGroup,
        selectedNoradId: Int,
        riding: Boolean,
        camera: GlobeCamera,
        palette: GlobePalette,
        frameNanos: Long = 0L,
        selectedLabel: TextLayoutResult? = null,
    ) {
        val width = scope.size.width
        val height = scope.size.height
        if (width <= 0f || height <= 0f) return
        val density = scope.density

        val dtSec = if (lastFrameNanos == 0L || frameNanos <= lastFrameNanos) {
            1f / 60f
        } else {
            ((frameNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        }
        lastFrameNanos = frameNanos
        val timeSec = frameNanos / 1e9

        ensurePickCapacity(field)

        val timeMs = simTime.toEpochMilliseconds().toDouble()
        val gmst = AstroTime.gmstRadians(simTime)
        val cosG = cos(gmst)
        val sinG = sin(gmst)

        // Sun direction in the Earth-fixed frame drives both lighting passes.
        val sunTeme = Frames.sunPositionTeme(simTime).normalized()
        val sunX = cosG * sunTeme.x + sinG * sunTeme.y
        val sunY = -sinG * sunTeme.x + cosG * sunTeme.y
        val sunZ = sunTeme.z

        val selectedIndex = if (selectedNoradId > 0) field.indexOf(selectedNoradId) else -1
        updateSelectedExact(field, selectedIndex, simTime, cosG, sinG)
        setUpCamera(camera, field, selectedIndex, timeMs, cosG, sinG, riding, width, height, dtSec)

        if (showSkyBodies) refreshSky(simTime)
        drawEnvironment(
            scope, cosG, sinG, sunX, sunY, sunZ, width, height, density, timeSec,
            includeEarth = true, includeSky = showSkyBodies,
        )

        drawOrbitRing(scope, selectedNoradId, cosG, sinG, width, height, density, timeSec)

        drawSatellites(
            scope, field, timeMs, cosG, sinG, group, selectedIndex, riding,
            camera, palette, sunX, sunY, sunZ, width, height, density, timeSec,
        )

        drawSelectedLabel(scope, selectedLabel, width, density, fade = 1f - rideBlend)
    }

    /**
     * Stars, both atmosphere layers and the planet itself, for whatever camera basis is current.
     *
     * Shared by the Play globe and the Multi-Play arena: everything that is *scenery* rather than
     * *subject* lives here, so the two screens cannot drift apart in how the Earth looks.
     */
    @Suppress("LongParameterList")
    private fun drawEnvironment(
        scope: DrawScope,
        cosG: Double,
        sinG: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        width: Float,
        height: Float,
        density: Float,
        timeSec: Double,
        includeEarth: Boolean,
        includeSky: Boolean,
    ) {
        drawStars(scope, cosG, sinG, width, height, density, timeSec)
        if (includeSky) drawSkyBodies(scope, cosG, sinG, sunX, sunY, sunZ, width, height, density)
        if (!includeEarth) return

        // Earth centre on screen and the exact silhouette radius drive both atmosphere layers.
        val relZ = -(camX * fX + camY * fY + camZ * fZ)
        val camDistance = sqrt(camX * camX + camY * camY + camZ * camZ)
        var earthOnScreen = false
        var earthCx = 0f
        var earthCy = 0f
        var silhouettePx = 0f
        if (relZ > NEAR_KM && camDistance > EARTH_RADIUS_KM * 1.01) {
            val inv = focal / relZ
            earthCx = centerX + (-(camX * rX + camY * rY + camZ * rZ) * inv).toFloat()
            earthCy = centerY - (-(camX * uX + camY * uY + camZ * uZ) * inv).toFloat()
            val tangent = EARTH_RADIUS_KM / sqrt(camDistance * camDistance - EARTH_RADIUS_KM * EARTH_RADIUS_KM)
            silhouettePx = (focal * tangent).toFloat()
            earthOnScreen = true
        }

        // Far out, the silhouette is a near-centred circle and the disc gradients are exact
        // enough. Up close the silhouette's projection is a wide conic — the discs land in the
        // wrong place entirely — so the atmosphere is drawn along the true horizon instead.
        val discAtmosphere = earthOnScreen && camDistance > EARTH_RADIUS_KM * 2.2
        if (discAtmosphere) drawOuterAtmosphere(scope, earthCx, earthCy, silhouettePx)
        val vector = vectorEarth
        if (vector != null) {
            drawVectorEarth(scope, vector, camDistance, sunX, sunY, sunZ, density)
        } else {
            drawEarth(scope, sunX, sunY, sunZ)
        }
        if (discAtmosphere) {
            drawInnerAtmosphere(scope, earthCx, earthCy, silhouettePx)
        } else if (camDistance > EARTH_RADIUS_KM * 1.001) {
            drawHorizonAtmosphere(scope, camDistance, height, density)
        }
    }

    // region Sky bodies — the Sun, Moon and planets at their true directions

    /** Whether the real Sun, Moon and planets are drawn in the sky. Off in the Solar System arena. */
    var showSkyBodies: Boolean = true

    /** Pre-measured names keyed by [Body.id], drawn beside each planet; null draws no names. */
    var skyLabels: Map<String, TextLayoutResult>? = null

    private val skyWorld = SolarWorld()
    private var skyJd = 0.0
    private val skyDir = DoubleArray(3)
    private val moonPath = Path()

    /** Re-evaluates the ephemeris about once a minute; nothing in the sky moves faster than that. */
    private fun refreshSky(simTime: Instant) {
        val jd = Ephemeris.julianDate(simTime)
        if (kotlin.math.abs(jd - skyJd) > 1.0 / 1440.0) {
            skyJd = jd
            skyWorld.update(jd)
        }
    }

    /**
     * The Sun as a bright disc with a corona, the Moon as a phased disc, the planets as coloured
     * points with their names — all at the directions the ephemeris gives for right now, in the
     * same GMST-rotated frame as the stars. This is what makes the Play sky *this evening's* sky.
     */
    @Suppress("LongParameterList")
    private fun drawSkyBodies(
        scope: DrawScope,
        cosG: Double,
        sinG: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        width: Float,
        height: Float,
        density: Float,
    ) {
        val ex = skyWorld.x(Body.EARTH)
        val ey = skyWorld.y(Body.EARTH)
        val ez = skyWorld.z(Body.EARTH)
        val labels = skyLabels
        for (body in skyWorld.bodies) {
            if (body == Body.EARTH) continue
            // Geocentric direction, equatorial → Earth-fixed.
            val gx = skyWorld.x(body) - ex
            val gy = skyWorld.y(body) - ey
            val gz = skyWorld.z(body) - ez
            val gm = sqrt(gx * gx + gy * gy + gz * gz)
            if (gm < 1.0) continue
            val ix = gx / gm
            val iy = gy / gm
            val dx = cosG * ix + sinG * iy
            val dy = -sinG * ix + cosG * iy
            val dz = gz / gm
            val z = dx * fX + dy * fY + dz * fZ
            if (z < 0.02) continue
            // Hidden behind the planet: the ray from the camera along the direction hits the sphere.
            val t = -(camX * dx + camY * dy + camZ * dz)
            if (t > 0.0) {
                val hx = camX + dx * t
                val hy = camY + dy * t
                val hz = camZ + dz * t
                if (hx * hx + hy * hy + hz * hz < EARTH_RADIUS_KM * EARTH_RADIUS_KM) continue
            }
            val inv = focal / z
            val sx = centerX + ((dx * rX + dy * rY + dz * rZ) * inv).toFloat()
            val sy = centerY - ((dx * uX + dy * uY + dz * uZ) * inv).toFloat()
            if (sx < -60f || sx > width + 60f || sy < -60f || sy > height + 60f) continue
            val c = Offset(sx, sy)
            // True angular radius in pixels, floored so the disc bodies stay visible on a phone.
            val angularPx = (focal * body.radiusKm / gm).toFloat()
            when (body) {
                Body.SUN -> {
                    val r = maxOf(angularPx, 7f * density)
                    scope.drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFFFFF3C4).copy(alpha = 0.9f),
                            0.3f to Color(0xFFFFC94D).copy(alpha = 0.45f),
                            1f to Color.Transparent,
                            center = c,
                            radius = r * 4.2f,
                        ),
                        r * 4.2f,
                        c,
                    )
                    scope.drawCircle(Color(0xFFFFE9A8), r * 1.25f, c)
                    scope.drawCircle(Color.White, r, c)
                }

                Body.MOON -> {
                    val r = maxOf(angularPx, 5f * density)
                    // Phase: the lit limb faces the Sun. The dark disc slides off toward the
                    // anti-Sun side by the illuminated fraction.
                    val sunDot = dx * sunX + dy * sunY + dz * sunZ
                    val lit = ((1.0 - sunDot) / 2.0).toFloat().coerceIn(0f, 1f)
                    var ax = (sunX * rX + sunY * rY + sunZ * rZ - (dx * rX + dy * rY + dz * rZ)).toFloat()
                    var ay = -(sunX * uX + sunY * uY + sunZ * uZ - (dx * uX + dy * uY + dz * uZ)).toFloat()
                    val am = sqrt(ax * ax + ay * ay).coerceAtLeast(1e-4f)
                    ax /= am
                    ay /= am
                    scope.drawCircle(Color(0xFFDDE3EE).copy(alpha = 0.25f), r * 1.8f, c)
                    scope.drawCircle(Color(0xFFDDE3EE), r, c)
                    moonPath.reset()
                    moonPath.addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r))
                    scope.clipPath(moonPath) {
                        drawCircle(
                            Color(0xFF232A3F),
                            r * 1.02f,
                            Offset(c.x - ax * 2f * r * lit, c.y - ay * 2f * r * lit),
                        )
                    }
                }

                else -> {
                    val dot = when (body) {
                        Body.VENUS, Body.JUPITER -> 3.2f
                        Body.MARS, Body.SATURN -> 2.7f
                        Body.MERCURY -> 2.2f
                        else -> 1.8f
                    } * density
                    val color = Color(body.colorArgb)
                    scope.drawCircle(color.copy(alpha = 0.28f), dot * 2.6f, c)
                    scope.drawCircle(color, dot, c)
                    scope.drawCircle(Color.White.copy(alpha = 0.85f), dot * 0.45f, c)
                }
            }
            val label = labels?.get(body.id) ?: continue
            scope.drawText(label, topLeft = Offset(sx + 7f * density, sy + 4f * density))
        }
    }

    // endregion

    // region Arena API — an explicit camera and world-space drawing primitives

    /** Sun direction in the Earth-fixed frame for the frame [beginWorld] last set up. */
    private var worldSunX = 0.0
    private var worldSunY = 0.0
    private var worldSunZ = 0.0
    private var worldCosG = 1.0
    private var worldSinG = 0.0
    private var worldWidth = 0f
    private var worldHeight = 0f
    private var worldDensity = 1f

    // Scratch for the oriented-model path, so callers never allocate per model.
    private val axisX = DoubleArray(3)
    private val axisY = DoubleArray(3)
    private val axisZ = DoubleArray(3)

    /**
     * Draws the scenery for an explicit camera, then leaves the projection set up so the caller
     * can place its own objects into the same frame with [projectWorld], [drawModelWorld] and
     * [drawRingWorld].
     *
     * This is the Multi-Play arena's entry point. The Play globe's [render] keeps its own camera
     * model; the arena's chase camera is a different animal (it follows a *player*, not a
     * propagated satellite) and is computed by the game, so the renderer just takes the pose.
     */
    fun beginWorld(
        scope: DrawScope,
        simTime: Instant,
        pose: CameraPose,
        frameNanos: Long,
        earth: Boolean = true,
        sky: Boolean = true,
    ) {
        val width = scope.size.width
        val height = scope.size.height
        if (width <= 0f || height <= 0f) return
        worldWidth = width
        worldHeight = height
        worldDensity = scope.density
        lastFrameNanos = frameNanos
        val timeSec = frameNanos / 1e9

        val gmst = AstroTime.gmstRadians(simTime)
        worldCosG = cos(gmst)
        worldSinG = sin(gmst)
        val sunTeme = Frames.sunPositionTeme(simTime).normalized()
        worldSunX = worldCosG * sunTeme.x + worldSinG * sunTeme.y
        worldSunY = -worldSinG * sunTeme.x + worldCosG * sunTeme.y
        worldSunZ = sunTeme.z

        centerX = width / 2f
        centerY = height / 2f
        focal = (height / 2.0) / kotlin.math.tan(pose.fovDeg * DEG_TO_RAD / 2.0)
        camX = pose.x
        camY = pose.y
        camZ = pose.z
        buildBasis(pose.targetX, pose.targetY, pose.targetZ, pose.upX, pose.upY, pose.upZ)

        val drawSky = sky && showSkyBodies
        if (drawSky) refreshSky(simTime)
        drawEnvironment(
            scope, worldCosG, worldSinG, worldSunX, worldSunY, worldSunZ,
            width, height, worldDensity, timeSec,
            includeEarth = earth, includeSky = drawSky,
        )
    }

    /** The lens' focal length in pixels for the frame [beginWorld] last set up. */
    val focalPx: Double get() = focal

    /** The GMST rotation of the frame [beginWorld] last set up, for callers holding TEME positions. */
    val worldCosGmst: Double get() = worldCosG
    val worldSinGmst: Double get() = worldSinG

    /** Camera distance from the Earth's centre, km, for the frame last set up. */
    val cameraDistanceKm: Double get() = sqrt(camX * camX + camY * camY + camZ * camZ)

    /**
     * Projects an ECEF point (km) into screen space.
     *
     * Writes `sx, sy, cameraDepthKm` into [out] and returns false when the point is behind the
     * near plane or hidden behind the planet — the two cases where drawing anything there would
     * be wrong rather than merely off-screen.
     */
    fun projectWorld(x: Double, y: Double, z: Double, out: FloatArray, checkOcclusion: Boolean = true): Boolean {
        if (checkOcclusion && occludedByEarth(x, y, z)) return false
        val relX = x - camX
        val relY = y - camY
        val relZ = z - camZ
        val depth = relX * fX + relY * fY + relZ * fZ
        if (depth <= NEAR_KM) return false
        val inv = focal / depth
        out[0] = centerX + ((relX * rX + relY * rY + relZ * rZ) * inv).toFloat()
        out[1] = centerY - ((relX * uX + relY * uY + relZ * uZ) * inv).toFloat()
        out[2] = depth.toFloat()
        return true
    }

    /** Screen pixels per world kilometre at [depthKm] — how big a thing of a known size draws. */
    fun pixelsPerKm(depthKm: Double): Double = focal / depthKm.coerceAtLeast(NEAR_KM)

    /** Whether the segment from the camera to the point passes through the planet. */
    fun worldIsOccluded(x: Double, y: Double, z: Double): Boolean = occludedByEarth(x, y, z)

    /**
     * A satellite model at a world position with an explicit attitude.
     *
     * [fwd] is where the nose points and [down] is the body's nadir (both ECEF, need not be unit
     * or orthogonal — they are orthonormalised here), [rollRad] banks the body about its nose.
     * [kmPerUnit] scales the model's ~4.4-unit span into kilometres, so the thing gets smaller
     * with distance like everything else in the world. [tint] blends every face toward a colour,
     * which is how one player's craft is told from another's.
     */
    @Suppress("LongParameterList")
    fun drawModelWorld(
        scope: DrawScope,
        classType: SatClass,
        wx: Double,
        wy: Double,
        wz: Double,
        fwdX: Double,
        fwdY: Double,
        fwdZ: Double,
        downX: Double,
        downY: Double,
        downZ: Double,
        rollRad: Double,
        kmPerUnit: Double,
        tint: Color? = null,
        tintAmount: Float = 0.5f,
    ) = drawModelWorld(
        scope, SatelliteModels.forClass(classType), wx, wy, wz, fwdX, fwdY, fwdZ, downX, downY, downZ,
        rollRad, kmPerUnit, tint, tintAmount,
    )

    /** [drawModelWorld] for an arbitrary procedural model, such as the Solar System starship. */
    @Suppress("LongParameterList")
    internal fun drawModelWorld(
        scope: DrawScope,
        model: SatModel,
        wx: Double,
        wy: Double,
        wz: Double,
        fwdX: Double,
        fwdY: Double,
        fwdZ: Double,
        downX: Double,
        downY: Double,
        downZ: Double,
        rollRad: Double,
        kmPerUnit: Double,
        tint: Color? = null,
        tintAmount: Float = 0.5f,
    ) {
        // Body frame: +z nadir, +x along the nose, +y completing the triad.
        var zx = downX
        var zy = downY
        var zz = downZ
        val zMag = sqrt(zx * zx + zy * zy + zz * zz)
        if (zMag < 1e-9) return
        zx /= zMag
        zy /= zMag
        zz /= zMag
        var xx = fwdX
        var xy = fwdY
        var xz = fwdZ
        val dotZ = xx * zx + xy * zy + xz * zz
        xx -= zx * dotZ
        xy -= zy * dotZ
        xz -= zz * dotZ
        var xMag = sqrt(xx * xx + xy * xy + xz * xz)
        if (xMag < 1e-9) {
            xx = -zy
            xy = zx
            xz = 0.0
            xMag = sqrt(xx * xx + xy * xy).coerceAtLeast(1e-9)
        }
        xx /= xMag
        xy /= xMag
        xz /= xMag
        var yx = zy * xz - zz * xy
        var yy = zz * xx - zx * xz
        var yz = zx * xy - zy * xx
        if (rollRad != 0.0) {
            // Bank: rotate the y and z axes about the nose.
            val cr = cos(rollRad)
            val sr = sin(rollRad)
            val nyx = yx * cr + zx * sr
            val nyy = yy * cr + zy * sr
            val nyz = yz * cr + zz * sr
            zx = -yx * sr + zx * cr
            zy = -yy * sr + zy * cr
            zz = -yz * sr + zz * cr
            yx = nyx
            yy = nyy
            yz = nyz
        }
        axisX[0] = xx
        axisX[1] = xy
        axisX[2] = xz
        axisY[0] = yx
        axisY[1] = yy
        axisY[2] = yz
        axisZ[0] = zx
        axisZ[1] = zy
        axisZ[2] = zz
        drawModelOriented(
            scope, model, wx, wy, wz, axisX, axisY, axisZ,
            kmPerUnit, worldSunX, worldSunY, worldSunZ, worldDensity, tint, tintAmount,
        )
    }

    /**
     * A circle in world space: centre, axis (the ring's normal) and radius in km, stroked as a
     * projected polyline. Segments behind the near plane are skipped rather than clamped, so a ring
     * the camera is flying through opens up instead of folding into a bow-tie.
     */
    @Suppress("LongParameterList")
    fun drawRingWorld(
        scope: DrawScope,
        cx: Double,
        cy: Double,
        cz: Double,
        ax: Double,
        ay: Double,
        az: Double,
        radiusKm: Double,
        color: Color,
        strokeWidthPx: Float,
        segments: Int = 40,
        pathEffect: PathEffect? = null,
    ) {
        val aMag = sqrt(ax * ax + ay * ay + az * az)
        if (aMag < 1e-9) return
        val nx = ax / aMag
        val ny = ay / aMag
        val nz = az / aMag
        // Two in-plane axes: cross with whichever world axis is least aligned with the normal.
        var e1x: Double
        var e1y: Double
        var e1z: Double
        if (kotlin.math.abs(nz) < 0.9) {
            e1x = -ny
            e1y = nx
            e1z = 0.0
        } else {
            e1x = 0.0
            e1y = -nz
            e1z = ny
        }
        val e1m = sqrt(e1x * e1x + e1y * e1y + e1z * e1z).coerceAtLeast(1e-9)
        e1x /= e1m
        e1y /= e1m
        e1z /= e1m
        val e2x = ny * e1z - nz * e1y
        val e2y = nz * e1x - nx * e1z
        val e2z = nx * e1y - ny * e1x

        ringPath.reset()
        var penDown = false
        var any = false
        for (k in 0..segments) {
            val a = 2.0 * PI * (k % segments) / segments
            val ca = cos(a) * radiusKm
            val sa = sin(a) * radiusKm
            val wx = cx + e1x * ca + e2x * sa
            val wy = cy + e1y * ca + e2y * sa
            val wz = cz + e1z * ca + e2z * sa
            val relX = wx - camX
            val relY = wy - camY
            val relZ = wz - camZ
            val depth = relX * fX + relY * fY + relZ * fZ
            if (depth > NEAR_KM) {
                val inv = focal / depth
                val sx = centerX + ((relX * rX + relY * rY + relZ * rZ) * inv).toFloat()
                val sy = centerY - ((relX * uX + relY * uY + relZ * uZ) * inv).toFloat()
                if (penDown) ringPath.lineTo(sx, sy) else ringPath.moveTo(sx, sy)
                penDown = true
                any = true
            } else {
                penDown = false
            }
        }
        if (!any) return
        scope.drawPath(
            ringPath,
            color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, pathEffect = pathEffect),
        )
    }

    // endregion

    /**
     * The friendly name bubble above whatever is selected.
     *
     * [fade] carries it out as the camera travels into a ride. In ride mode the satellite's name is
     * already on the handle at the bottom of the screen, and a second copy floating over the model
     * was the same word twice — so this one leaves. Tied to the ride blend rather than to the
     * boolean so it dissolves along the same curve the camera moves on, instead of popping out on
     * the first frame of a one-second move.
     */
    private fun drawSelectedLabel(
        scope: DrawScope,
        label: TextLayoutResult?,
        width: Float,
        density: Float,
        fade: Float,
    ) {
        if (label == null || selectedScreenX.isNaN()) return
        if (fade <= 0.01f) return
        val padX = 10f * density
        val padY = 6f * density
        val bubbleW = label.size.width + padX * 2
        val bubbleH = label.size.height + padY * 2
        val left = (selectedScreenX - bubbleW / 2f).coerceIn(4f * density, width - bubbleW - 4f * density)
        val top = selectedScreenY - 40f * density - bubbleH
        if (top < 0f) return
        val corner = CornerRadius(bubbleH / 2f, bubbleH / 2f)
        scope.drawRoundRect(
            color = BubbleFill.copy(alpha = BubbleFill.alpha * fade),
            topLeft = Offset(left, top),
            size = Size(bubbleW, bubbleH),
            cornerRadius = corner,
        )
        scope.drawRoundRect(
            color = BubbleStroke.copy(alpha = BubbleStroke.alpha * fade),
            topLeft = Offset(left, top),
            size = Size(bubbleW, bubbleH),
            cornerRadius = corner,
            style = Stroke(width = 1f * density),
        )
        scope.drawText(label, topLeft = Offset(left + padX, top + padY), alpha = fade)
    }

    /** Exact per-frame state for the followed satellite; everything else interpolates. */
    private fun updateSelectedExact(
        field: com.developerstring.satride.feature.play.engine.SatelliteField,
        selectedIndex: Int,
        simTime: Instant,
        cosG: Double,
        sinG: Double,
    ) {
        selectedExact = false
        if (selectedIndex < 0 || selectedIndex >= field.count) {
            cachedSelectedIndex = -1
            cachedPropagator = null
            return
        }
        if (selectedIndex != cachedSelectedIndex) {
            cachedSelectedIndex = selectedIndex
            cachedPropagator = field.propagatorFor(field.ids[selectedIndex])
        }
        val propagator = cachedPropagator ?: return
        val state = runCatching { propagator.stateAt(simTime) }.getOrNull() ?: return
        selectedPosEcef[0] = cosG * state.position.x + sinG * state.position.y
        selectedPosEcef[1] = -sinG * state.position.x + cosG * state.position.y
        selectedPosEcef[2] = state.position.z
        selectedVelEcef[0] = cosG * state.velocity.x + sinG * state.velocity.y
        selectedVelEcef[1] = -sinG * state.velocity.x + cosG * state.velocity.y
        selectedVelEcef[2] = state.velocity.z
        selectedExact = true
    }

    /** Nearest drawn satellite within [maxDistancePx] of (x, y), or 0 when the tap hit space. */
    fun pickNearest(x: Float, y: Float, maxDistancePx: Float): Int {
        var best = 0
        var bestDist = maxDistancePx * maxDistancePx
        for (i in pickOn.indices) {
            if (!pickOn[i]) continue
            val dx = pickX[i] - x
            val dy = pickY[i] - y
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = pickIds[i]
            }
        }
        return best
    }

    private fun ensurePickCapacity(field: com.developerstring.satride.feature.play.engine.SatelliteField) {
        val ids = field.ids
        if (pickIds !== ids || pickOn.size != ids.size) {
            pickIds = ids
            pickX = FloatArray(ids.size)
            pickY = FloatArray(ids.size)
            pickOn = BooleanArray(ids.size)
        }
        pickOn.fill(false)
    }

    // region Camera

    private fun setUpCamera(
        camera: GlobeCamera,
        field: com.developerstring.satride.feature.play.engine.SatelliteField,
        selectedIndex: Int,
        timeMs: Double,
        cosG: Double,
        sinG: Double,
        riding: Boolean,
        width: Float,
        height: Float,
        dtSec: Float,
    ) {
        centerX = width / 2f
        centerY = height / 2f
        focal = (height / 2.0) / kotlin.math.tan(camera.fovDeg * DEG_TO_RAD / 2.0)

        // Swap the resting pitch between the two modes. Idempotent, so calling it every frame is
        // free — it only does anything on the frames where `riding` actually changed.
        camera.setRiding(riding)

        // Resolve the ride camera whenever it is wanted and the satellite has a position; its
        // pose is cached so the blend-out after "exit ride" still has somewhere to travel from.
        var rideResolved = false
        if (riding && selectedIndex >= 0) {
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            var haveSat = false
            if (selectedExact) {
                sx = selectedPosEcef[0]
                sy = selectedPosEcef[1]
                sz = selectedPosEcef[2]
                haveSat = true
            } else if (field.temePositionInto(selectedIndex, timeMs, satTeme)) {
                sx = cosG * satTeme[0] + sinG * satTeme[1]
                sy = -sinG * satTeme[0] + cosG * satTeme[1]
                sz = satTeme[2]
                haveSat = true
            }
            val sMag = sqrt(sx * sx + sy * sy + sz * sz)
            if (haveSat && sMag > EARTH_RADIUS_KM) {
                // Radial-out is "up"; along-track from the exact velocity when available.
                val upLx = sx / sMag
                val upLy = sy / sMag
                val upLz = sz / sMag
                var fwdX: Double
                var fwdY: Double
                var fwdZ: Double
                if (selectedExact) {
                    fwdX = selectedVelEcef[0]
                    fwdY = selectedVelEcef[1]
                    fwdZ = selectedVelEcef[2]
                } else if (field.temePositionInto(selectedIndex, timeMs - 2000.0, satPrev)) {
                    val px = cosG * satPrev[0] + sinG * satPrev[1]
                    val py = -sinG * satPrev[0] + cosG * satPrev[1]
                    fwdX = sx - px
                    fwdY = sy - py
                    fwdZ = sz - satPrev[2]
                } else {
                    fwdX = 0.0
                    fwdY = 0.0
                    fwdZ = 0.0
                }
                val fDotUp = fwdX * upLx + fwdY * upLy + fwdZ * upLz
                fwdX -= upLx * fDotUp
                fwdY -= upLy * fDotUp
                fwdZ -= upLz * fDotUp
                var fMag = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)
                if (fMag < 1e-6) {
                    fwdX = -upLy
                    fwdY = upLx
                    fwdZ = 0.0
                    fMag = sqrt(fwdX * fwdX + fwdY * fwdY).coerceAtLeast(1e-9)
                }
                fwdX /= fMag
                fwdY /= fMag
                fwdZ /= fMag
                val rightX = fwdY * upLz - fwdZ * upLy
                val rightY = fwdZ * upLx - fwdX * upLz
                val rightZ = fwdX * upLy - fwdY * upLx

                // Free-look chase camera: yaw/pitch steer the *view*, and the camera hangs
                // behind it. Dragging down tilts the gaze from the horizon all the way to the
                // ground sliding beneath the satellite; dragging sideways looks around.
                val yaw = camera.yawDeg * DEG_TO_RAD
                val pitchDown = camera.pitchDeg.coerceIn(-80.0, 84.0) * DEG_TO_RAD
                val cp = cos(pitchDown)
                val lookX = (fwdX * cos(yaw) + rightX * sin(yaw)) * cp - upLx * sin(pitchDown)
                val lookY = (fwdY * cos(yaw) + rightY * sin(yaw)) * cp - upLy * sin(pitchDown)
                val lookZ = (fwdZ * cos(yaw) + rightZ * sin(yaw)) * cp - upLz * sin(pitchDown)

                val lift = camera.rideDistanceKm * RIDE_CAMERA_LIFT
                val cpx = sx - lookX * camera.rideDistanceKm + upLx * lift
                val cpy = sy - lookY * camera.rideDistanceKm + upLy * lift
                val cpz = sz - lookZ * camera.rideDistanceKm + upLz * lift

                // Up-hint: the radial made perpendicular to the gaze, so straight-down views
                // stay stable instead of hitting the parallel-up singularity.
                var uhX = upLx - lookX * (upLx * lookX + upLy * lookY + upLz * lookZ)
                var uhY = upLy - lookY * (upLx * lookX + upLy * lookY + upLz * lookZ)
                var uhZ = upLz - lookZ * (upLx * lookX + upLy * lookY + upLz * lookZ)
                val uhMag = sqrt(uhX * uhX + uhY * uhY + uhZ * uhZ)
                if (uhMag < 1e-6) {
                    uhX = fwdX
                    uhY = fwdY
                    uhZ = fwdZ
                } else {
                    uhX /= uhMag
                    uhY /= uhMag
                    uhZ /= uhMag
                }

                lastRideCam[0] = cpx
                lastRideCam[1] = cpy
                lastRideCam[2] = cpz
                lastRideCam[3] = sx + lookX * RIDE_AHEAD_KM
                lastRideCam[4] = sy + lookY * RIDE_AHEAD_KM
                lastRideCam[5] = sz + lookZ * RIDE_AHEAD_KM
                lastRideCam[6] = uhX
                lastRideCam[7] = uhY
                lastRideCam[8] = uhZ
                rideCamValid = true
                rideResolved = true
            }
        }

        // The snap-to-POV transition: ease the blend toward where it wants to be, then mix the
        // two camera poses. The eased path swings out from the orbit view, dives toward the
        // satellite and settles behind it — one continuous camera move, not a cut. The very
        // first frame snaps instead (a restored session should not replay the flight).
        if (firstFrame) {
            rideBlend = if (rideResolved) 1f else 0f
            firstFrame = false
        }
        val step = dtSec / RIDE_TRANSITION_SEC
        rideBlend = (rideBlend + if (rideResolved) step else -step).coerceIn(0f, 1f)
        if (!rideCamValid) rideBlend = 0f
        val t = rideBlend * rideBlend * rideBlend * (rideBlend * (rideBlend * 6 - 15) + 10) // smootherstep

        val yaw = camera.yawDeg * DEG_TO_RAD
        val pitch = camera.pitchDeg * DEG_TO_RAD
        val cp = cos(pitch)
        val orbitX = camera.distanceKm * cp * cos(yaw)
        val orbitY = camera.distanceKm * cp * sin(yaw)
        val orbitZ = camera.distanceKm * sin(pitch)

        if (t <= 0f) {
            camX = orbitX
            camY = orbitY
            camZ = orbitZ
            buildBasis(0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        } else {
            val td = t.toDouble()
            camX = orbitX + (lastRideCam[0] - orbitX) * td
            camY = orbitY + (lastRideCam[1] - orbitY) * td
            camZ = orbitZ + (lastRideCam[2] - orbitZ) * td
            val upX = (1.0 - td) * 0.0 + lastRideCam[6] * td
            val upY = (1.0 - td) * 0.0 + lastRideCam[7] * td
            val upZ = (1.0 - td) * 1.0 + lastRideCam[8] * td
            val upMag = sqrt(upX * upX + upY * upY + upZ * upZ).coerceAtLeast(1e-9)
            buildBasis(
                targetX = lastRideCam[3] * td,
                targetY = lastRideCam[4] * td,
                targetZ = lastRideCam[5] * td,
                upHintX = upX / upMag,
                upHintY = upY / upMag,
                upHintZ = upZ / upMag,
            )
        }
    }

    private fun buildBasis(
        targetX: Double,
        targetY: Double,
        targetZ: Double,
        upHintX: Double,
        upHintY: Double,
        upHintZ: Double,
    ) {
        var fx = targetX - camX
        var fy = targetY - camY
        var fz = targetZ - camZ
        val fMag = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-9)
        fx /= fMag
        fy /= fMag
        fz /= fMag
        var rx = fy * upHintZ - fz * upHintY
        var ry = fz * upHintX - fx * upHintZ
        var rz = fx * upHintY - fy * upHintX
        val rMag = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-9)
        rx /= rMag
        ry /= rMag
        rz /= rMag
        fX = fx
        fY = fy
        fZ = fz
        rX = rx
        rY = ry
        rZ = rz
        uX = ry * fz - rz * fy
        uY = rz * fx - rx * fz
        uZ = rx * fy - ry * fx
    }

    // endregion

    // region Scene layers

    private fun drawStars(
        scope: DrawScope,
        cosG: Double,
        sinG: Double,
        width: Float,
        height: Float,
        density: Float,
        timeSec: Double,
    ) {
        for (i in 0 until stars.count) {
            // Inertial direction rotated into the Earth-fixed render frame.
            val dx = cosG * stars.dx[i] + sinG * stars.dy[i]
            val dy = -sinG * stars.dx[i] + cosG * stars.dy[i]
            val dz = stars.dz[i]
            val z = dx * fX + dy * fY + dz * fZ
            if (z < 0.02) continue
            val inv = focal / z
            val sx = centerX + ((dx * rX + dy * rY + dz * rZ) * inv).toFloat()
            val sy = centerY - ((dx * uX + dy * uY + dz * uZ) * inv).toFloat()
            if (sx < -4f || sx > width + 4f || sy < -4f || sy > height + 4f) continue
            // A slow per-star twinkle keeps the backdrop alive without drawing attention.
            val twinkle = 0.72f + 0.28f * sin(timeSec * 1.8 + i * 1.37).toFloat()
            scope.drawCircle(
                color = Color.White.copy(alpha = stars.alpha[i] * twinkle),
                radius = stars.radiusDp[i] * density,
                center = Offset(sx, sy),
            )
        }
    }

    private fun drawOuterAtmosphere(scope: DrawScope, cx: Float, cy: Float, silhouettePx: Float) {
        // Wider and softer than a physical atmosphere, in a two-tone sky-blue → lavender wash:
        // the pastel halo is most of the illustrated look.
        val outer = silhouettePx * 1.42f
        if (outer < 4f) return
        val edge = silhouettePx / outer
        scope.drawCircle(
            brush = Brush.radialGradient(
                (edge - 0.07f).coerceAtLeast(0f) to Color.Transparent,
                edge to AtmosphereBlue.copy(alpha = 0.60f),
                (edge + 0.15f).coerceAtMost(1f) to AtmosphereLavender.copy(alpha = 0.20f),
                1f to Color.Transparent,
                center = Offset(cx, cy),
                radius = outer,
            ),
            radius = outer,
            center = Offset(cx, cy),
        )
    }

    private fun drawInnerAtmosphere(scope: DrawScope, cx: Float, cy: Float, silhouettePx: Float) {
        if (silhouettePx < 4f) return
        scope.drawCircle(
            brush = Brush.radialGradient(
                0.74f to Color.Transparent,
                0.94f to AtmosphereBlue.copy(alpha = 0.14f),
                1f to Color(0xFFDFF1FF).copy(alpha = 0.55f),
                center = Offset(cx, cy),
                radius = silhouettePx,
            ),
            radius = silhouettePx,
            center = Offset(cx, cy),
        )
        // The thin bright line right at the limb — the airglow edge every orbital photo has.
        scope.drawCircle(
            color = LimbGlow,
            radius = silhouettePx,
            center = Offset(cx, cy),
            style = Stroke(width = (silhouettePx * 0.004f).coerceIn(1f, 2.5f)),
        )
    }

    /**
     * The vector surface: a flat-lit ocean sphere, then continents/deserts/glaciers/lakes as
     * clipped, filled paths, then a soft indigo shadow-side overlay and the city-lights pass.
     *
     * The clip is Sutherland–Hodgman in 3D against the visible-cap plane `u · ĉ = R/d` — rings
     * are culled by bounding cone first, so at ride altitude only the handful of polygons under
     * the camera are ever touched.
     */
    private fun drawVectorEarth(
        scope: DrawScope,
        vector: VectorEarth,
        camDistance: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        density: Float,
    ) {
        // Ocean + shadow vertex passes share the sphere mesh transform.
        var anyNight = false
        var anyShadow = false
        for (i in 0 until mesh.vertexCount) {
            val relX = mesh.px[i] - camX
            val relY = mesh.py[i] - camY
            val relZ = mesh.pz[i] - camZ
            val z = relX * fX + relY * fY + relZ * fZ
            vertexDepth[i] = z
            val cx = relX * rX + relY * rY + relZ * rZ
            val cy = relX * uX + relY * uY + relZ * uZ
            vertexCamX[i] = cx
            vertexCamY[i] = cy
            if (z > MESH_NEAR_KM) {
                val inv = focal / z
                positions[i] = Offset(centerX + (cx * inv).toFloat(), centerY - (cy * inv).toFloat())
            }

            val sunDot = mesh.nx[i] * sunX + mesh.ny[i] * sunY + mesh.nz[i] * sunZ
            // Flat, sunny ocean with a gentle bright patch toward the subsolar point.
            val gleam = smoothstep(0.15, 0.95, sunDot) * 0.55f
            colorsDay[i] = Color(
                OCEAN_R + (OCEAN_LIGHT_R - OCEAN_R) * gleam,
                OCEAN_G + (OCEAN_LIGHT_G - OCEAN_G) * gleam,
                OCEAN_B + (OCEAN_LIGHT_B - OCEAN_B) * gleam,
            )
            // The illustrated terminator: one soft indigo overlay across everything below.
            val shadow = smoothstep(0.20, -0.30, sunDot) * 0.62f
            shadowAlphaPerVertex[i] = shadow
            if (shadow > 0.01f) {
                colorsShadow[i] = Color(SHADOW_R, SHADOW_G, SHADOW_B, shadow)
            } else {
                colorsShadow[i] = Color.Transparent
            }
            if (nightTexture != null) {
                val nightAlpha = smoothstep(-0.08, -0.50, sunDot) * 0.9f
                nightAlphaPerVertex[i] = nightAlpha
                colorsNight[i] = if (nightAlpha > 0.01f) Color(1f, 1f, 1f, nightAlpha) else Color.Transparent
            }
        }

        indices.clear()
        nightIndices.clear()
        shadowIndices.clear()
        trimExtras()
        for (face in 0 until mesh.faceCount) {
            val facing = mesh.cx[face] * camX + mesh.cy[face] * camY + mesh.cz[face] * camZ
            if (facing <= mesh.c2[face]) continue
            val a = mesh.fa[face]
            val b = mesh.fb[face]
            val c = mesh.fc[face]
            val wantShadow =
                shadowAlphaPerVertex[a] > 0.01f || shadowAlphaPerVertex[b] > 0.01f || shadowAlphaPerVertex[c] > 0.01f
            val wantNight =
                nightAlphaPerVertex[a] > 0.01f || nightAlphaPerVertex[b] > 0.01f || nightAlphaPerVertex[c] > 0.01f
            if (!emitFace(a, b, c, wantShadow, wantNight)) continue
            if (wantShadow) anyShadow = true
            if (wantNight) anyNight = true
        }
        if (indices.isEmpty()) return

        val canvas = scope.drawContext.canvas
        canvas.drawVertices(
            Vertices(VertexMode.Triangles, positions, uvZero, colorsDay, indices),
            BlendMode.Modulate,
            plainPaint,
        )

        // Vector landmasses, finest tier the distance justifies.
        val lodIndex = when {
            camDistance < EARTH_RADIUS_KM * 1.45 -> 2
            camDistance < EARTH_RADIUS_KM * 2.3 -> 1
            else -> 0
        }.coerceAtMost(vector.lods.size - 1)
        val lod = vector.lods[lodIndex]
        // No outline pass: stroking would tessellate every path a second time, and the flat
        // fills against the ocean read as clean vector edges on their own.
        drawSurfaceLayer(scope, lod.layers[VectorEarth.LAYER_LAND], camDistance, LandColor, null, density)
        drawSurfaceLayer(scope, lod.layers[VectorEarth.LAYER_DESERT], camDistance, DesertColor, null, density)
        drawSurfaceLayer(scope, lod.layers[VectorEarth.LAYER_GLACIER], camDistance, GlacierColor, null, density)
        drawSurfaceLayer(scope, lod.layers[VectorEarth.LAYER_LAKE], camDistance, LakeColor, null, density)

        if (anyShadow && shadowIndices.isNotEmpty()) {
            canvas.drawVertices(
                Vertices(VertexMode.Triangles, positions, uvZero, colorsShadow, shadowIndices),
                BlendMode.Modulate,
                plainPaint,
            )
        }
        // City lights as points, not as a stretched raster — see [CityLights]. The texture pass is
        // still used by the fallback textured globe below, which has no vector data to be crisp
        // against in the first place.
        cityLights?.let { lights ->
            drawCityLights(scope, lights, camDistance, sunX, sunY, sunZ, density)
        }
    }

    /**
     * The night side, one dot per lit place.
     *
     * Each light is culled three ways before it costs anything: facing (the far hemisphere),
     * darkness (the day side has no visible lights), and brightness after both are applied. What
     * survives is drawn twice — a wide dim halo and a tight bright core — because a single flat
     * circle reads as a sticker while two reads as something glowing.
     *
     * Radius is in *pixels* and clamped, deliberately. Scaling it with distance the way a physical
     * object scales would make every city a sub-pixel speck from far out, where the lights are most
     * of what tells you the planet is inhabited.
     */
    @Suppress("LongParameterList")
    private fun drawCityLights(
        scope: DrawScope,
        lights: CityLights,
        camDistance: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        density: Float,
    ) {
        val eyeUnitX = camX / camDistance
        val eyeUnitY = camY / camDistance
        val eyeUnitZ = camZ / camDistance

        // Horizon cull: a point on the sphere is visible only where its normal faces the camera by
        // more than the angle the camera's altitude subtends.
        val horizonCos = EARTH_RADIUS_KM / camDistance

        // Closer in, the same city covers more screen — but sub-linearly, so a continent's worth
        // of lights never turns back into the blob this replaced.
        val zoom = (EARTH_RADIUS_KM * 3.4 / camDistance).coerceIn(0.55, 3.2)
        val coreRadius = (LIGHT_CORE_PX * density * zoom).toFloat()
        val haloRadius = coreRadius * 2.6f

        var drawn = 0
        for (i in 0 until lights.count) {
            val lx = lights.xyz[i * 3].toDouble()
            val ly = lights.xyz[i * 3 + 1].toDouble()
            val lz = lights.xyz[i * 3 + 2].toDouble()

            val facing = lx * eyeUnitX + ly * eyeUnitY + lz * eyeUnitZ
            if (facing <= horizonCos) continue

            // Only the dark side, fading in across the terminator rather than switching on at it —
            // a hard edge along the terminator is the one artefact that would look worse than the
            // texture this replaced.
            val sunDot = lx * sunX + ly * sunY + lz * sunZ
            val darkness = smoothstep(0.02, -0.28, sunDot)
            if (darkness <= 0.02f) continue

            // Fade at the limb too: lights at a grazing angle are physically dimmer, and without
            // this they form a hard bright rim around the silhouette.
            val limb = smoothstep(horizonCos, horizonCos + 0.22, facing)

            val alpha = lights.intensity[i] * darkness * limb
            if (alpha <= 0.03f) continue

            val relX = lx * EARTH_RADIUS_KM - camX
            val relY = ly * EARTH_RADIUS_KM - camY
            val relZ = lz * EARTH_RADIUS_KM - camZ
            val z = relX * fX + relY * fY + relZ * fZ
            if (z <= NEAR_KM) continue

            val inv = focal / z
            val sx = centerX + ((relX * rX + relY * rY + relZ * rZ) * inv).toFloat()
            val sy = centerY - ((relX * uX + relY * uY + relZ * uZ) * inv).toFloat()

            val center = Offset(sx, sy)
            scope.drawCircle(LightHalo, haloRadius, center, alpha = alpha * 0.30f)
            scope.drawCircle(LightCore, coreRadius, center, alpha = alpha)
            drawn++
            if (drawn >= MAX_LIGHTS_PER_FRAME) break
        }
    }

    private fun drawSurfaceLayer(
        scope: DrawScope,
        rings: List<VectorEarth.Ring>,
        camDistance: Double,
        color: Color,
        outline: Color?,
        density: Float,
    ) {
        val capCos = (EARTH_RADIUS_KM / camDistance).toFloat()
        val capAngle = kotlin.math.acos(capCos.coerceIn(-1f, 1f))
        val cxU = (camX / camDistance).toFloat()
        val cyU = (camY / camDistance).toFloat()
        val czU = (camZ / camDistance).toFloat()

        // Fjord coasts put a thousand-plus visible tiles in the cap; a drawPath per tile is a
        // thousand tessellations. All subpaths accumulate into ONE path and one draw call.
        surfacePath.reset()
        var anything = false

        // Below roughly a pixel, a ring cannot be seen — cull islets by projected size.
        val surfaceRange = (camDistance - EARTH_RADIUS_KM).coerceAtLeast(50.0)
        val minAngle = (1.2 * surfaceRange / (focal * EARTH_RADIUS_KM)).toFloat()

        for (ring in rings) {
            if (ring.radiusAngle < minAngle) continue
            val axisDot = ring.axisX * cxU + ring.axisY * cyU + ring.axisZ * czU
            val axisAngle = kotlin.math.acos(axisDot.coerceIn(-1f, 1f))
            if (axisAngle - ring.radiusAngle > capAngle) continue // fully on the far side

            val needsClip = axisAngle + ring.radiusAngle > capAngle
            val capSource: FloatArray
            val capCount: Int
            if (needsClip) {
                capCount = clipRing(ring.xyz, cxU, cyU, czU, capCos)
                if (capCount < 3) continue
                capSource = clipScratch
            } else {
                capSource = ring.xyz
                capCount = ring.pointCount
            }

            // Second clip, against the camera's near plane. The visible cap is not enough on its
            // own: a chase/ride camera looking at the horizon has visible terrain *beside and
            // behind* itself, whose vertices project with z ≤ 0. The old code bailed out of the
            // whole ring on the first such vertex — which dropped all of Eurasia the moment its
            // ring touched the near plane (ocean where land should be) and left a half-built
            // subpath in [surfacePath] that filled as a giant straight-edged box.
            val count = clipNear(capSource, capCount)
            if (count < 3) continue

            var started = false
            for (p in 0 until count) {
                val wx = nearScratch[p * 3] * EARTH_RADIUS_KM
                val wy = nearScratch[p * 3 + 1] * EARTH_RADIUS_KM
                val wz = nearScratch[p * 3 + 2] * EARTH_RADIUS_KM
                val relX = wx - camX
                val relY = wy - camY
                val relZ = wz - camZ
                val z = (relX * fX + relY * fY + relZ * fZ).coerceAtLeast(NEAR_KM * 0.5)
                val inv = focal / z
                val sx = centerX + ((relX * rX + relY * rY + relZ * rZ) * inv).toFloat()
                val sy = centerY - ((relX * uX + relY * uY + relZ * uZ) * inv).toFloat()
                if (started) surfacePath.lineTo(sx, sy) else surfacePath.moveTo(sx, sy)
                started = true
            }
            if (started) {
                surfacePath.close()
                anything = true
            }
        }
        if (!anything) return
        scope.drawPath(surfacePath, color)
        if (outline != null) {
            scope.drawPath(surfacePath, outline, style = Stroke(width = 1f * density))
        }
    }

    /**
     * Sutherland–Hodgman against the visible-cap plane, on unit vectors. Writes the surviving
     * polygon into [clipScratch] and returns its point count.
     */
    private fun clipRing(xyz: FloatArray, cxU: Float, cyU: Float, czU: Float, capCos: Float): Int {
        val n = xyz.size / 3
        var out = 0
        var prevX = xyz[(n - 1) * 3]
        var prevY = xyz[(n - 1) * 3 + 1]
        var prevZ = xyz[(n - 1) * 3 + 2]
        var prevDot = prevX * cxU + prevY * cyU + prevZ * czU
        for (i in 0 until n) {
            val curX = xyz[i * 3]
            val curY = xyz[i * 3 + 1]
            val curZ = xyz[i * 3 + 2]
            val curDot = curX * cxU + curY * cyU + curZ * czU
            val curIn = curDot >= capCos
            val prevIn = prevDot >= capCos
            if (curIn != prevIn) {
                val t = (capCos - prevDot) / (curDot - prevDot)
                var ix = prevX + (curX - prevX) * t
                var iy = prevY + (curY - prevY) * t
                var iz = prevZ + (curZ - prevZ) * t
                val mag = sqrt((ix * ix + iy * iy + iz * iz).toDouble()).toFloat().coerceAtLeast(1e-6f)
                ix /= mag
                iy /= mag
                iz /= mag
                clipScratch[out * 3] = ix
                clipScratch[out * 3 + 1] = iy
                clipScratch[out * 3 + 2] = iz
                out++
            }
            if (curIn) {
                clipScratch[out * 3] = curX
                clipScratch[out * 3 + 1] = curY
                clipScratch[out * 3 + 2] = curZ
                out++
            }
            prevX = curX
            prevY = curY
            prevZ = curZ
            prevDot = curDot
        }
        return out
    }

    /**
     * Sutherland–Hodgman against the camera near plane `(u·R − cam)·f ≥ NEAR_KM`, on unit
     * vectors. Writes survivors into [nearScratch] and returns the point count.
     *
     * Crossing points are interpolated linearly and *not* renormalised: the plane is linear in
     * 3D, so the exact crossing lies on the chord — pushing it back onto the sphere would move
     * it off the plane again, and the point is at the screen's edge where the sliver of error
     * cannot be seen.
     */
    private fun clipNear(xyz: FloatArray, n: Int): Int {
        val planeOffset = camX * fX + camY * fY + camZ * fZ + NEAR_KM
        fun g(x: Float, y: Float, z: Float): Double = EARTH_RADIUS_KM * (x * fX + y * fY + z * fZ) - planeOffset

        var out = 0
        var prevX = xyz[(n - 1) * 3]
        var prevY = xyz[(n - 1) * 3 + 1]
        var prevZ = xyz[(n - 1) * 3 + 2]
        var prevG = g(prevX, prevY, prevZ)
        for (i in 0 until n) {
            val curX = xyz[i * 3]
            val curY = xyz[i * 3 + 1]
            val curZ = xyz[i * 3 + 2]
            val curG = g(curX, curY, curZ)
            val curIn = curG >= 0.0
            val prevIn = prevG >= 0.0
            if (curIn != prevIn) {
                val t = (prevG / (prevG - curG)).toFloat()
                nearScratch[out * 3] = prevX + (curX - prevX) * t
                nearScratch[out * 3 + 1] = prevY + (curY - prevY) * t
                nearScratch[out * 3 + 2] = prevZ + (curZ - prevZ) * t
                out++
            }
            if (curIn) {
                nearScratch[out * 3] = curX
                nearScratch[out * 3 + 1] = curY
                nearScratch[out * 3 + 2] = curZ
                out++
            }
            prevX = curX
            prevY = curY
            prevZ = curZ
            prevG = curG
        }
        return out
    }

    /**
     * The close-range atmosphere: layered translucent strokes along the exact projected
     * silhouette circle. Valid at any camera position, unlike the disc approximation.
     */
    private fun drawHorizonAtmosphere(scope: DrawScope, camDistance: Double, height: Float, density: Float) {
        val cxU = camX / camDistance
        val cyU = camY / camDistance
        val czU = camZ / camDistance
        // The silhouette circle in 3D: centre ĉ·R²/d, radius R√(1−(R/d)²), in the plane ⊥ ĉ.
        val ratio = EARTH_RADIUS_KM / camDistance
        val circleCenter = EARTH_RADIUS_KM * ratio
        val circleRadius = EARTH_RADIUS_KM * sqrt((1.0 - ratio * ratio).coerceAtLeast(0.0))
        var e1x = -cyU
        var e1y = cxU
        var e1z = 0.0
        var e1m = sqrt(e1x * e1x + e1y * e1y)
        if (e1m < 1e-6) {
            e1x = 1.0
            e1y = 0.0
            e1z = 0.0
            e1m = 1.0
        }
        e1x /= e1m
        e1y /= e1m
        val e2x = cyU * e1z - czU * e1y
        val e2y = czU * e1x - cxU * e1z
        val e2z = cxU * e1y - cyU * e1x

        ringPath.reset()
        var penDown = false
        val steps = 96
        for (k in 0..steps) {
            val a = 2.0 * PI * (k % steps) / steps
            val ca = cos(a)
            val sa = sin(a)
            val wx = cxU * circleCenter + (e1x * ca + e2x * sa) * circleRadius
            val wy = cyU * circleCenter + (e1y * ca + e2y * sa) * circleRadius
            val wz = czU * circleCenter + (e1z * ca + e2z * sa) * circleRadius
            val relX = wx - camX
            val relY = wy - camY
            val relZ = wz - camZ
            val z = relX * fX + relY * fY + relZ * fZ
            if (z > NEAR_KM) {
                val inv = focal / z
                val sx = centerX + ((relX * rX + relY * rY + relZ * rZ) * inv).toFloat()
                val sy = centerY - ((relX * uX + relY * uY + relZ * uZ) * inv).toFloat()
                if (penDown) ringPath.lineTo(sx, sy) else ringPath.moveTo(sx, sy)
                penDown = true
            } else {
                penDown = false
            }
        }

        val glowWidth = height * 0.10f
        scope.drawPath(ringPath, AtmosphereBlue.copy(alpha = 0.10f), style = Stroke(glowWidth, cap = StrokeCap.Round))
        scope.drawPath(
            ringPath,
            AtmosphereBlue.copy(alpha = 0.16f),
            style = Stroke(glowWidth * 0.45f, cap = StrokeCap.Round),
        )
        scope.drawPath(
            ringPath,
            Color(0xFFDFF1FF).copy(alpha = 0.55f),
            style = Stroke(2.2f * density, cap = StrokeCap.Round),
        )
    }

    private fun drawEarth(scope: DrawScope, sunX: Double, sunY: Double, sunZ: Double) {
        val day = dayTexture ?: return
        var anyNight = false

        for (i in 0 until mesh.vertexCount) {
            val relX = mesh.px[i] - camX
            val relY = mesh.py[i] - camY
            val relZ = mesh.pz[i] - camZ
            val z = relX * fX + relY * fY + relZ * fZ
            vertexDepth[i] = z
            val cx = relX * rX + relY * rY + relZ * rZ
            val cy = relX * uX + relY * uY + relZ * uZ
            vertexCamX[i] = cx
            vertexCamY[i] = cy
            if (z > MESH_NEAR_KM) {
                val inv = focal / z
                positions[i] = Offset(centerX + (cx * inv).toFloat(), centerY - (cy * inv).toFloat())
            }

            val sunDot = mesh.nx[i] * sunX + mesh.ny[i] * sunY + mesh.nz[i] * sunZ
            // Wrapped, tinted lighting: a wide soft terminator, the lit side barely shaded so
            // the flat colors stay vivid, and the night side falling to a readable indigo
            // rather than black — illustrated, not photometric.
            val wrap = smoothstep(-0.42, 0.45, sunDot)
            colorsDay[i] = Color(
                0.32f + 0.68f * wrap,
                0.36f + 0.64f * wrap,
                0.58f + 0.40f * wrap,
            )
            if (nightTexture != null) {
                val nightAlpha = smoothstep(-0.08, -0.50, sunDot) * 0.9f
                nightAlphaPerVertex[i] = nightAlpha
                colorsNight[i] = if (nightAlpha > 0.01f) {
                    if (z > NEAR_KM) anyNight = true
                    Color(1f, 1f, 1f, nightAlpha)
                } else {
                    Color.Transparent
                }
            }
        }

        indices.clear()
        nightIndices.clear()
        shadowIndices.clear()
        trimExtras()
        for (face in 0 until mesh.faceCount) {
            // Front-facing ⇔ the centroid sees the camera above its horizon plane:
            // dot(centroid, cam) > |centroid|².
            val facing = mesh.cx[face] * camX + mesh.cy[face] * camY + mesh.cz[face] * camZ
            if (facing <= mesh.c2[face]) continue
            val a = mesh.fa[face]
            val b = mesh.fb[face]
            val c = mesh.fc[face]
            val wantNight =
                nightAlphaPerVertex[a] > 0.01f || nightAlphaPerVertex[b] > 0.01f || nightAlphaPerVertex[c] > 0.01f
            emitFace(a, b, c, wantShadow = false, wantNight = wantNight)
        }
        if (indices.isEmpty()) return

        val canvas = scope.drawContext.canvas
        canvas.drawVertices(
            Vertices(VertexMode.Triangles, positions, uvDay, colorsDay, indices),
            BlendMode.Modulate,
            dayPaint,
        )
        // The textured fallback keeps its raster night pass. There is nothing crisp beside it here
        // to expose the blur — this path only runs when the vector surface is missing entirely, and
        // a soft globe lit softly is coherent in a way a sharp one with soft lights is not.
        if (anyNight && nightTexture != null && nightIndices.isNotEmpty()) {
            canvas.drawVertices(
                Vertices(VertexMode.Triangles, positions, uvNight, colorsNight, nightIndices),
                BlendMode.Modulate,
                nightPaint,
            )
        }
    }

    private fun drawOrbitRing(
        scope: DrawScope,
        selectedNoradId: Int,
        cosG: Double,
        sinG: Double,
        width: Float,
        height: Float,
        density: Float,
        timeSec: Double,
    ) {
        val ring = orbitRing ?: return
        if (ring.noradId != selectedNoradId) return
        val points = ring.teme.size / 3
        if (points < 2) return

        ringPath.reset()
        var penDown = false
        for (k in 0..points) {
            val i = (k % points) * 3
            val tx = ring.teme[i]
            val ty = ring.teme[i + 1]
            val tz = ring.teme[i + 2]
            val wx = cosG * tx + sinG * ty
            val wy = -sinG * tx + cosG * ty

            var visible = !occludedByEarth(wx, wy, tz)
            var sx = 0f
            var sy = 0f
            if (visible) {
                val relX = wx - camX
                val relY = wy - camY
                val relZ = tz - camZ
                val z = relX * fX + relY * fY + relZ * fZ
                if (z > NEAR_KM) {
                    val inv = focal / z
                    sx = centerX + ((relX * rX + relY * rY + relZ * rZ) * inv).toFloat()
                    sy = centerY - ((relX * uX + relY * uY + relZ * uZ) * inv).toFloat()
                    if (sx < -width || sx > width * 2 || sy < -height || sy > height * 2) visible = false
                } else {
                    visible = false
                }
            }

            if (visible) {
                if (penDown) ringPath.lineTo(sx, sy) else ringPath.moveTo(sx, sy)
                penDown = true
            } else {
                penDown = false
            }
        }
        // A rounded dashed stroke whose phase advances with time: the path itself appears to
        // flow along the direction of travel, which reads as motion even when zoomed far out.
        val dash = 13f * density
        val gap = 9f * density
        val phase = (-(timeSec * 46.0) % (dash + gap).toDouble()).toFloat() * density
        scope.drawPath(
            path = ringPath,
            color = RingCyan,
            style = Stroke(
                width = 2.2f * density,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), phase),
            ),
        )
    }

    private fun drawSatellites(
        scope: DrawScope,
        field: com.developerstring.satride.feature.play.engine.SatelliteField,
        timeMs: Double,
        cosG: Double,
        sinG: Double,
        group: PlayGroup,
        selectedIndex: Int,
        riding: Boolean,
        camera: GlobeCamera,
        palette: GlobePalette,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        width: Float,
        height: Float,
        density: Float,
        timeSec: Double,
    ) {
        selectedScreenX = Float.NaN
        selectedScreenY = Float.NaN

        // Bounded by the pick arrays as well as the field: mid-frame catalog swaps can never
        // index past whichever is smaller.
        val satelliteCount = minOf(field.count, pickOn.size, field.classes.size)

        /*
         * The draw budget.
         *
         * The field carries everything the user tracks, which can be the whole catalog. Every
         * satellite is present, propagated and tappable — but a phone cannot draw thirty thousand
         * dots at thirty frames a second, and a globe with thirty thousand dots on it is a solid
         * shell rather than a picture of anything.
         *
         * So above [DRAW_BUDGET] the loop takes every `stride`-th satellite. A *stride* rather
         * than a prefix or a random sample: the field is ordered by catalog number, so a stride
         * spreads the drawn set evenly across orbits and constellations instead of showing every
         * satellite launched before 1990 and none after. It is also stable frame to frame — the
         * same satellites are drawn every frame until the field changes — so the globe does not
         * shimmer, which any per-frame reselection would cause.
         *
         * The selected satellite is drawn whatever the stride says, because the one thing the user
         * has actually pointed at must never be the one that gets dropped.
         */
        val stride = if (satelliteCount > DRAW_BUDGET) satelliteCount / DRAW_BUDGET else 1
        // Dense fields lose the catch-light and the halo: three draw calls per satellite is what
        // makes a candy dot, and at this density it is also what would make the frame late.
        val dense = stride > 1

        for (i in 0 until satelliteCount) {
            val isSelected = i == selectedIndex
            if (!isSelected && stride > 1 && i % stride != 0) continue
            if (!isSelected && !group.matches(field.classes[i])) continue

            val wx: Double
            val wy: Double
            val wz: Double
            if (isSelected && selectedExact) {
                // The followed satellite gets true per-frame propagation — its motion (and the
                // camera riding it) is continuous, never a chord between background ticks.
                wx = selectedPosEcef[0]
                wy = selectedPosEcef[1]
                wz = selectedPosEcef[2]
            } else {
                if (!field.temePositionInto(i, timeMs, satTeme)) continue
                wx = cosG * satTeme[0] + sinG * satTeme[1]
                wy = -sinG * satTeme[0] + cosG * satTeme[1]
                wz = satTeme[2]
            }
            if (occludedByEarth(wx, wy, wz)) continue

            val relX = wx - camX
            val relY = wy - camY
            val relZ = wz - camZ
            val z = relX * fX + relY * fY + relZ * fZ
            if (z <= NEAR_KM) continue
            val inv = focal / z
            val sx = centerX + ((relX * rX + relY * rY + relZ * rZ) * inv).toFloat()
            val sy = centerY - ((relX * uX + relY * uY + relZ * uZ) * inv).toFloat()
            if (sx < -MARGIN_PX || sx > width + MARGIN_PX || sy < -MARGIN_PX || sy > height + MARGIN_PX) continue

            pickX[i] = sx
            pickY[i] = sy
            pickOn[i] = true

            if (isSelected) {
                selectedScreenX = sx
                selectedScreenY = sy
            }

            val color = palette.colorFor(field.classes[i])
            val dotRadius = ((focal * 34.0 / z).toFloat()).coerceIn(1.8f * density, 3.6f * density)

            if (isSelected) {
                val asModel = riding || camera.distanceKm < EARTH_RADIUS_KM * 2.4
                if (asModel) {
                    val targetPx = (if (riding) 120f else 64f) * density
                    drawSatelliteModel(
                        scope, field.classes[i], i, field, timeMs, cosG, sinG,
                        wx, wy, wz, z, sunX, sunY, sunZ, targetPx, density,
                    )
                } else {
                    scope.drawCircle(color.copy(alpha = 0.32f), dotRadius * 3.0f, Offset(sx, sy))
                    scope.drawCircle(color, dotRadius * 1.5f, Offset(sx, sy))
                    scope.drawCircle(Color.White, dotRadius * 0.7f, Offset(sx, sy))
                }
                // A gentle breathing ring instead of a static marker.
                val pulse = 1f + 0.14f * sin(timeSec * 4.2).toFloat()
                scope.drawCircle(
                    color = palette.selection,
                    radius = dotRadius * 4.2f * pulse,
                    center = Offset(sx, sy),
                    style = Stroke(width = 1.6f * density, cap = StrokeCap.Round),
                )
            } else if (dense) {
                // One call instead of three. At this density the halo would overlap its
                // neighbours into a wash anyway, and the catch-light is smaller than the gaps
                // between dots.
                scope.drawCircle(color, dotRadius * 1.05f, Offset(sx, sy))
            } else {
                // Candy dot: soft halo, saturated body, white catch-light core.
                scope.drawCircle(color.copy(alpha = 0.26f), dotRadius * 2.6f, Offset(sx, sy))
                scope.drawCircle(color, dotRadius * 1.15f, Offset(sx, sy))
                scope.drawCircle(Color.White.copy(alpha = 0.9f), dotRadius * 0.45f, Offset(sx, sy))
            }
        }
    }

    private fun drawSatelliteModel(
        scope: DrawScope,
        classType: SatClass,
        index: Int,
        field: com.developerstring.satride.feature.play.engine.SatelliteField,
        timeMs: Double,
        cosG: Double,
        sinG: Double,
        wx: Double,
        wy: Double,
        wz: Double,
        camSpaceZ: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        targetPx: Float,
        density: Float,
    ) {
        val model = SatelliteModels.forClass(classType)
        val quadCount = model.quads.size.coerceAtMost(MAX_MODEL_QUADS)

        // Body frame: +z nadir, +x along-track, +y completing the triad.
        val sMag = sqrt(wx * wx + wy * wy + wz * wz).coerceAtLeast(1e-9)
        val zbX = -wx / sMag
        val zbY = -wy / sMag
        val zbZ = -wz / sMag
        var xbX: Double
        var xbY: Double
        var xbZ: Double
        if (index == cachedSelectedIndex && selectedExact) {
            xbX = selectedVelEcef[0]
            xbY = selectedVelEcef[1]
            xbZ = selectedVelEcef[2]
        } else if (field.temePositionInto(index, timeMs - 2000.0, satPrev)) {
            val px = cosG * satPrev[0] + sinG * satPrev[1]
            val py = -sinG * satPrev[0] + cosG * satPrev[1]
            xbX = wx - px
            xbY = wy - py
            xbZ = wz - satPrev[2]
        } else {
            xbX = -wy
            xbY = wx
            xbZ = 0.0
        }
        val dotZ = xbX * zbX + xbY * zbY + xbZ * zbZ
        xbX -= zbX * dotZ
        xbY -= zbY * dotZ
        xbZ -= zbZ * dotZ
        var xMag = sqrt(xbX * xbX + xbY * xbY + xbZ * xbZ)
        if (xMag < 1e-9) {
            xbX = -wy / sMag
            xbY = wx / sMag
            xbZ = 0.0
            xMag = sqrt(xbX * xbX + xbY * xbY).coerceAtLeast(1e-9)
        }
        xbX /= xMag
        xbY /= xMag
        xbZ /= xMag
        val ybX = zbY * xbZ - zbZ * xbY
        val ybY = zbZ * xbX - zbX * xbZ
        val ybZ = zbX * xbY - zbY * xbX

        // Scale so the model's ~4.4-unit span fills targetPx on screen at its actual depth.
        val kmPerUnit = targetPx * camSpaceZ / (focal * 4.4)

        axisX[0] = xbX
        axisX[1] = xbY
        axisX[2] = xbZ
        axisY[0] = ybX
        axisY[1] = ybY
        axisY[2] = ybZ
        axisZ[0] = zbX
        axisZ[1] = zbY
        axisZ[2] = zbZ
        drawModelOriented(scope, model, wx, wy, wz, axisX, axisY, axisZ, kmPerUnit, sunX, sunY, sunZ, density, null, 0f)
    }

    /**
     * The shared model rasteriser: body axes in, painter-sorted lit quads out.
     *
     * [bx], [by], [bz] are the body's x (nose), y and z (nadir) axes in world space, unit length.
     * Everything here is allocation-free and bounded by [MAX_MODEL_QUADS].
     */
    @Suppress("LongParameterList")
    private fun drawModelOriented(
        scope: DrawScope,
        model: SatModel,
        wx: Double,
        wy: Double,
        wz: Double,
        bx: DoubleArray,
        by: DoubleArray,
        bz: DoubleArray,
        kmPerUnit: Double,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        density: Float,
        tint: Color?,
        tintAmount: Float,
    ) {
        val quadCount = model.quads.size.coerceAtMost(MAX_MODEL_QUADS)
        val xbX = bx[0]
        val xbY = bx[1]
        val xbZ = bx[2]
        val ybX = by[0]
        val ybY = by[1]
        val ybZ = by[2]
        val zbX = bz[0]
        val zbY = bz[1]
        val zbZ = bz[2]

        var drawable = 0
        for (q in 0 until quadCount) {
            val quad = model.quads[q]
            var depthSum = 0.0
            var behind = false
            for (corner in 0 until 4) {
                val mx = quad.corners[corner * 3]
                val my = quad.corners[corner * 3 + 1]
                val mz = quad.corners[corner * 3 + 2]
                val pxW = wx + (xbX * mx + ybX * my + zbX * mz) * kmPerUnit
                val pyW = wy + (xbY * mx + ybY * my + zbY * mz) * kmPerUnit
                val pzW = wz + (xbZ * mx + ybZ * my + zbZ * mz) * kmPerUnit
                val relX = pxW - camX
                val relY = pyW - camY
                val relZ = pzW - camZ
                val cz = relX * fX + relY * fY + relZ * fZ
                if (cz <= NEAR_KM) {
                    behind = true
                    break
                }
                val base = q * 12 + corner * 3
                cornerCam[base] = relX * rX + relY * rY + relZ * rZ
                cornerCam[base + 1] = relX * uX + relY * uY + relZ * uZ
                cornerCam[base + 2] = cz
                depthSum += cz
            }
            if (behind) continue
            quadDepthScratch[drawable] = depthSum
            quadOrderScratch[drawable] = q
            drawable++
        }
        if (drawable == 0) return

        // Painter's algorithm over a dozen quads: insertion sort, farthest first.
        for (a in 1 until drawable) {
            val d = quadDepthScratch[a]
            val o = quadOrderScratch[a]
            var b = a - 1
            while (b >= 0 && quadDepthScratch[b] < d) {
                quadDepthScratch[b + 1] = quadDepthScratch[b]
                quadOrderScratch[b + 1] = quadOrderScratch[b]
                b--
            }
            quadDepthScratch[b + 1] = d
            quadOrderScratch[b + 1] = o
        }

        for (slot in 0 until drawable) {
            val q = quadOrderScratch[slot]
            val quad = model.quads[q]
            val base = q * 12
            for (corner in 0 until 4) {
                val ccx = cornerCam[base + corner * 3]
                val ccy = cornerCam[base + corner * 3 + 1]
                val ccz = cornerCam[base + corner * 3 + 2]
                val inv = focal / ccz
                quadSx[corner] = centerX + (ccx * inv).toFloat()
                quadSy[corner] = centerY - (ccy * inv).toFloat()
            }

            // Face normal in world space, from the body-frame normal of the quad.
            val e1x = quad.corners[3] - quad.corners[0]
            val e1y = quad.corners[4] - quad.corners[1]
            val e1z = quad.corners[5] - quad.corners[2]
            val e2x = quad.corners[9] - quad.corners[0]
            val e2y = quad.corners[10] - quad.corners[1]
            val e2z = quad.corners[11] - quad.corners[2]
            val nmx = e1y * e2z - e1z * e2y
            val nmy = e1z * e2x - e1x * e2z
            val nmz = e1x * e2y - e1y * e2x
            val nWx = xbX * nmx + ybX * nmy + zbX * nmz
            val nWy = xbY * nmx + ybY * nmy + zbY * nmz
            val nWz = xbZ * nmx + ybZ * nmy + zbZ * nmz
            val nMag = sqrt(nWx * nWx + nWy * nWy + nWz * nWz).coerceAtLeast(1e-9)
            val sunDot = (nWx * sunX + nWy * sunY + nWz * sunZ) / nMag
            // Wrapped and generous: even shadowed faces keep over half their color, so the
            // model reads as a bright illustration; the outline supplies the definition.
            val shade = (0.58 + 0.42 * kotlin.math.abs(sunDot)).toFloat()

            quadPath.reset()
            quadPath.moveTo(quadSx[0], quadSy[0])
            quadPath.lineTo(quadSx[1], quadSy[1])
            quadPath.lineTo(quadSx[2], quadSy[2])
            quadPath.lineTo(quadSx[3], quadSy[3])
            quadPath.close()
            val baseColor = quad.color
            val c = if (tint != null && tintAmount > 0f) {
                Color(
                    baseColor.red + (tint.red - baseColor.red) * tintAmount,
                    baseColor.green + (tint.green - baseColor.green) * tintAmount,
                    baseColor.blue + (tint.blue - baseColor.blue) * tintAmount,
                    baseColor.alpha,
                )
            } else {
                baseColor
            }
            scope.drawPath(quadPath, Color(c.red * shade, c.green * shade, c.blue * shade, c.alpha))
            // The vector-illustration outline: a darker stroke of the same hue on every face.
            scope.drawPath(
                quadPath,
                Color(c.red * shade * 0.45f, c.green * shade * 0.45f, c.blue * shade * 0.5f, c.alpha),
                style = Stroke(width = 1.1f * density, cap = StrokeCap.Round),
            )
        }
    }

    // endregion

    // region Near-plane clipping of the sphere mesh

    /**
     * Drops the vertices a previous frame's clipping appended, so every frame starts from the
     * bare mesh. Cheap: removing a tail range from an ArrayList is one array shift of nothing.
     */
    private fun trimExtras() {
        val n = mesh.vertexCount
        positions.trimTo(n)
        colorsDay.trimTo(n)
        colorsNight.trimTo(n)
        colorsShadow.trimTo(n)
        uvZero.trimTo(n)
        uvDay.trimTo(n)
        uvNight.trimTo(n)
    }

    private fun <T> ArrayList<T>.trimTo(n: Int) {
        if (size > n) subList(n, size).clear()
    }

    /**
     * Adds one mesh face to the index lists, clipping it against the near plane if it straddles
     * it. Returns false when the face is entirely behind the camera.
     *
     * Dropping straddling faces was what put black boxes along the bottom of the screen when a
     * low, wide-angle camera (the arena's chase cam) looked down at the ground: the sphere mesh
     * is coarse, so a face whose one behind-the-camera corner was rejected took a few hundred
     * kilometres of perfectly visible ocean with it. Sutherland–Hodgman on the depth gives a
     * three- or four-sided polygon instead, with its new corners interpolated in camera space.
     */
    private fun emitFace(a: Int, b: Int, c: Int, wantShadow: Boolean, wantNight: Boolean): Boolean {
        val inA = vertexDepth[a] > MESH_NEAR_KM
        val inB = vertexDepth[b] > MESH_NEAR_KM
        val inC = vertexDepth[c] > MESH_NEAR_KM
        if (inA && inB && inC) {
            addTriangle(a, b, c, wantShadow, wantNight)
            return true
        }
        if (!inA && !inB && !inC) return false
        if (positions.size + 2 > mesh.vertexCount + EXTRA_VERTEX_CAPACITY) return false

        var n = 0
        var prev = c
        var prevIn = inC
        val corners = clipOut
        for (k in 0 until 3) {
            val cur = when (k) {
                0 -> a
                1 -> b
                else -> c
            }
            val curIn = when (k) {
                0 -> inA
                1 -> inB
                else -> inC
            }
            if (curIn != prevIn) {
                val dPrev = vertexDepth[prev]
                val dCur = vertexDepth[cur]
                val t = ((MESH_NEAR_KM - dPrev) / (dCur - dPrev)).toFloat()
                corners[n++] = appendClippedVertex(prev, cur, t)
            }
            if (curIn) corners[n++] = cur
            prev = cur
            prevIn = curIn
        }
        if (n < 3) return false
        addTriangle(corners[0], corners[1], corners[2], wantShadow, wantNight)
        if (n == 4) addTriangle(corners[0], corners[2], corners[3], wantShadow, wantNight)
        return true
    }

    private fun addTriangle(a: Int, b: Int, c: Int, wantShadow: Boolean, wantNight: Boolean) {
        indices.add(a)
        indices.add(b)
        indices.add(c)
        if (wantShadow) {
            shadowIndices.add(a)
            shadowIndices.add(b)
            shadowIndices.add(c)
        }
        if (wantNight) {
            nightIndices.add(a)
            nightIndices.add(b)
            nightIndices.add(c)
        }
    }

    /** A new vertex on the near plane between mesh vertices [i] and [j]; returns its index. */
    private fun appendClippedVertex(i: Int, j: Int, t: Float): Int {
        val cx = vertexCamX[i] + (vertexCamX[j] - vertexCamX[i]) * t
        val cy = vertexCamY[i] + (vertexCamY[j] - vertexCamY[i]) * t
        val inv = focal / MESH_NEAR_KM
        positions.add(Offset(centerX + (cx * inv).toFloat(), centerY - (cy * inv).toFloat()))
        colorsDay.add(lerpColor(colorsDay[i], colorsDay[j], t))
        colorsNight.add(lerpColor(colorsNight[i], colorsNight[j], t))
        colorsShadow.add(lerpColor(colorsShadow[i], colorsShadow[j], t))
        uvZero.add(Offset.Zero)
        if (uvDay.size >= mesh.vertexCount) uvDay.add(lerpOffset(uvDay[i], uvDay[j], t))
        if (uvNight.size >= mesh.vertexCount) uvNight.add(lerpOffset(uvNight[i], uvNight[j], t))
        return positions.size - 1
    }

    private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        a.alpha + (b.alpha - a.alpha) * t,
    )

    private fun lerpOffset(a: Offset, b: Offset, t: Float): Offset =
        Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    // endregion

    /** Whether the segment from the camera to (x, y, z) passes through the planet. */
    private fun occludedByEarth(x: Double, y: Double, z: Double): Boolean {
        val dx = x - camX
        val dy = y - camY
        val dz = z - camZ
        val a = dx * dx + dy * dy + dz * dz
        if (a < 1e-9) return false
        val t = -(camX * dx + camY * dy + camZ * dz) / a
        if (t <= 0.0 || t >= 1.0) return false
        val px = camX + dx * t
        val py = camY + dy * t
        val pz = camZ + dz * t
        return px * px + py * py + pz * pz < OCCLUSION_RADIUS_SQ
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }

    companion object {
        private const val DEG_TO_RAD = kotlin.math.PI / 180.0
        private const val NEAR_KM = 2.0

        /**
         * The sphere mesh's own near plane, further out than [NEAR_KM]: a clipped vertex lands
         * exactly on it and is projected with `focal / MESH_NEAR_KM`, so a plane too close to
         * the eye would fling those corners to absurd coordinates. Nothing the mesh represents
         * is ever within six kilometres of a camera — the ground is hundreds below at minimum.
         */
        private const val MESH_NEAR_KM = 6.0

        /** Clipped faces add at most two vertices each; this bounds the per-frame growth. */
        private const val EXTRA_VERTEX_CAPACITY = 2048
        private const val MARGIN_PX = 48f

        /**
         * How many satellite dots a frame draws.
         *
         * Not a limit on how many are tracked — the field carries the whole selection, and every
         * one of them is propagated and tappable. It is a limit on draw calls: a dot is one to
         * three of them, and a frame that issues tens of thousands is a frame that misses. Two and
         * a half thousand fills a globe densely enough that adding more changes the picture very
         * little and the frame time a great deal.
         */
        private const val DRAW_BUDGET = 2_500
        private const val MAX_MODEL_QUADS = 64

        /** How long the orbit→ride POV move takes, seconds. */
        private const val RIDE_TRANSITION_SEC = 1.1f

        /** Slightly inside the surface so satellites skimming the limb do not flicker. */
        private val OCCLUSION_RADIUS_SQ =
            (EARTH_RADIUS_KM * 0.996) * (EARTH_RADIUS_KM * 0.996)

        /** Ride framing: how far ahead the gaze anchors, and the camera's slight raise. */
        private const val RIDE_AHEAD_KM = 1600.0
        private const val RIDE_CAMERA_LIFT = 0.22

        private val AtmosphereBlue = Color(0xFF8FD3FF)
        private val AtmosphereLavender = Color(0xFFBFA8FF)
        private val LimbGlow = Color(0x8CCFEAFF)
        private val RingCyan = Color(0xCC4DE3FF)
        private val BubbleFill = Color(0xE0101B33)
        private val BubbleStroke = Color(0x664DE3FF)

        // The vector surface's flat palette.
        private const val OCEAN_R = 0.180f
        private const val OCEAN_G = 0.467f
        private const val OCEAN_B = 0.878f
        private const val OCEAN_LIGHT_R = 0.341f
        private const val OCEAN_LIGHT_G = 0.659f
        private const val OCEAN_LIGHT_B = 0.961f
        private const val SHADOW_R = 0.039f
        private const val SHADOW_G = 0.063f
        private const val SHADOW_B = 0.188f
        private val LandColor = Color(0xFF54B33A)
        private val LandOutline = Color(0x593A8A26)
        private val DesertColor = Color(0xFFEFCB7A)
        private val GlacierColor = Color(0xFFF2F9FF)
        private val LakeColor = Color(0xFF2660C8)

        /** Warm sodium-lamp core, and a cooler halo so a cluster glows rather than smears. */
        private val LightCore = Color(0xFFFFE9B0)
        private val LightHalo = Color(0xFFFFB347)

        /**
         * Core radius in dp before the zoom term.
         *
         * Small on purpose: a light is a *place*, and anything bigger reads as a blob rather than
         * a city. The halo does the visual work.
         */
        private const val LIGHT_CORE_PX = 0.85

        /**
         * A ceiling on the per-frame cost.
         *
         * The three culls (horizon, terminator, brightness) already remove roughly three quarters
         * of the set on any given frame — only the night side facing the camera survives. This is
         * the backstop for the worst case, a full night hemisphere filling the screen, so the
         * frame budget cannot be blown by geometry alone.
         */
        private const val MAX_LIGHTS_PER_FRAME = 4200
    }
}

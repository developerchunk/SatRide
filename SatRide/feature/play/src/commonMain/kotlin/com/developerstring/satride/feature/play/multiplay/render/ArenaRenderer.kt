package com.developerstring.satride.feature.play.multiplay.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.feature.play.GlobeAssets
import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import com.developerstring.satride.feature.play.multiplay.engine.GamePhase
import com.developerstring.satride.feature.play.multiplay.engine.GameSession
import com.developerstring.satride.feature.play.multiplay.engine.ShipState
import com.developerstring.satride.feature.play.render.CameraPose
import com.developerstring.satride.feature.play.render.GlobeRenderer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/**
 * Draws one frame of the arena on top of the globe renderer's world pass.
 *
 * Everything here is world-space objects projected through [GlobeRenderer]'s camera, drawn in
 * a fixed order — orbs, gates, debris, the catalog, other craft, then the player's craft — with
 * the near-field decided by distance rather than by type, so a piece of debris the player is
 * about to hit is a model and one on the far side of the planet is a dot or nothing.
 */
class ArenaRenderer(private val globe: GlobeRenderer) {

    private val out = FloatArray(3)
    private val tmp = DoubleArray(3)
    private val tmp2 = DoubleArray(3)
    private val extraTemp = DoubleArray(3)
    private val streakPath = Path()

    /** Pre-measured name tags, keyed by uid, supplied by the composable. */
    var labels: Map<String, TextLayoutResult> = emptyMap()

    /** Pre-measured emote glyphs, keyed by the emote string. */
    var emoteLabels: Map<String, TextLayoutResult> = emptyMap()

    private var modelsThisFrame = 0

    @Suppress("LongParameterList")
    fun render(
        scope: DrawScope,
        session: GameSession,
        pose: CameraPose,
        assets: GlobeAssets,
        simTime: Instant,
        frameNanos: Long,
        nowMs: Long,
        localColor: Color,
    ) {
        globe.vectorEarth = assets.vectorEarth
        globe.cityLights = assets.cityLights
        globe.setTextures(assets.dayTexture, assets.nightTexture)
        globe.beginWorld(scope, simTime, pose, frameNanos)

        val density = scope.density
        val width = scope.size.width
        val height = scope.size.height
        val timeSec = frameNanos / 1e9
        modelsThisFrame = 0

        drawOrbs(scope, session, density, timeSec)
        drawGates(scope, session, density, timeSec)
        drawDebris(scope, session, density, timeSec)
        drawCatalog(scope, session, density)
        drawOtherShips(scope, session, density, nowMs)
        drawLocalShip(scope, session, density, localColor, timeSec)
        drawGuide(scope, session, width, height, density, timeSec)
        drawSpeedStreaks(scope, session, width, height, density, timeSec)
        drawSpinVignette(scope, session, width, height)
    }

    private fun drawOrbs(scope: DrawScope, session: GameSession, density: Float, timeSec: Double) {
        val track = session.track
        val next = session.localProgress.nextGate
        for (k in -1..4) {
            val i = ((next + k) % track.gateCount + track.gateCount) % track.gateCount
            if (!session.orbAvailable(i)) continue
            track.orbWorld(i, tmp)
            if (!globe.projectWorld(tmp[0], tmp[1], tmp[2], out)) continue
            val pxPerKm = globe.pixelsPerKm(out[2].toDouble())
            val r = (pxPerKm * 22.0).toFloat().coerceIn(3f * density, 26f * density)
            val pulse = 1f + 0.12f * sin(timeSec * 5.0 + i).toFloat()
            val c = Offset(out[0], out[1])
            scope.drawCircle(OrbGlow, r * 2.4f * pulse, c)
            scope.drawCircle(OrbCore, r * pulse, c)
            scope.drawCircle(Color.White, r * 0.42f, c)
        }
    }

    private fun drawGates(scope: DrawScope, session: GameSession, density: Float, timeSec: Double) {
        val track = session.track
        val next = session.localProgress.nextGate
        val finished = session.localProgress.finished
        // Farthest first so the nearer rings paint over the further ones.
        for (k in 5 downTo -1) {
            val i = ((next + k) % track.gateCount + track.gateCount) % track.gateCount
            track.gateWorld(i, tmp)
            if (globe.worldIsOccluded(tmp[0], tmp[1], tmp[2])) continue
            val axis = track.gateAxis[i]
            val isNext = k == 0 && !finished
            val alpha = when {
                isNext -> 1f
                k < 0 -> 0.25f
                else -> (0.75f - k * 0.11f).coerceAtLeast(0.2f)
            }
            val color = if (isNext) GateNext.copy(alpha = alpha) else GateFar.copy(alpha = alpha)
            val stroke = (if (isNext) 3.2f else 2.0f) * density
            val dash = if (isNext) null else dashEffect(density, timeSec)
            globe.drawRingWorld(
                scope, tmp[0], tmp[1], tmp[2], axis[0], axis[1], axis[2],
                track.gateRadiusKm, color, stroke, segments = 44, pathEffect = dash,
            )
            if (isNext) {
                // A breathing outer halo on the gate to fly through.
                val pulse = 1.0 + 0.06 * sin(timeSec * 4.0)
                globe.drawRingWorld(
                    scope, tmp[0], tmp[1], tmp[2], axis[0], axis[1], axis[2],
                    track.gateRadiusKm * pulse * 1.16, GateNext.copy(alpha = 0.35f), 6f * density, segments = 44,
                )
                globe.drawRingWorld(
                    scope, tmp[0], tmp[1], tmp[2], axis[0], axis[1], axis[2],
                    track.gateRadiusKm * 0.12, Color.White.copy(alpha = 0.5f), 1.5f * density, segments = 16,
                )
            }
        }
    }

    private fun drawDebris(scope: DrawScope, session: GameSession, density: Float, timeSec: Double) {
        val debris = session.debris
        val world = debris.world
        for (i in 0 until debris.activeCount) {
            val wx = world[i * 3]
            val wy = world[i * 3 + 1]
            val wz = world[i * 3 + 2]
            if (!globe.projectWorld(wx, wy, wz, out)) continue
            val depth = out[2].toDouble()
            // Drawn a little larger than the hit sphere: a generous silhouette makes a near miss
            // feel like one, and a hit never feels like the game cheated.
            val spanKm = debris.radiusKm[i] * 2.5
            val px = globe.pixelsPerKm(depth) * spanKm
            if (px < 2.5 * density) continue
            val c = Offset(out[0], out[1])
            if (px >= MODEL_MIN_PX * density && depth < MODEL_MAX_KM && modelsThisFrame < MAX_MODELS) {
                modelsThisFrame++
                // Nadir = toward the Earth; nose = the tumbling facing; a slow roll about the
                // nose keeps the wings from lying flat forever.
                globe.drawModelWorld(
                    scope, debris.classes[i], wx, wy, wz,
                    debris.facing[i * 3], debris.facing[i * 3 + 1], debris.facing[i * 3 + 2],
                    -wx, -wy, -wz, debris.tumble[i] * 0.7, spanKm / 4.4, DebrisTint, 0.22f,
                )
                drawThreatRing(scope, session, wx, wy, wz, debris.radiusKm[i], c, px.toFloat(), density, timeSec)
            } else {
                val r = (px * 0.5).toFloat().coerceIn(1.5f * density, 5f * density)
                scope.drawCircle(DebrisDot.copy(alpha = 0.35f), r * 2.2f, c)
                scope.drawCircle(DebrisDot, r, c)
            }
        }
    }

    /**
     * A pulsing warning ring on a piece the craft is about to hit: within a couple of seconds
     * along the velocity and inside three hit radii of the line. It is the one cue that turns
     * "something hit me" into "I saw it and dodged".
     */
    @Suppress("LongParameterList")
    private fun drawThreatRing(
        scope: DrawScope,
        session: GameSession,
        wx: Double,
        wy: Double,
        wz: Double,
        radiusKm: Double,
        centre: Offset,
        spanPx: Float,
        density: Float,
        timeSec: Double,
    ) {
        val ship = session.local
        if (ship.spinOutS > 0.0) return
        val dx = wx - ship.world[0]
        val dy = wy - ship.world[1]
        val dz = wz - ship.world[2]
        val along = dx * ship.vel[0] + dy * ship.vel[1] + dz * ship.vel[2]
        val horizon = (ship.speedKmS * THREAT_SECONDS).coerceAtLeast(120.0)
        if (along < 0.0 || along > horizon) return
        val lx = dx - ship.vel[0] * along
        val ly = dy - ship.vel[1] * along
        val lz = dz - ship.vel[2] * along
        val lateral = sqrt(lx * lx + ly * ly + lz * lz)
        val danger = (radiusKm + 4.5) * 3.0
        if (lateral > danger) return
        val urgency = (1.0 - along / horizon).toFloat().coerceIn(0.15f, 1f)
        val pulse = 1f + 0.12f * sin(timeSec * 9.0).toFloat()
        val r = (spanPx * 0.8f).coerceIn(14f * density, 90f * density) * pulse
        scope.drawCircle(
            ThreatRed.copy(alpha = 0.25f + 0.55f * urgency),
            r,
            centre,
            style = Stroke(width = (2f + 2f * urgency) * density, cap = StrokeCap.Round),
        )
        scope.drawCircle(ThreatRed.copy(alpha = 0.10f * urgency), r, centre)
    }

    private fun drawCatalog(scope: DrawScope, session: GameSession, density: Float) {
        val world = session.extraWorld
        val names = session.extraNames
        for (i in 0 until session.extraCount) {
            val wx = world[i * 3]
            val wy = world[i * 3 + 1]
            val wz = world[i * 3 + 2]
            if (!globe.projectWorld(wx, wy, wz, out)) continue
            val depth = out[2].toDouble()
            val px = globe.pixelsPerKm(depth) * session.extraRadiusKm * 2.0
            val c = Offset(out[0], out[1])
            if (px >= MODEL_MIN_PX * density && depth < MODEL_MAX_KM && modelsThisFrame < MAX_MODELS) {
                modelsThisFrame++
                // Real satellites fly along-track: nose = east-ish tangent, a fair caricature.
                extraTemp[0] = -wy
                extraTemp[1] = wx
                extraTemp[2] = 0.0
                globe.drawModelWorld(
                    scope, SatClass.OTHER, wx, wy, wz,
                    extraTemp[0], extraTemp[1], extraTemp[2], -wx, -wy, -wz, 0.0,
                    session.extraRadiusKm * 2.0 / 4.4, null, 0f,
                )
                // Its name, because that is the point of it being real.
                val label = names?.getOrNull(i)?.let { labels[it] }
                if (label != null) drawTag(scope, label, c, density, CatalogTag)
            } else {
                val r = (px * 0.5).toFloat().coerceIn(1.6f * density, 4f * density)
                scope.drawCircle(CatalogDot.copy(alpha = 0.3f), r * 2.4f, c)
                scope.drawCircle(CatalogDot, r, c)
                scope.drawCircle(Color.White, r * 0.45f, c)
            }
        }
    }

    private fun drawOtherShips(scope: DrawScope, session: GameSession, density: Float, nowMs: Long) {
        for (bot in session.bots) {
            drawShip(
                scope,
                bot.ship,
                Color(session.botColor(bot)),
                session.botUid(bot),
                density,
                faded = !bot.progress.alive,
                isIt =
                session.itUid == session.botUid(bot),
            )
        }
        for (remote in session.remotes.values) {
            val faded = remote.isSilent(nowMs) || !remote.progress.alive
            drawShip(
                scope,
                remote.ship,
                Color(remote.colorArgb),
                remote.uid,
                density,
                faded,
                session.itUid == remote.uid,
            )
            if (remote.emote.isNotEmpty() && nowMs - remote.emoteAtMs < EMOTE_MS) {
                val label = emoteLabels[remote.emote] ?: continue
                if (globe.projectWorld(remote.ship.world[0], remote.ship.world[1], remote.ship.world[2], out)) {
                    drawTag(scope, label, Offset(out[0], out[1] - 34f * density), density, EmoteFill)
                }
            }
        }
    }

    private fun drawShip(
        scope: DrawScope,
        ship: ShipState,
        color: Color,
        uid: String,
        density: Float,
        faded: Boolean,
        isIt: Boolean,
    ) {
        val wx = ship.world[0]
        val wy = ship.world[1]
        val wz = ship.world[2]
        if (!globe.projectWorld(wx, wy, wz, out)) return
        val depth = out[2].toDouble()
        val c = Offset(out[0], out[1])
        val px = globe.pixelsPerKm(depth) * SHIP_SPAN_KM
        val tint = if (faded) color.copy(alpha = 0.5f) else color
        if (px >= 4f * density && depth < MODEL_MAX_KM * 1.5) {
            drawExhaust(scope, ship, density, tint)
            globe.drawModelWorld(
                scope, ship.skin, wx, wy, wz,
                ship.hdg[0], ship.hdg[1], ship.hdg[2], -ship.pos[0], -ship.pos[1], -ship.pos[2],
                ship.rollRad, SHIP_SPAN_KM / 4.4, tint, 0.55f,
            )
        } else {
            val r = (px * 0.5).toFloat().coerceIn(2f * density, 6f * density)
            scope.drawCircle(tint.copy(alpha = 0.3f), r * 2.4f, c)
            scope.drawCircle(tint, r, c)
        }
        if (isIt) {
            // The "it" marker: a hazard ring that is impossible to miss.
            scope.drawCircle(
                ItRing,
                (px * 0.9).toFloat().coerceIn(14f * density, 60f * density),
                c,
                style = Stroke(
                    3f * density,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density)),
                ),
            )
        }
        labels[uid]?.let {
            drawTag(
                scope,
                it,
                Offset(
                    c.x,
                    c.y - (px * 0.6f).toFloat().coerceIn(18f * density, 70f * density),
                ),
                density,
                tint.copy(alpha = 0.85f),
            )
        }
    }

    private fun drawLocalShip(scope: DrawScope, session: GameSession, density: Float, color: Color, timeSec: Double) {
        val ship = session.local
        val wx = ship.world[0]
        val wy = ship.world[1]
        val wz = ship.world[2]
        if (!globe.projectWorld(wx, wy, wz, out, checkOcclusion = false)) return
        val c = Offset(out[0], out[1])
        // Immune after a hit: flicker, the universal "you cannot be hurt right now".
        if (ship.immuneS > 0.0 && ship.spinOutS <= 0.0 && ((timeSec * 12).toInt() and 1) == 0) return
        drawExhaust(scope, ship, density, color)
        globe.drawModelWorld(
            scope, ship.skin, wx, wy, wz,
            ship.hdg[0], ship.hdg[1], ship.hdg[2], -ship.pos[0], -ship.pos[1], -ship.pos[2],
            ship.rollRad, SHIP_SPAN_KM / 4.4, color, 0.55f,
        )
        if (session.mode == GameMode.TAG && session.itUid == session.localUid) {
            scope.drawCircle(
                ItRing,
                44f * density,
                c,
                style = Stroke(
                    3f * density,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(
                            8f * density,
                            6f * density,
                        ),
                        (timeSec * 30).toFloat(),
                    ),
                ),
            )
        }
        // Drift: a sideways slip streak from the tail, brighter as the charge builds.
        if (ship.drifting) {
            val charge = ship.driftCharge.coerceAtMost(2.5)
            val len = (18f + charge * 22f).toFloat() * density
            scope.drawCircle(DriftGlow.copy(alpha = (0.25 + charge * 0.2).toFloat().coerceAtMost(0.8f)), len, c)
        }
    }

    /** The engine flame: a soft cone behind the tail, longer and whiter under boost. */
    private fun drawExhaust(scope: DrawScope, ship: ShipState, density: Float, tint: Color) {
        if (ship.flame < 0.05) return
        // Tail point: behind the nose along -hdg, a little more than half the span.
        val back = SHIP_SPAN_KM * 0.55
        tmp[0] = ship.world[0] - ship.hdg[0] * back
        tmp[1] = ship.world[1] - ship.hdg[1] * back
        tmp[2] = ship.world[2] - ship.hdg[2] * back
        if (!globe.projectWorld(tmp[0], tmp[1], tmp[2], out, checkOcclusion = false)) return
        val tail = Offset(out[0], out[1])
        val flameLen = SHIP_SPAN_KM * (0.6 + ship.flame * 1.6) * (if (ship.boosting) 1.6 else 1.0)
        tmp2[0] = tmp[0] - ship.hdg[0] * flameLen
        tmp2[1] = tmp[1] - ship.hdg[1] * flameLen
        tmp2[2] = tmp[2] - ship.hdg[2] * flameLen
        if (!globe.projectWorld(tmp2[0], tmp2[1], tmp2[2], out, checkOcclusion = false)) return
        val end = Offset(out[0], out[1])
        val width = (globe.pixelsPerKm(out[2].toDouble()) * SHIP_SPAN_KM * 0.28).toFloat().coerceIn(
            2f * density,
            14f * density,
        )
        val core = if (ship.boosting) BoostFlame else tint
        scope.drawLine(core.copy(alpha = 0.35f), tail, end, strokeWidth = width * 2.2f, cap = StrokeCap.Round)
        scope.drawLine(core.copy(alpha = 0.85f), tail, end, strokeWidth = width, cap = StrokeCap.Round)
        scope.drawLine(
            Color.White.copy(alpha = 0.9f),
            tail,
            Offset((tail.x + end.x) / 2, (tail.y + end.y) / 2),
            strokeWidth =
            width * 0.4f,
            cap = StrokeCap.Round,
        )
    }

    /** A pill of text anchored above a point. */
    private fun drawTag(scope: DrawScope, label: TextLayoutResult, anchor: Offset, density: Float, fill: Color) {
        val padX = 7f * density
        val padY = 3f * density
        val w = label.size.width + padX * 2
        val h = label.size.height + padY * 2
        val left = anchor.x - w / 2f
        val top = anchor.y - h
        scope.drawRoundRect(TagFill, Offset(left, top), Size(w, h), CornerRadius(h / 2f, h / 2f))
        scope.drawRoundRect(
            fill,
            Offset(left, top),
            Size(w, h),
            CornerRadius(h / 2f, h / 2f),
            style = Stroke(1f * density),
        )
        scope.drawText(label, topLeft = Offset(left + padX, top + padY))
    }

    /**
     * The way to the next gate when it is off-screen: an arrow on the screen edge pointing at
     * where it would be. Reads the gate's projection *without* the near-plane cull so a gate
     * behind the camera still yields a direction.
     */
    private fun drawGuide(
        scope: DrawScope,
        session: GameSession,
        width: Float,
        height: Float,
        density: Float,
        timeSec: Double,
    ) {
        if (session.phase != GamePhase.RUNNING || session.localProgress.finished || !session.localProgress.alive) return
        val track = session.track
        val g = session.localProgress.nextGate
        track.gateWorld(g, tmp)
        val visible = globe.projectWorld(tmp[0], tmp[1], tmp[2], out, checkOcclusion = false)
        val onScreen = visible && out[0] > 0f && out[0] < width && out[1] > 0f && out[1] < height
        if (onScreen) return
        // Direction on screen: from the centre toward the projected point; for a point behind
        // the camera the projection flips, so use the ship-relative direction instead.
        val cx = width / 2f
        val cy = height / 2f
        var dx: Float
        var dy: Float
        if (visible) {
            dx = out[0] - cx
            dy = out[1] - cy
        } else {
            // Behind: project a point a little way toward the gate from the ship instead.
            val ship = session.local
            val vx = tmp[0] - ship.world[0]
            val vy = tmp[1] - ship.world[1]
            val vz = tmp[2] - ship.world[2]
            val m = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1.0)
            tmp2[0] = ship.world[0] + vx / m * 30.0
            tmp2[1] = ship.world[1] + vy / m * 30.0
            tmp2[2] = ship.world[2] + vz / m * 30.0
            if (!globe.projectWorld(tmp2[0], tmp2[1], tmp2[2], out, checkOcclusion = false)) return
            dx = out[0] - cx
            dy = out[1] - cy
        }
        val m = hypot(dx, dy)
        if (m < 1f) return
        dx /= m
        dy /= m
        val margin = 46f * density
        // Where the ray from the centre exits the (inset) screen rectangle.
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
        streakPath.reset()
        streakPath.moveTo(ax + cos(angle) * size, ay + sin(angle) * size)
        streakPath.lineTo(ax + cos(angle + 2.5f) * size, ay + sin(angle + 2.5f) * size)
        streakPath.lineTo(ax + cos(angle - 2.5f) * size, ay + sin(angle - 2.5f) * size)
        streakPath.close()
        scope.drawPath(streakPath, GateNext.copy(alpha = 0.35f), style = Stroke(6f * density))
        scope.drawPath(streakPath, GateNext)
    }

    /** Radial streaks under boost: cheap, and the single strongest cue of speed there is. */
    private fun drawSpeedStreaks(
        scope: DrawScope,
        session: GameSession,
        width: Float,
        height: Float,
        density: Float,
        timeSec: Double,
    ) {
        val ship = session.local
        val strength = when {
            ship.boosting -> 1f
            ship.speedKmS > 240 -> ((ship.speedKmS - 240) / 120.0).toFloat().coerceIn(0f, 0.5f)
            else -> 0f
        }
        if (strength <= 0.02f) return
        val cx = width / 2f
        val cy = height / 2f
        val count = 22
        val phase = (timeSec * 9.0).toFloat()
        for (k in 0 until count) {
            val a = k * (2f * kotlin.math.PI.toFloat() / count) + (k % 3) * 0.13f
            val r0 = (0.55f + 0.12f * sin(a * 7f + phase)) * minOf(cx, cy)
            val r1 = r0 + (60f + 90f * strength) * density
            val alpha = strength * (0.10f + 0.12f * ((sin(phase * 1.7f + k) + 1f) / 2f))
            scope.drawLine(
                Color.White.copy(alpha = alpha),
                Offset(cx + cos(a) * r0, cy + sin(a) * r0),
                Offset(cx + cos(a) * r1, cy + sin(a) * r1),
                strokeWidth = 2f * density,
                cap = StrokeCap.Round,
            )
        }
    }

    private fun drawSpinVignette(scope: DrawScope, session: GameSession, width: Float, height: Float) {
        val s = session.local.spinOutS
        if (s <= 0.0) return
        val alpha = (s / 1.15).toFloat().coerceIn(0f, 1f) * 0.55f
        scope.drawRect(
            Brush.radialGradient(
                0.45f to Color.Transparent,
                1f to CrashRed.copy(alpha = alpha),
                center = Offset(width / 2f, height / 2f),
                radius = maxOf(width, height) * 0.75f,
            ),
        )
    }

    private var dashCache: PathEffect? = null
    private var dashPhaseCache = -1

    private fun dashEffect(density: Float, timeSec: Double): PathEffect {
        val dash = 10f * density
        val gap = 8f * density
        val phaseBucket = ((timeSec * 40.0) % (dash + gap)).toInt()
        if (dashCache == null || phaseBucket != dashPhaseCache) {
            dashPhaseCache = phaseBucket
            dashCache = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -phaseBucket.toFloat())
        }
        return dashCache!!
    }

    companion object {
        const val SHIP_SPAN_KM: Double = 9.5
        private const val MODEL_MIN_PX = 4f
        private const val MODEL_MAX_KM = 2600.0
        private const val MAX_MODELS = 80
        private const val THREAT_SECONDS = 2.2
        private const val EMOTE_MS = 3200L

        private val GateNext = Color(0xFF3FE0FF)
        private val GateFar = Color(0xFF8E7BFF)
        private val OrbGlow = Color(0x66FFE14D)
        private val OrbCore = Color(0xFFFFE14D)
        private val DebrisDot = Color(0xFFB8C4DA)
        private val DebrisTint = Color(0xFF9BB0CC)
        private val CatalogDot = Color(0xFFFFC94D)
        private val CatalogTag = Color(0xFFFFC94D)
        private val TagFill = Color(0xD0101B33)
        private val EmoteFill = Color(0xFFFFFFFF)
        private val ItRing = Color(0xFFFF7E5C)
        private val BoostFlame = Color(0xFF8FD3FF)
        private val DriftGlow = Color(0xFFFF54C8)
        private val CrashRed = Color(0xFFFF3B5C)
        private val ThreatRed = Color(0xFFFF5C6E)
    }
}

package com.developerstring.satride.feature.play.render

import androidx.compose.ui.graphics.Color
import com.developerstring.satride.core.model.SatClass

/**
 * One flat-shaded face of a satellite model: four corners in the body frame, counter-clockwise.
 *
 * Body frame convention: +x along-track (direction of motion), +y cross-track, +z toward the
 * Earth (nadir). Solar wings extend along y, which is where they sit on most real buses.
 */
internal class SatQuad(val corners: DoubleArray, val color: Color) {
    init {
        require(corners.size == 12) { "A quad has 4 corners × 3 coordinates" }
    }
}

/** A procedural satellite: a handful of quads, painter-sorted and lit at draw time. */
internal class SatModel(val quads: List<SatQuad>)

/**
 * Hand-built 3D models, one per satellite class.
 *
 * Built from primitives for the same reason `SatRideIcons` draws rather than imports: no asset
 * pipeline, no format parser, crisp at any scale, and tinted by real sunlight at render time.
 * They are caricatures, not engineering drawings — a station reads as a truss with golden
 * wings, a weather bird as a bus with one blue panel — which at game scale is what matters.
 */
internal object SatelliteModels {

    // Bright, saturated, toy-like: the illustrated restyle reads as friendly hardware, not
    // engineering grey. Shading and outlines at draw time supply the depth.
    private val Silver = Color(0xFFDDE6F2)
    private val White = Color(0xFFFFFFFF)
    private val GoldFoil = Color(0xFFFFC24D)
    private val PanelBlue = Color(0xFF2EA6F7)
    private val PanelNavy = Color(0xFF1B7FD4)
    private val DarkMetal = Color(0xFF7C8CA6)
    private val Canopy = Color(0xFF4DE3FF)
    private val HullAccent = Color(0xFFFF6B5C)

    private val cache = HashMap<SatClass, SatModel>()

    /**
     * The Solar System mode's starship: a long white hull, a canopy up top, swept wings, twin
     * engines and a tail fin. Nose along +x like every model here; the wings and fin take the
     * player's tint, which is how ten identical hulls are told apart at a glance.
     */
    val starship: SatModel by lazy {
        SatModel(
            buildList {
                box(0.0, 0.0, 0.0, 1.5, 0.28, 0.24, White)
                box(1.78, 0.0, 0.04, 0.34, 0.17, 0.15, Silver)
                box(0.45, 0.0, -0.3, 0.5, 0.2, 0.1, Canopy)
                wingXY(-0.6, 0.98, 0.62, 0.7, 0.06, HullAccent)
                wingXY(-0.6, -0.98, 0.62, 0.7, 0.06, HullAccent)
                box(-1.45, 0.55, 0.06, 0.38, 0.17, 0.17, DarkMetal)
                box(-1.45, -0.55, 0.06, 0.38, 0.17, 0.17, DarkMetal)
                box(-1.15, 0.0, -0.46, 0.36, 0.05, 0.26, HullAccent)
            },
        )
    }

    fun forClass(classType: SatClass): SatModel = cache.getOrPut(classType) {
        when (classType) {
            SatClass.CREWED -> station()
            SatClass.WEATHER -> weather()
            SatClass.AMATEUR -> amateur()
            SatClass.CUBESAT -> cubesat()
            SatClass.NAVIGATION -> navigation()
            SatClass.ROCKET_BODY -> rocketBody()
            SatClass.OTHER -> generic()
        }
    }

    /** ISS-like: a long cross-track truss, pressurised modules along-track, four golden wings. */
    private fun station(): SatModel = SatModel(
        buildList {
            box(0.0, 0.0, 0.0, 0.16, 2.15, 0.12, Silver)
            box(0.0, 0.0, 0.0, 1.35, 0.26, 0.26, White)
            box(0.0, 0.0, -0.42, 0.55, 0.22, 0.22, White)
            for (side in intArrayOf(-1, 1)) {
                wingXY(0.0, side * 1.05, 0.75, 0.42, 0.0, GoldFoil)
                wingXY(0.0, side * 1.85, 0.75, 0.42, 0.0, GoldFoil)
            }
        },
    )

    /** Polar-orbiting weather bus: white body, one long blue wing, nadir sensor block. */
    private fun weather(): SatModel = SatModel(
        buildList {
            box(0.0, 0.0, 0.0, 0.5, 0.45, 0.7, White)
            box(0.0, 0.0, 0.85, 0.28, 0.28, 0.16, DarkMetal)
            wingXY(0.0, 1.35, 0.45, 0.78, 0.0, PanelBlue)
            box(0.0, -0.62, -0.3, 0.045, 0.2, 0.045, Silver)
        },
    )

    /** Ham bird: gold-foiled body, short wings, whip antennas. */
    private fun amateur(): SatModel = SatModel(
        buildList {
            box(0.0, 0.0, 0.0, 0.5, 0.5, 0.45, GoldFoil)
            wingXY(0.0, 1.05, 0.42, 0.5, 0.0, PanelBlue)
            wingXY(0.0, -1.05, 0.42, 0.5, 0.0, PanelBlue)
            box(0.3, 0.3, -0.85, 0.03, 0.03, 0.42, Silver)
            box(-0.3, -0.3, -0.75, 0.03, 0.03, 0.32, Silver)
        },
    )

    /** A 3U brick, panel-skinned, with one deployed antenna. */
    private fun cubesat(): SatModel = SatModel(
        buildList {
            box(0.0, 0.0, 0.0, 0.34, 0.34, 0.6, PanelNavy)
            box(0.0, 0.0, -0.66, 0.3, 0.3, 0.06, GoldFoil)
            box(0.2, 0.0, -0.95, 0.025, 0.025, 0.28, Silver)
        },
    )

    /** Navigation bus: boxy body and two big blue wings, the classic GNSS silhouette. */
    private fun navigation(): SatModel = SatModel(
        buildList {
            box(0.0, 0.0, 0.0, 0.6, 0.5, 0.5, Silver)
            box(0.0, 0.0, 0.62, 0.3, 0.3, 0.12, DarkMetal)
            wingXY(0.0, 1.45, 0.55, 0.85, 0.0, PanelNavy)
            wingXY(0.0, -1.45, 0.55, 0.85, 0.0, PanelNavy)
        },
    )

    /** A spent stage: one long grey cylinder, approximated by a box with dark ends. */
    private fun rocketBody(): SatModel = SatModel(
        buildList {
            box(0.0, 0.0, 0.0, 1.35, 0.38, 0.38, Silver)
            box(1.42, 0.0, 0.0, 0.07, 0.3, 0.3, DarkMetal)
            box(-1.42, 0.0, 0.0, 0.07, 0.3, 0.3, DarkMetal)
        },
    )

    /** Everything else: a generic bus with two wings. */
    private fun generic(): SatModel = SatModel(
        buildList {
            box(0.0, 0.0, 0.0, 0.55, 0.42, 0.42, Silver)
            wingXY(0.0, 1.15, 0.45, 0.62, 0.0, PanelNavy)
            wingXY(0.0, -1.15, 0.45, 0.62, 0.0, PanelNavy)
        },
    )

    /** Six quads of an axis-aligned box centred at (cx,cy,cz) with half-extents (hx,hy,hz). */
    private fun MutableList<SatQuad>.box(
        cx: Double,
        cy: Double,
        cz: Double,
        hx: Double,
        hy: Double,
        hz: Double,
        color: Color,
    ) {
        val x0 = cx - hx
        val x1 = cx + hx
        val y0 = cy - hy
        val y1 = cy + hy
        val z0 = cz - hz
        val z1 = cz + hz
        add(SatQuad(doubleArrayOf(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0), color))
        add(SatQuad(doubleArrayOf(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1), color))
        add(SatQuad(doubleArrayOf(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1), color))
        add(SatQuad(doubleArrayOf(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1), color))
        add(SatQuad(doubleArrayOf(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1), color))
        add(SatQuad(doubleArrayOf(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1), color))
    }

    /** A solar wing lying in the x–y plane at height [z], centred at (cx, cy). */
    private fun MutableList<SatQuad>.wingXY(
        cx: Double,
        cy: Double,
        halfX: Double,
        halfY: Double,
        z: Double,
        color: Color,
    ) {
        add(
            SatQuad(
                doubleArrayOf(
                    cx - halfX, cy - halfY, z,
                    cx + halfX, cy - halfY, z,
                    cx + halfX, cy + halfY, z,
                    cx - halfX, cy + halfY, z,
                ),
                color,
            ),
        )
    }
}

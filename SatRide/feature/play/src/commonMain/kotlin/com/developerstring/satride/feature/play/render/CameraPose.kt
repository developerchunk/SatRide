package com.developerstring.satride.feature.play.render

/**
 * An explicit camera: where it is, what it looks at, which way is up, and how wide the lens is.
 *
 * All ECEF kilometres. Mutable and reused frame to frame so a chase camera can be written into
 * it without allocating; the renderer copies what it needs in [GlobeRenderer.beginWorld].
 */
class CameraPose {
    var x: Double = 0.0
    var y: Double = 0.0
    var z: Double = 0.0
    var targetX: Double = 0.0
    var targetY: Double = 0.0
    var targetZ: Double = 0.0
    var upX: Double = 0.0
    var upY: Double = 0.0
    var upZ: Double = 1.0

    /** Vertical field of view. Wider than the globe's 45°: a race wants peripheral vision. */
    var fovDeg: Double = 62.0

    fun set(
        x: Double,
        y: Double,
        z: Double,
        targetX: Double,
        targetY: Double,
        targetZ: Double,
        upX: Double,
        upY: Double,
        upZ: Double,
    ) {
        this.x = x
        this.y = y
        this.z = z
        this.targetX = targetX
        this.targetY = targetY
        this.targetZ = targetZ
        this.upX = upX
        this.upY = upY
        this.upZ = upZ
    }
}

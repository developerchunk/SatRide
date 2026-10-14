package com.developerstring.satride.core.sensors

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Desktop has no magnetometer.
 *
 * The attitude source reports itself unavailable rather than pretending, which is what drives the
 * sky view into planetarium mode — drag to look around, pinch or scroll to zoom. That is not a
 * degraded experience bolted on for desktop: it is the same path a phone user takes when their
 * compass will not calibrate, which is what makes desktop a genuinely useful place to develop it.
 *
 * Location is a different story — a desktop does have one, roughly — and lives in [IpGeolocationSource].
 */
internal class DesktopAttitudeSource : PlatformAttitudeSource {
    override fun samples(): Flow<RawAttitudeSample> = emptyFlow()
    override val northReference: NorthReference = NorthReference.TRUE
    override val isAvailable: Boolean = false
}

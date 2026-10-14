package com.developerstring.satride.core.network.dto

import com.developerstring.satride.core.model.Transmitter
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.model.TxStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One transmitter record from the SatNOGS database.
 *
 * Almost every field is nullable because the database is crowd-maintained and genuinely
 * incomplete in places — a transmitter with no mode, no baud rate and a citation of
 * "CITATION NEEDED" is a real record, not a parse failure.
 */
@Serializable
data class TransmitterDto(
    val uuid: String,
    val description: String = "",
    val alive: Boolean = true,
    /** `Transmitter`, `Transceiver` or `Transponder`. */
    val type: String? = null,
    @SerialName("uplink_low") val uplinkLow: Long? = null,
    @SerialName("uplink_high") val uplinkHigh: Long? = null,
    @SerialName("downlink_low") val downlinkLow: Long? = null,
    @SerialName("downlink_high") val downlinkHigh: Long? = null,
    val mode: String? = null,
    val invert: Boolean = false,
    val baud: Double? = null,
    @SerialName("norad_cat_id") val noradCatId: Int? = null,
    val status: String? = null,
    val service: String? = null,
    val updated: String? = null,
) {

    /**
     * True when this record describes something SatRide can receive.
     *
     * Uplink-only entries are dropped: a receiver has nothing to do with them, and showing a
     * frequency the user cannot listen to is worse than showing nothing. Records without a
     * catalog number are dropped too, since there is no satellite to attach them to.
     */
    val isReceivable: Boolean
        get() = downlinkLow != null && downlinkLow > 0L && noradCatId != null

    /**
     * Maps to the domain type, or `null` when [isReceivable] is false.
     *
     * Baud arrives as a floating-point number (1200.0, 9600.0) and is rounded rather than
     * truncated — 1199.9999 is a 1200-baud link.
     */
    fun toTransmitter(): Transmitter? {
        val downlink = downlinkLow ?: return null
        val norad = noradCatId ?: return null
        if (!isReceivable) return null

        return Transmitter(
            id = uuid,
            noradId = norad,
            description = description.trim(),
            downlinkLowHz = downlink,
            // A "high" equal to or below "low" is a single carrier recorded oddly, not a band.
            downlinkHighHz = downlinkHigh?.takeIf { it > downlink },
            mode = TxMode.fromSatNogs(mode),
            baud = baud?.takeIf { it > 0.0 }?.let { kotlin.math.round(it).toInt() },
            status = when (status?.lowercase()) {
                "active" -> TxStatus.ACTIVE
                "inactive" -> TxStatus.INACTIVE
                else -> if (alive) TxStatus.ACTIVE else TxStatus.INACTIVE
            },
            invert = invert,
            service = service?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) },
        )
    }
}

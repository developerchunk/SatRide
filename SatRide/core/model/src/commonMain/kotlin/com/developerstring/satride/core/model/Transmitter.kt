package com.developerstring.satride.core.model

/**
 * Modulation of a satellite downlink, as catalogued by SatNOGS.
 *
 * This mirrors the vocabulary the SatNOGS database publishes (see docs/plan/07-references.md)
 * rather than a tidier taxonomy of our own, because the whole point is to classify their strings.
 * Unknown values map to [UNKNOWN] instead of failing: the database gains modes faster than we
 * ship releases, and a satellite listed with a mode we have never seen should still be tunable.
 */
enum class TxMode(
    /** The exact string SatNOGS uses, where it is not simply the enum name. */
    val satnogsName: String? = null,
) {
    FSK4("4FSK"),
    QAM64("64-QAM"),
    AFSK,
    AFSK_TUBIX10("AFSK TUBiX10"),
    AHRPT,
    AM,
    APT,
    ASK,
    BPSK,
    BPSK_PMT_A3("BPSK PMT-A3"),
    CERTO,
    CW,
    DATV,
    DBPSK,
    DOKA,
    DPSK,
    DQPSK,
    DSB,
    DSTAR,
    DUV,
    DVB_S2("DVB-S2"),
    FFSK,
    FM,
    FMN,
    FSK,
    FSK_AX100_MODE_5("FSK AX.100 Mode 5"),
    FSK_AX100_MODE_6("FSK AX.100 Mode 6"),
    FSK_AX25_G3RUH("FSK AX.25 G3RUH"),
    FT8,
    GENESIS_FSK("GENESIS FSK"),
    GFSK,
    GFSK_PKST("GFSK Pkst"),
    GFSK_RKTR("GFSK Rktr"),
    GFSK_BPSK("GFSK/BPSK"),
    GMSK,
    GMSK_USP("GMSK USP"),
    HRPT,
    LORA("LoRa"),
    LRPT,
    LSB,
    MFSK,
    MSK,
    MSK_AX100_MODE_5("MSK AX.100 Mode 5"),
    MSK_AX100_MODE_6("MSK AX.100 Mode 6"),
    OFDM,
    OQPSK,
    PPM,
    PSK,
    PSK31,
    PSK63,
    QPSK,
    QPSK31,
    QPSK63,
    SIDLOC,
    SQPSK,
    SSDV,
    SSTV,
    USB,
    WSJT,
    UNKNOWN,
    ;

    /**
     * Which decoder SatRide can actually run against this mode.
     *
     * Deliberately conservative. It would be easy to claim [DecoderKind.TELEMETRY] for the whole
     * FSK family, but SatRide only decodes AFSK/AX.25 and G3RUH — promising more would mean a user
     * taps "decode", sits through a ten-minute pass and gets nothing. Everything we cannot decode
     * still gets demodulated audio, so no satellite is a dead end: the user hears the signal even
     * when we cannot turn it into a picture or a frame.
     */
    val decoder: DecoderKind
        get() = when (this) {
            FM, FMN, AM, USB, LSB, DSB, CW -> DecoderKind.AUDIO
            SSTV -> DecoderKind.SSTV
            AFSK, AFSK_TUBIX10, FSK_AX25_G3RUH, FFSK -> DecoderKind.TELEMETRY
            LRPT -> DecoderKind.LRPT
            else -> DecoderKind.LISTEN_ONLY
        }

    companion object {
        private val byWireName: Map<String, TxMode> =
            entries.associateBy { (it.satnogsName ?: it.name).uppercase() }

        /**
         * Maps a SatNOGS `mode` string onto the enum.
         *
         * Matched case-insensitively so a change of capitalisation upstream cannot silently turn
         * a decodable satellite into [UNKNOWN]. A null or unrecognised value gives [UNKNOWN]
         * rather than an error — see the class comment.
         */
        fun fromSatNogs(raw: String?): TxMode {
            val key = raw?.trim()?.uppercase().orEmpty()
            if (key.isEmpty()) return UNKNOWN
            return byWireName[key] ?: UNKNOWN
        }
    }
}

/** Decoders SatRide can run against a downlink. */
enum class DecoderKind { AUDIO, SSTV, TELEMETRY, LRPT, LISTEN_ONLY }

/** Whether a transmitter is believed to be working. */
enum class TxStatus { ACTIVE, INACTIVE, UNKNOWN }

/**
 * One downlink of a satellite.
 *
 * Frequencies are integer hertz throughout SatRide. Doppler shift at UHF is only a few kHz on a
 * carrier of hundreds of MHz, and floating-point hertz would lose the precision that the
 * correction depends on.
 */
data class Transmitter(
    val id: String,
    val noradId: Int,
    val description: String,
    val downlinkLowHz: Long,
    val downlinkHighHz: Long? = null,
    val mode: TxMode = TxMode.UNKNOWN,
    val baud: Int? = null,
    val status: TxStatus = TxStatus.UNKNOWN,
    /** Inverting transponder: Doppler correction must be applied with the opposite sign. */
    val invert: Boolean = false,
    /** ITU service category as SatNOGS records it — "Amateur", "Meteorological", and so on. */
    val service: String? = null,
) {
    /** Centre of the downlink; for a single-frequency transmitter this is simply that value. */
    val centerHz: Long get() = downlinkHighHz?.let { (downlinkLowHz + it) / 2 } ?: downlinkLowHz

    /** True for a transponder passband rather than a single carrier. */
    val isWideband: Boolean get() = downlinkHighHz != null && downlinkHighHz > downlinkLowHz
}

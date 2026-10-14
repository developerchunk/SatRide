package com.developerstring.satride.core.network

/**
 * Credit that has to appear in the app.
 *
 * SatNOGS licenses its database CC BY-SA 4.0, so attribution is a licence condition rather than
 * a courtesy — shipping without it would make the release non-compliant. CelesTrak and the
 * amateur-radio sources ask to be credited and cost us nothing to credit. These constants are
 * rendered by the About screen in P11.4; keeping them here means the module that consumes a
 * source is the module that declares the obligation.
 */
object DataAttribution {

    data class Credit(val source: String, val url: String, val licence: String, val required: Boolean)

    val SATNOGS: Credit = Credit(
        source = "SatNOGS DB",
        url = "https://db.satnogs.org",
        licence = "CC BY-SA 4.0",
        required = true,
    )

    val CELESTRAK: Credit = Credit(
        source = "CelesTrak",
        url = "https://celestrak.org",
        licence = "Courtesy of T.S. Kelso",
        required = false,
    )

    val ARISS: Credit = Credit(
        source = "ARISS",
        url = "https://live.ariss.org",
        licence = "Courtesy credit",
        required = false,
    )

    val AMSAT: Credit = Credit(
        source = "AMSAT",
        url = "https://amsat.org",
        licence = "Courtesy credit",
        required = false,
    )

    /** Every credit the data layer incurs, in the order the About screen shows them. */
    val all: List<Credit> = listOf(SATNOGS, CELESTRAK, ARISS, AMSAT)
}

package com.developerstring.satride.core.network

/**
 * A CelesTrak General Perturbations group.
 *
 * Each is a separate query because the alternative — always pulling `active` — is several
 * megabytes of mostly debris and defunct hardware. The first block is what a routine refresh
 * fetches and covers everything most people point a phone or an antenna at; the rest exist so a
 * user who wants a particular constellation, or the entire sky, can have exactly that and pay for
 * exactly that.
 */
enum class GpGroup(
    val queryValue: String,
    val label: String,
    /** A one-line description for the group picker. */
    val blurb: String = "",
    /** Roughly how many objects the group carries, for the download-size estimate. */
    val approximateSize: Int = 200,
) {
    STATIONS("stations", "Space stations", "The ISS, Tiangong and everything docked to them", 90),
    WEATHER("weather", "Weather", "NOAA, MetOp, Meteor, GOES and the rest of the met fleet", 90),
    AMATEUR("amateur", "Amateur radio", "Satellites with a downlink you are licensed to hear", 130),
    CUBESAT("cubesat", "CubeSats", "The small-satellite population", 1_600),
    VISUAL("visual", "Brightest", "The hundred or so objects visible to the naked eye", 170),

    // --- Everything below is opt-in. -----------------------------------------------------------

    SCIENCE("science", "Space science", "Observatories, probes and research payloads", 80),
    GNSS("gnss", "Navigation", "GPS, Galileo, GLONASS, BeiDou and the augmentation systems", 160),
    GEO("geo", "Geostationary", "The ring at 35,786 km — communications and met, always up", 600),
    STARLINK("starlink", "Starlink", "SpaceX's constellation. Large, and it moves fast", 7_000),
    ONEWEB("oneweb", "OneWeb", "The other big broadband constellation", 650),
    IRIDIUM("iridium-NEXT", "Iridium NEXT", "The flare-era constellation's successor", 90),
    GLOBALSTAR("globalstar", "Globalstar", "Low-orbit voice and data", 60),
    ORBCOMM("orbcomm", "Orbcomm", "Machine-to-machine messaging", 60),
    ENGINEERING("engineering", "Engineering", "Technology demonstrators and calibration targets", 70),
    EDUCATION("education", "Education", "University-built satellites", 40),
    MILITARY("military", "Military", "Publicly catalogued defence payloads", 250),
    RESOURCE("resource", "Earth resources", "Imaging and remote sensing", 90),
    LAST_30_DAYS("last-30-days", "Launched recently", "Everything catalogued in the last 30 days", 300),

    /** The full catalog. Large, and only fetched when the user explicitly asks for everything. */
    ACTIVE("active", "All active satellites", "Every active object CelesTrak tracks", 11_000),
    ;

    companion object {
        /** What a routine background refresh pulls. */
        val defaults: Set<GpGroup> = setOf(STATIONS, WEATHER, AMATEUR, CUBESAT, VISUAL)

        /**
         * Everything, for the user who asked to track the whole sky.
         *
         * The five defaults plus [ACTIVE], and deliberately **not** every group in this enum.
         *
         * [ACTIVE] already contains Starlink, OneWeb, the GNSS constellations, the geostationary
         * ring and the rest — so fetching those groups alongside it re-downloads the same sixteen
         * thousand objects several times over. The only thing the per-group requests add is
         * classification, and the classes SatRide actually draws are exactly the five defaults;
         * navigation is recognised by name, and a Starlink is "other" either way.
         *
         * That is not merely wasteful. Asking CelesTrak for nineteen groups totalling forty-odd
         * megabytes, four at a time, gets the large ones answered with **403** — measured, on a
         * second run minutes after the first. Their servers are run by volunteers and they are
         * right to push back. Six requests is the polite version of the same result.
         */
        val everything: Set<GpGroup> = defaults + ACTIVE

        /** The groups offered on the tracking screen, in the order they are shown. */
        val selectable: List<GpGroup> = entries.toList()
    }
}

/** How a source serves its element sets. */
enum class TleFormat {
    /** CelesTrak's OMM JSON: mean elements, rendered back to TLE text on the way in. */
    GP_JSON,

    /** Classic text: bare two-line pairs or three-line sets with a name. */
    TLE_TEXT,
}

/**
 * One place element sets can be fetched from.
 *
 * Modelling sources as data rather than as separate code paths means adding McCants or R4UAB in
 * v1.1 is a list entry, and it lets the refresh logic treat "CelesTrak is down, use SatNOGS" as
 * ordinary iteration rather than a special case. The approach is Look4Sat's; the merge rule that
 * consumes it is ours.
 */
data class TleSource(
    val id: String,
    val label: String,
    val url: String,
    val format: TleFormat,
    /**
     * Lower wins ties. Only used when two sources carry the *same* epoch for a satellite —
     * a newer epoch always beats a higher-priority source, whatever the priority.
     */
    val priority: Int,
    val group: GpGroup? = null,
    val credit: DataAttribution.Credit,
) {
    companion object {

        fun celesTrak(group: GpGroup): TleSource = TleSource(
            id = "celestrak-${group.queryValue}",
            label = "CelesTrak · ${group.label}",
            url = "https://celestrak.org/NORAD/elements/gp.php?GROUP=${group.queryValue}&FORMAT=json",
            format = TleFormat.GP_JSON,
            priority = 0,
            group = group,
            credit = DataAttribution.CELESTRAK,
        )

        /**
         * ARISS publishes the ISS element set on its own, and refreshes it more eagerly than the
         * general catalog. The ISS is the single satellite most users open the app for, so it is
         * worth one extra request.
         */
        val ARISS_ISS: TleSource = TleSource(
            id = "ariss-iss",
            label = "ARISS · ISS",
            url = "https://live.ariss.org/iss.txt",
            format = TleFormat.TLE_TEXT,
            priority = 0,
            credit = DataAttribution.ARISS,
        )

        /**
         * The NOAA APT satellites, fetched individually by catalog number.
         *
         * CelesTrak has retired its `noaa` group and the `weather` group only carries the modern
         * JPSS birds, so NOAA 15, 18 and 19 appear in no group at all. They are also the three
         * satellites most likely to be a new user's first decoded image — 137 MHz APT, receivable
         * with the cheapest possible antenna — so losing them is not an option. Their catalog
         * numbers have been fixed since launch, which is what makes hard-coding them safe.
         */
        val APT_WEATHER: List<TleSource> = listOf(
            25338 to "NOAA 15",
            28654 to "NOAA 18",
            33591 to "NOAA 19",
        ).map { (noradId, name) ->
            TleSource(
                id = "celestrak-catnr-$noradId",
                label = "CelesTrak · $name",
                url = "https://celestrak.org/NORAD/elements/gp.php?CATNR=$noradId&FORMAT=json",
                format = TleFormat.GP_JSON,
                priority = 0,
                group = GpGroup.WEATHER,
                credit = DataAttribution.CELESTRAK,
            )
        }

        /** Fallback for when CelesTrak is unreachable; same database as the transmitter list. */
        val SATNOGS: TleSource = TleSource(
            id = "satnogs-tle",
            label = "SatNOGS",
            url = "https://db.satnogs.org/api/tle/?format=3le",
            format = TleFormat.TLE_TEXT,
            priority = 2,
            credit = DataAttribution.SATNOGS,
        )

        /** Amateur-satellite redundancy; small, and often fresher than CelesTrak for ham birds. */
        val AMSAT: TleSource = TleSource(
            id = "amsat-nasabare",
            label = "AMSAT",
            url = "https://amsat.org/tle/current/nasabare.txt",
            format = TleFormat.TLE_TEXT,
            priority = 1,
            credit = DataAttribution.AMSAT,
        )

        /**
         * The sources a routine refresh uses, in the order they are tried.
         *
         * SatNOGS is not in this list: it is the fallback, requested only when the CelesTrak
         * groups fail, because fetching it every time would pull a quarter-megabyte of element
         * sets we already have.
         */
        fun defaults(groups: Set<GpGroup> = GpGroup.defaults): List<TleSource> =
            groups.map(::celesTrak) + APT_WEATHER + ARISS_ISS + AMSAT

        val fallback: TleSource = SATNOGS
    }
}

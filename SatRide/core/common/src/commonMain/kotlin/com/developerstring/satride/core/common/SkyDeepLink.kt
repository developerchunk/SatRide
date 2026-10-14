package com.developerstring.satride.core.common

/**
 * The one URL SatRide answers to.
 *
 * `satride://sky/detail/{noradId}?guide=true` — the sky view, already guiding the user to a
 * satellite. A pass notification opens it, and so does anything else that wants to hand a
 * satellite to the AR view.
 *
 * Parsing lives here, in common code, rather than in each platform's entry point. A URL scheme is
 * exactly the kind of thing that gets implemented twice and diverges, and the failure is silent:
 * the notification simply opens the app on the wrong screen, on one platform, for one person.
 */
object SkyDeepLink {

    const val SCHEME: String = "satride"

    /** The Android intent-filter path, kept beside the parser so the two cannot drift. */
    const val HOST: String = "sky"

    /** Builds the link a notification tap should open. */
    fun guide(noradId: Int): String = "$SCHEME://$HOST/detail/$noradId?guide=true"

    /**
     * Reads a link, or answers null for anything that is not one of ours.
     *
     * Hand-parsed rather than routed through a URL type: `kotlinx.serialization` has none, the
     * platforms disagree about theirs, and the grammar here is three segments and one flag.
     */
    fun parse(uri: String?): SkyDeepLinkTarget? {
        if (uri == null) return null
        val withoutScheme = uri.trim().removePrefix("$SCHEME://").takeIf { it != uri.trim() } ?: return null

        val path = withoutScheme.substringBefore('?')
        val query = withoutScheme.substringAfter('?', missingDelimiterValue = "")
        val segments = path.split('/').filter { it.isNotEmpty() }

        if (segments.size < 3) return null
        if (segments[0] != HOST || segments[1] != "detail") return null
        val noradId = segments[2].toIntOrNull() ?: return null

        // Guiding is the default for a link that names a satellite: everything that produces one
        // — a pass alert, a share — means "show me this one", and arriving on a sky full of dots
        // with nothing highlighted would be a worse answer than the link asked for.
        val guide = query.split('&')
            .firstOrNull { it.startsWith("guide=") }
            ?.removePrefix("guide=")
            ?.let { it.equals("true", ignoreCase = true) || it == "1" }
            ?: true

        return SkyDeepLinkTarget(noradId = noradId, guide = guide)
    }
}

/** A parsed [SkyDeepLink]: which satellite, and whether to lead the user to it. */
data class SkyDeepLinkTarget(val noradId: Int, val guide: Boolean)

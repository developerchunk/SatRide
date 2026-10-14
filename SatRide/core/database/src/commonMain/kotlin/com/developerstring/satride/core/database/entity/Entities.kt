package com.developerstring.satride.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tracked object, independent of its orbital elements.
 *
 * Separate from [TleEntity] because the two have completely different lifetimes: a satellite's
 * identity is stable for its whole mission, while its element set is replaced every few days. One
 * combined table would rewrite every satellite's name and class on every refresh.
 */
@Entity(
    tableName = "satellites",
    indices = [Index("tracked"), Index("classType")],
)
data class SatelliteEntity(
    @PrimaryKey val noradId: Int,
    val name: String,
    val classType: String,
    val launchDate: String? = null,
    val countryCode: String? = null,
    val internationalDesignator: String? = null,
    /** In the bundled starter set, so it is available before the first successful refresh. */
    val curated: Boolean = false,
    /**
     * The user has asked for this object to be tracked.
     *
     * A column on the satellite rather than a join table because the question "is this tracked"
     * is asked of the whole catalog at once — the tracking engine reads the selected ids on every
     * sweep, and the manage screen renders a checkbox per row over tens of thousands of rows. A
     * separate table would turn both into a join; a column with a partial index makes the first a
     * single indexed scan and the second free.
     */
    val tracked: Boolean = false,
)

/**
 * The current element set for a satellite.
 *
 * One row per satellite, not one per element set: SatRide only ever propagates from the newest, and
 * keeping history would grow without bound for data that becomes useless within days.
 *
 * [epochMillis] is indexed because staleness — "how old is the oldest thing I am tracking" — is a
 * query the UI runs on every catalog render.
 */
@Entity(
    tableName = "tles",
    indices = [Index("epochMillis")],
)
data class TleEntity(
    @PrimaryKey val noradId: Int,
    val name: String,
    val line1: String,
    val line2: String,
    val epochMillis: Long,
    /** When SatRide downloaded it, as distinct from the epoch the elements describe. */
    val fetchedAtMillis: Long,
    /** Which [com.developerstring.satride.core.model.Tle] source won the merge, for diagnostics. */
    val sourceId: String,
)

/**
 * One downlink of a satellite, from the SatNOGS database.
 *
 * [noradId] is indexed: the satellite detail and radio screens both look transmitters up by
 * satellite, and that is the only access pattern.
 */
@Entity(
    tableName = "transmitters",
    indices = [Index("noradId")],
)
data class TransmitterEntity(
    @PrimaryKey val id: String,
    val noradId: Int,
    val description: String,
    val downlinkLowHz: Long,
    val downlinkHighHz: Long? = null,
    val mode: String,
    val baud: Int? = null,
    val status: String,
    val invert: Boolean = false,
    val service: String? = null,
)

/**
 * A computed pass, cached so a screen open does not re-run the propagator.
 *
 * The primary key is the triple that fully determines the answer: which satellite, from where,
 * using which element set. Change any of the three and the cached result is not merely stale but
 * wrong, so it cannot be silently reused — a key collision is impossible rather than unlikely.
 *
 * [observerGeohash] is a coarse cell (see `Geohash.PASS_CACHE_PRECISION`), which is what stops
 * ordinary GPS jitter from invalidating the cache every few seconds.
 */
@Entity(
    tableName = "pass_cache",
    primaryKeys = ["noradId", "observerGeohash", "tleEpochMillis", "aosMillis"],
    indices = [Index("aosMillis"), Index("observerGeohash", "tleEpochMillis")],
)
data class PassCacheEntity(
    val noradId: Int,
    val observerGeohash: String,
    val tleEpochMillis: Long,
    val aosMillis: Long,
    val tcaMillis: Long,
    val losMillis: Long,
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Double,
    val losAzimuthDeg: Double,
    /** The sampled arc, so a polar plot can be drawn without propagating again. */
    val arcJson: String,
    val computedAtMillis: Long,
)

/**
 * A reminder the user asked for, about one pass.
 *
 * Denormalised on purpose: it copies the pass's numbers instead of referencing a [PassCacheEntity]
 * row. Cached passes are pruned the moment their element set is superseded — daily — so a foreign
 * key would take every alert with it. The alert has to outlive the prediction it was made from,
 * because that is exactly what the reconcile step exists to fix up.
 *
 * [aosMillis] is indexed: every read of this table is either "what is coming up" or "what has
 * already happened and can be forgotten".
 */
@Entity(
    tableName = "pass_alerts",
    indices = [Index("aosMillis")],
)
data class PassAlertEntity(
    /** `noradId@originalAosMillis` — stable across reschedules. */
    @PrimaryKey val id: String,
    val noradId: Int,
    val satelliteName: String,
    val aosMillis: Long,
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Double,
    val leadTimeSeconds: Long,
    val createdAtMillis: Long,
)

/**
 * Metadata for something the decoders produced — an SSTV image, a telemetry log, a recording.
 *
 * The schema lands now, in the same migration as everything else, so P10 adds rows rather than a
 * table. The payload itself is a file; only the pointer and the context live here.
 */
@Entity(
    tableName = "artifacts",
    indices = [Index("noradId"), Index("createdAtMillis")],
)
data class ArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noradId: Int,
    val satelliteName: String,
    /** `SSTV_IMAGE`, `TELEMETRY_LOG`, `AUDIO_RECORDING`, `LRPT_IMAGE`. */
    val kind: String,
    val filePath: String,
    val createdAtMillis: Long,
    val frequencyHz: Long? = null,
    val mode: String? = null,
    val maxElevationDeg: Double? = null,
    val notes: String? = null,
)

/** A radio session: what was tuned, for how long, and what came of it. */
@Entity(
    tableName = "observations",
    indices = [Index("startedAtMillis")],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noradId: Int,
    val satelliteName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val frequencyHz: Long,
    val mode: String,
    val sourceKind: String,
    val maxElevationDeg: Double? = null,
    val artifactCount: Int = 0,
)

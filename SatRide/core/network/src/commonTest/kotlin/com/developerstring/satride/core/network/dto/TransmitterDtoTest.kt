package com.developerstring.satride.core.network.dto

import com.developerstring.satride.core.model.DecoderKind
import com.developerstring.satride.core.model.TxMode
import com.developerstring.satride.core.model.TxStatus
import com.developerstring.satride.core.network.SatRideHttpClient
import com.developerstring.satride.core.network.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransmitterDtoTest {

    private val dtos: List<TransmitterDto> by lazy {
        SatRideHttpClient.json.decodeFromString<List<TransmitterDto>>(
            TestFixtures.read("satnogs_transmitters_sample.json"),
        )
    }

    @Test
    fun parsesTheRecordedSatNogsResponse() {
        assertTrue(dtos.size >= 50, "expected the recorded sample, got ${dtos.size}")
    }

    @Test
    fun dropsRecordsWithNothingToReceive() {
        val withoutDownlink = dtos.filter { it.downlinkLow == null || it.downlinkLow == 0L }
        assertTrue(withoutDownlink.isNotEmpty(), "fixture no longer covers the no-downlink case")

        withoutDownlink.forEach {
            assertNull(it.toTransmitter(), "a record with no downlink must not become a Transmitter")
        }
    }

    /**
     * `norad_cat_id` is nullable in the SatNOGS schema even though every record in the current
     * capture has one, so this case is written out rather than taken from the fixture. A record
     * with no catalog number has no satellite to attach to and nothing to point an antenna at.
     */
    @Test
    fun dropsRecordsWithNoSatelliteToAttachTo() {
        val orphan = SatRideHttpClient.json.decodeFromString<TransmitterDto>(
            """
            {"uuid":"abc","description":"Orphan","downlink_low":437500000,
             "mode":"FM","norad_cat_id":null,"status":"active","alive":true}
            """.trimIndent(),
        )

        assertNull(orphan.toTransmitter())
    }

    /**
     * The database gains modes faster than we ship. Every one of the 50 distinct mode strings in
     * the recorded sample must map to something, and an unfamiliar one must land on UNKNOWN
     * rather than throw — an app that crashes when SatNOGS adds a modulation is an app that
     * breaks without a release.
     */
    @Test
    fun mapsEveryModeStringInTheLiveDatabase() {
        val unmapped = dtos
            .mapNotNull { it.mode }
            .distinct()
            .filter { TxMode.fromSatNogs(it) == TxMode.UNKNOWN && it != "UNKNOWN" }

        assertTrue(
            unmapped.isEmpty(),
            "SatNOGS modes missing from the TxMode taxonomy: $unmapped",
        )
    }

    @Test
    fun unknownAndAbsentModesAreNotFailures() {
        assertEquals(TxMode.UNKNOWN, TxMode.fromSatNogs(null))
        assertEquals(TxMode.UNKNOWN, TxMode.fromSatNogs(""))
        assertEquals(TxMode.UNKNOWN, TxMode.fromSatNogs("SOME MODE INVENTED IN 2027"))
    }

    @Test
    fun matchesModeStringsRegardlessOfCase() {
        assertEquals(TxMode.LORA, TxMode.fromSatNogs("LoRa"))
        assertEquals(TxMode.LORA, TxMode.fromSatNogs("lora"))
        assertEquals(TxMode.FSK_AX25_G3RUH, TxMode.fromSatNogs("FSK AX.25 G3RUH"))
        assertEquals(TxMode.QAM64, TxMode.fromSatNogs("64-QAM"))
        assertEquals(TxMode.DVB_S2, TxMode.fromSatNogs("DVB-S2"))
    }

    @Test
    fun keepsInvertFlagForTransponders() {
        val inverting = dtos.filter { it.invert }.mapNotNull { it.toTransmitter() }
        assertTrue(inverting.isNotEmpty(), "fixture no longer covers inverting transponders")
        inverting.forEach { assertTrue(it.invert) }
    }

    @Test
    fun readsTheIssTransmittersTheRadioScreenWillOffer() {
        val iss = dtos.mapNotNull { it.toTransmitter() }.filter { it.noradId == 25544 }

        assertTrue(iss.size > 5, "expected the ISS transmitter set, got ${iss.size}")

        val sstv = iss.firstOrNull { it.mode == TxMode.SSTV }
        assertNotNull(sstv, "the ISS SSTV downlink is a headline feature and must survive mapping")
        assertEquals(DecoderKind.SSTV, sstv.mode.decoder)

        val aprs = iss.first { it.mode == TxMode.AFSK }
        assertEquals(145_825_000L, aprs.downlinkLowHz)
        assertEquals(DecoderKind.TELEMETRY, aprs.mode.decoder)
        assertEquals(TxStatus.ACTIVE, aprs.status)
    }

    @Test
    fun roundsBaudToAnInteger() {
        val withBaud = dtos.mapNotNull { it.toTransmitter() }.filter { it.baud != null }
        assertTrue(withBaud.isNotEmpty(), "fixture no longer covers baud rates")
        withBaud.forEach { assertTrue(it.baud!! > 0) }
    }

    @Test
    fun treatsAWidebandDownlinkAsAPassbandAndTunesItsCentre() {
        val wideband = dtos.mapNotNull { it.toTransmitter() }.firstOrNull { it.isWideband }
        assertNotNull(wideband, "fixture no longer covers wideband passbands")

        assertEquals(
            (wideband.downlinkLowHz + wideband.downlinkHighHz!!) / 2,
            wideband.centerHz,
        )
    }

    /** Everything we cannot decode must still be listenable, or a satellite becomes a dead end. */
    @Test
    fun everyMappedTransmitterHasSomethingTheAppCanDoWithIt() {
        val transmitters = dtos.mapNotNull { it.toTransmitter() }
        assertTrue(transmitters.isNotEmpty())
        transmitters.forEach {
            assertTrue(it.mode.decoder in DecoderKind.entries, "${it.mode} has no decoder mapping")
            assertTrue(it.downlinkLowHz > 0)
        }
    }
}

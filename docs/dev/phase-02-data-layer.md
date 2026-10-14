# P2 — Data Layer (`core:network`, `core:database`, `core:data`, `core:datastore`)

**Objective:** satellites, TLEs, and transmitters flowing from CelesTrak/SatNOGS into Room,
exposed through repositories, with an offline bundled snapshot so first launch works instantly.
**Depends on:** P0, P1 (models). **Duration target:** 3–4 days.

## Tasks

### P2.1 — Ktor client (`core:network`)
- Shared `HttpClient` factory (OkHttp engine androidMain / Darwin iosMain) with:
  kotlinx-serialization JSON (ignoreUnknownKeys), timeout config, retry-with-backoff plugin,
  Kermit logging in debug, gzip.
- **Accept:** client builds per platform via Koin; unit test with MockEngine.

### P2.2 — TLE sources (multi-source, per `docs/plan/07-references.md`)
- Primary — `CelesTrakApi.group(group: GpGroup): List<GpElementDto>` using
  `https://celestrak.org/NORAD/elements/gp.php?GROUP={g}&FORMAT=json`.
  Groups enum: `STATIONS, WEATHER, NOAA, AMATEUR, CUBESAT, VISUAL, ACTIVE`.
- ISS special path — `https://live.ariss.org/iss.txt` (ARISS, always-fresh ISS TLE).
- Fallbacks — SatNOGS `https://db.satnogs.org/api/tle/?format=3le` (on CelesTrak failure),
  AMSAT `https://amsat.org/tle/current/nasabare.txt` (amateur-group redundancy).
  Source list modeled as `TleSource(name, url, format, priority)` — Look4Sat-style registry,
  extensible for v1.1 (McCants/R4UAB deferred).
- Merge rule: **newest epoch wins per NORAD id**, regardless of source.
- DTO → `Tle` + `Satellite` mappers (GP JSON carries OBJECT_NAME, NORAD_CAT_ID, EPOCH, and
  either TLE lines or mean elements — reconstruct TLE lines if absent).
- Classic 2-line/3le TLE **text parser** (fallback sources + P4 custom import): checksum
  validation, typed errors.
- **Accept:** recorded JSON + TLE-text fixtures parse; malformed lines produce typed failures,
  not crashes; merge test: older-epoch source does not overwrite newer.

### P2.3 — SatNOGS transmitters API
- `SatNogsApi.transmitters(): List<TransmitterDto>` from
  `https://db.satnogs.org/api/transmitters/?format=json&status=active` (server-side active
  filter, as Look4Sat does; paged; also support per-satellite query). Map → `Transmitter`;
  keep downlinks only.
- Mode strings mapped against the 58-mode taxonomy in `docs/plan/07-references.md` →
  `TxMode` enum + decoder-capability mapping (FM→NFM, SSTV→SSTV, AFSK/G3RUH→AX25, LRPT→LRPT,
  else LISTEN_ONLY).
- Record attribution requirement (CC-BY-SA) → constant surfaced in Settings/About.
- **Accept:** fixture parse test; mapping drops uplink-only entries; unknown mode strings →
  `UNKNOWN`, never a crash.

### P2.4 — Room schema (`core:database`)
- Entities: `SatelliteEntity`, `TleEntity` (PK noradId, epoch, fetchedAt), `TransmitterEntity`,
  `PassCacheEntity` (key: noradId+observerGeohash6+tleEpoch), `ArtifactEntity` (decoded
  artifacts metadata — schema now, used in P10), `ObservationEntity` (radio session log).
- DAOs with Flow queries: curated list, search by name/norad, by class, "has transmitter" join.
- Room KMP setup: `RoomDatabase` builder expect/actual (bundled SQLite driver on iOS),
  schema export + migration test scaffold from day one.
- **Accept:** DAO round-trip tests run in commonTest (JVM + iOS sim).

### P2.5 — Bundled snapshot
- Script `tools/snapshot/build_snapshot.main.kts`: fetches curated set (~250 sats: stations,
  weather, top amateur/cubesat, brightest) + their transmitters → writes
  `composeApp/src/commonMain/composeResources/files/catalog_snapshot.json` with a
  `generatedAt` stamp.
- First-run seeding: if DB empty → load snapshot synchronously before first frame needing data
  (< 300 ms budget), then trigger background refresh.
- **Accept:** cold start with network disabled shows full curated catalog with pass predictions.

### P2.6 — Repositories (`core:data`)
```kotlin
interface SatelliteRepository {
    fun observeCurated(): Flow<List<Satellite>>
    fun observeAll(): Flow<List<Satellite>>
    fun search(q: String): Flow<List<Satellite>>
    suspend fun importTle(text: String): Result<Int>   // returns count imported
}
interface TleRepository {
    suspend fun tleFor(noradId: Int): Tle?
    fun staleness(): Flow<TleFreshness>                // FRESH / AGING(days) / STALE(days)
    suspend fun refresh(groups: Set<GpGroup> = defaults): RefreshResult
}
interface TransmitterRepository { fun forSatellite(noradId: Int): Flow<List<Transmitter>> }
interface PassRepository {
    fun upcoming(window: Duration): Flow<List<Pass>>   // cached, invalidation per plan 04
    suspend fun passesFor(noradId: Int, window: Duration): List<Pass>
}
```
- `PassRepository` computes via P1 `PassPredictor`, caches in `PassCacheEntity`, invalidates on
  TLE epoch change or observer moved >10 km (geohash-6 key change).
- Refresh policy: WorkManager-free — simple app-foreground check ("refresh if >24 h old") +
  manual pull; keep it dumb and reliable.
- **Accept:** repo tests with in-memory Room + MockEngine; staleness flow transitions verified
  with a fake clock.

### P2.7 — Preferences (`core:datastore`)
- DataStore(Preferences) KMP: observer mode (auto/manual+GeoPos), units, TLE refresh policy,
  onboarding-done, night mode, SDR defaults (gain/ppm/biasTee/rtl_tcp host history).
- Typed `SettingsRepository` facade with Flows.
- **Accept:** round-trip test; P0.5 onboarding gate now reads the real flag.

## Exit checklist
- [x] Cold offline start → curated catalog visible (snapshot path) — 260 satellites seeded before
      any network call; total-network-failure, missing-bundle and corrupt-bundle paths all tested.
      A true airplane-mode device run is still outstanding (see PROGRESS blockers).
- [x] Online refresh updates TLEs + transmitters; staleness flow correct — 10/10 live sources,
      455 element sets, 2,902 transmitters; freshness transitions tested with a fake clock
- [x] All repos injectable via Koin, covered by tests, and driving the real Satellites screen
- [x] SatNOGS attribution constant present (`DataAttribution.SATNOGS`, CC BY-SA 4.0, required)

## Corrections to this plan, made while building (see BUILDLOG for detail)
- `GpGroup.NOAA` does not exist — CelesTrak retired the group. NOAA 15/18/19 are fetched by
  catalog number instead.
- CelesTrak GP JSON has no TLE lines, so `TleFormatter` in `core:orbit` reconstructs them; verified
  against ARISS's published text for the same element set.
- Pass-cache observer key is geohash-**5** (~5 km), not 6 (1.2 km) — 6 would recompute across town.
- Startup precomputes a **2-day** window, not 10; ten days across the curated set was 46 MB of
  cached arcs.
- DataStore 1.2.1 (stable) rather than the 1.3.0 alpha the P0 note assumed.

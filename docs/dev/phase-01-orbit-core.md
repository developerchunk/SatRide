# P1 — Orbit Core (`core:model`, `core:common`, `core:orbit`)

**Objective:** a validated, pure-Kotlin orbital mechanics engine — SGP4, coordinate chain,
pass prediction, Doppler — with golden-vector tests. This is the app's foundation *and* the
future `orbitk` library.
**Depends on:** P0. **Duration target:** 4–5 days. All `commonMain`, zero platform deps.

## Tasks

### P1.1 — Domain models (`core:model`)
```kotlin
data class Tle(val name: String, val noradId: Int, val line1: String, val line2: String,
               val epoch: Instant)                      // + parsed GP fields as needed
data class Satellite(val noradId: Int, val name: String, val classType: SatClass,
                     val launchDate: LocalDate?, val countryCode: String?, val curated: Boolean)
enum class SatClass { CREWED, WEATHER, AMATEUR, CUBESAT, NAVIGATION, ROCKET_BODY, OTHER }
data class Transmitter(val id: String, val noradId: Int, val description: String,
                       val downlinkLowHz: Long, val downlinkHighHz: Long?, val mode: TxMode,
                       val baud: Int?, val status: TxStatus, val invert: Boolean)
data class GeoPos(val latDeg: Double, val lonDeg: Double, val altM: Double)
data class Topocentric(val azimuthDeg: Double, val elevationDeg: Double,
                       val rangeKm: Double, val rangeRateKmS: Double)
data class Pass(val noradId: Int, val aos: Instant, val tca: Instant, val los: Instant,
                val maxElevationDeg: Double, val aosAzimuthDeg: Double, val losAzimuthDeg: Double,
                val arc: List<TimedTopo>)               // sampled az/el for polar plots
```
- Plus `SatelliteSnapshot` (live state: topo + geodetic subpoint + sunlit + velocity).
- **Accept:** compiles for all targets; no logic here.

### P1.2 — Math & time utilities (`core:orbit.math`, `core:orbit.time`)
- `Vector3`, `Quaternion` (mul, conjugate, rotate, slerp, fromAxisAngle) — value-class-friendly,
  allocation-light.
- Julian date from `Instant`, **GMST** (IAU-82), WGS-84 constants, geodetic→ECEF.
- **Accept:** unit tests vs published values (e.g. GMST at J2000 epoch; ECEF of known city).

### P1.3 — SGP4 propagator (`core:orbit.sgp4`)
- Port SGP4/SDP4 from the canonical **Vallado reference implementation** (public domain).
  Straight structural port first — correctness over elegance; keep variable names traceable
  to the reference for reviewability.
- API: `class Sgp4(tle: Tle) { fun propagate(t: Instant): TemeState }`
  (`TemeState` = position+velocity km / km/s in TEME).
- **Fixtures:** the standard AIAA verification set (`SGP4-VER.TLE` + `tcppver.out` golden
  outputs) checked into `core/orbit/src/commonTest/resources/`.
- **Accept:** all verification satellites within 1e-6 km of golden positions across the test
  time ranges; deep-space cases pass (SDP4 branch).

### P1.4 — Coordinate chain (`core:orbit.frames`)
- `TemeState.toEcef(gmst)`; `ecefToTopocentric(observer: GeoPos): Topocentric` (ENU, az/el/
  range and **analytic range-rate** from velocity projection).
- Sub-satellite point + footprint radius; **eclipse test** (cylindrical Earth-shadow with a
  low-precision solar position — good to <0.5°, fine for "is it sunlit").
- **Accept:** ISS az/el for a known time+observer matches an independent reference (e.g. a
  Skyfield/gpredict-generated fixture) within 0.1°; range-rate sign flips at TCA.

### P1.5 — Doppler math (`core:orbit.doppler`)
- `fun dopplerHz(txHz: Long, rangeRateKmS: Double): Double` = `txHz * (−rangeRate/c)`;
  `data class DopplerState(correctedHz: Double, driftHzPerS: Double)` — drift from d(rangeRate)/dt
  (finite difference of analytic range-rates, 1 s apart).
- **Accept:** 137.1 MHz LEO pass fixture swings ±~3 kHz, monotonic through TCA, zero at TCA.

### P1.6 — Pass prediction (`core:orbit.passes`)
- `class PassPredictor(sgp4, observer)`: coarse 30 s elevation scan over window → refine
  AOS/LOS by bisection (<1 s), TCA by golden-section on elevation; emit `Pass` with sampled
  arc (≥20 points) and precomputed Doppler curve samples for UI.
- Config: min elevation (default 0° AOS, report maxEl), horizon mask hook (post-v1, keep param).
- **Accept:** predictions for ISS over 24 h match reference tool within ±10 s AOS/LOS, ±1° maxEl;
  handles: no passes (geostationary from wrong hemisphere), decayed TLE (throws typed error).

### P1.7 — WMM magnetic declination (`core:orbit.geomag`)
- Minimal WMM implementation (current coefficient epoch, `.COF` values embedded), API:
  `fun declinationDeg(pos: GeoPos, t: Instant): Double`.
- **Accept:** matches NOAA calculator within 0.5° for 5 spread test locations.

### P1.8 — Public facade + docs
- `Orbit` facade tying it together; KDoc on all public API (`explicitApi()` already on).
  This surface becomes `orbitk` in P13 — keep it app-agnostic (no Room/Ktor/UI types).

## Fixtures directory
`core/orbit/src/commonTest/resources/`: AIAA TLE+golden set, ISS reference ephemeris fixture,
doppler curve fixture, WMM test values. Add a `FIXTURES.md` noting provenance of each.

## Exit checklist
- [ ] SGP4 golden vectors green on JVM **and** iosSimulatorArm64 test runs
- [ ] Az/el + pass prediction validated against independent reference
- [ ] Doppler + declination tested; facade documented
- [ ] No dependency beyond model/common/stdlib/datetime (verify with dependency rules)

# P3 — Tracking Engine & Sensors (`core:sensors`, TrackingEngine in `core:data`)

**Objective:** the app-wide live state: where is the observer, where is the phone pointing,
where is every tracked satellite *right now* — at AR frame rates, battery-cheap.
**Depends on:** P1, P2. **Duration target:** 4 days.

## Tasks

### P3.1 — Location (`core:sensors`)
```kotlin
interface LocationProvider {
    val position: StateFlow<ObserverPosition>   // Auto(GeoPos, accuracy) | Manual(GeoPos) | Unknown
    suspend fun requestFix()
    fun setManual(pos: GeoPos?)
}
```
- androidMain: FusedLocationProvider single fix + passive updates (coarse OK); iosMain:
  `CLLocationManager` (reduced accuracy is acceptable — document `NSLocationWhenInUseUsageDescription`).
- Manual position from `SettingsRepository` overrides auto. No permission → `Unknown` →
  features fall back gracefully (onboarding drives the prompt).
- **Accept:** fake-driven common tests for mode precedence; manual smoke on both platforms.

### P3.2 — Attitude (`core:sensors`)
```kotlin
interface AttitudeProvider {
    val attitude: Flow<AttitudeSample>   // Quaternion in ENU/true-north frame + timestamp
    val accuracy: StateFlow<CompassAccuracy>  // HIGH / MEDIUM / LOW / UNCALIBRATED
    fun start(); fun stop()
}
```
- androidMain: `TYPE_ROTATION_VECTOR` @ SENSOR_DELAY_GAME; remap for display rotation;
  convert magnetic→true north by rotating yaw with P1.7 declination at current location.
- iosMain: `CMMotionManager.deviceMotion` with `.xTrueNorthZVertical`, 30–60 Hz; map CoreMotion
  quaternion into the same ENU convention (document the convention in one place —
  `AttitudeFrames.kt` — with axis diagrams in KDoc; this is where bugs will live).
- **Accept:** on-device check: pointing phone at known compass bearings/elevations yields
  matching az/el within sensor tolerance on both platforms (log-based verification).

### P3.3 — Attitude smoothing (commonMain)
- `AttitudeSmoother`: slerp low-pass with adaptive alpha (angular-velocity-dependent: loose
  when moving, tight when still), hemisphere-safe (dot-product sign fix), no lock-up at poles.
- Porting source: Stardroid's sensor-smoothing code (Apache-2.0 — port to Kotlin with NOTICE
  attribution per `docs/plan/07-references.md`), then tune constants on device.
- Tunables in one config object; debug overlay (P5) will expose raw-vs-smoothed.
- **Accept:** unit tests: step response settles < 400 ms, no overshoot oscillation; jitter
  (synthetic noise) attenuated ≥ 10×.

### P3.4 — TrackingEngine (`core:data`)
```kotlin
class TrackingEngine(...) {
    val observer: StateFlow<ObserverPosition>
    val snapshots: StateFlow<Map<Int, SatelliteSnapshot>>   // 1 Hz, tracked set
    fun interpolated(noradId: Int, tNanos: Long): Topocentric?  // for 30–60 Hz AR reads
    fun at(t: Instant): List<SatelliteSnapshot>             // time-travel (P5.7)
    fun follow(noradId: Int); fun unfollow(noradId: Int)
}
```
- Tracked set = above-horizon (from 30 s full-catalog sweep) ∪ followed ∪ curated-visible-soon.
- 1 Hz propagation on `Dispatchers.Default`; `interpolated()` lerps az/el/range between the
  last two fixes (handle az 359°→0° wrap). Lifecycle-aware: engine idles when app backgrounded
  (except during radio session — P9 takes a wakelock via session).
- Emits per-satellite `DopplerState` when a transmitter is selected (P9 consumes).
- **Accept:** perf test — 300-satellite sweep < 50 ms on Default dispatcher (JVM test with
  timing assertion, generous bound); interpolation continuity test across wrap; battery sanity:
  no propagation when no subscribers.

### P3.5 — Koin + debug surface
- Wire providers + engine as singletons; add debug route showing observer, sensor accuracy,
  live table of top-10 snapshots (az/el/range ticking at 1 Hz).
- **Accept:** debug screen live on both platforms; values sane vs reference app for tonight's
  sky.

## Exit checklist
- [H] ISS az/el matches a reference tracker 🔌 — the debug screen is built and the engine is
      verified against physics on desktop (geostationary arc at the right azimuth, elevation,
      range and zero Doppler; AO-07's shift correct at 145.800 MHz). The phone-to-phone comparison
      needs hardware and is logged as a blocker.
- [H] Compass accuracy transitions observable 🔌 — plumbed end to end and unit-tested, with the
      calibration prompt on the debug screen. Waving a phone near metal needs a phone.
- [x] Engine passes perf + wrap tests; no work when idle — 300 satellites in **0.9 ms**, azimuth
      wrap tested in both directions, nothing propagates without collectors
- [x] Frame conventions documented in `AttitudeFrames.kt` — with axis diagrams, and 14 tests
      pinning them

## Corrections to this plan, made while building (see BUILDLOG for detail)
- `interpolated()` **extrapolates** forward rather than only interpolating between the last two
  fixes. Clamping at the newest fix froze every glyph for a second at a time, because the render
  loop always asks for a time later than the newest fix.
- `AttitudeProvider` has no `start()`/`stop()`. The flows are cold, so the sensor's lifetime is
  the collection's — there is no pair of calls to forget.
- The attitude smoother adapts on a **velocity probe**, not on the filtered-versus-raw error: a
  degree of noise and a degree of motion are indistinguishable by error alone.
- Location uses the platform `LocationManager`, not Play Services' fused provider, so the app
  works on devices without Google Play Services.

# 04 — Orbits, Sensors, AR

Everything in this doc is `commonMain` except the sensor/camera actuals. This is the layer we
extract into the open-source **`orbitk`** library (ecosystem-contribution points).

## TLE data pipeline (core:network → core:data)

- **Primary source: CelesTrak GP API** (JSON, `gp.php?GROUP=...&FORMAT=json`) — groups:
  `stations`, `weather`, `amateur`, `active`, `visual`, `cubesat`. JSON GP format avoids
  TLE-line parsing fragility (but we also parse classic 2-line/3le format for fallback sources
  and user-imported files). **Additional sources** (registry modeled on Look4Sat's
  `Sources.kt` — full verified URL list in [07-references.md](07-references.md)): ARISS
  `live.ariss.org/iss.txt` for an always-fresh ISS TLE, SatNOGS `api/tle/?format=3le` as
  fallback, AMSAT `nasabare.txt` for amateur redundancy; newest-epoch-wins merge per NORAD id.
- **Transmitters: SatNOGS DB API** (`db.satnogs.org/api/transmitters`) — downlink freq, mode,
  baud, status per NORAD id. CC-BY-SA — attribute in-app (About screen + satellite detail).
  Ship a **bundled snapshot** of both catalogs (curated ~250 sats + transmitters) so first
  launch works offline/instantly; sync in background.
- **Freshness policy:** auto-refresh per group every 24 h on Wi-Fi/any (setting); TLEs older
  than 5 days flagged in UI (SGP4 error grows ~1–3 km/day). Room stores epoch + fetch time.
- ISS SSTV events are ad-hoc (ARISS announcements) — v1: a tiny JSON we host (updateable
  without app release) listing active event windows; the app banners it.

## Orbit engine (core:orbit)

Pure Kotlin, zero platform deps, heavily unit-tested.

- **SGP4/SDP4 propagator** ported to Kotlin from the canonical Vallado reference implementation
  (public-domain); validated against the standard AIAA test vectors (`tcppver.out` golden set).
  LEO satellites (all our radio targets) use SGP4; deep-space SDP4 included for completeness.
- **Coordinate chain per satellite per tick:**
  1. SGP4(t) → position/velocity in **TEME**
  2. TEME → **ECEF** via GMST rotation (IAU-82 GMST from Julian date — sufficient accuracy;
     no need for full nutation/EOP at consumer level)
  3. ECEF − observer ECEF (from geodetic lat/lon/alt, WGS-84) → **ENU** topocentric vector
  4. ENU → **azimuth / elevation / range**; d(range)/dt → **range-rate** for Doppler
  5. Extras: sub-satellite point + footprint circle (map/detail), eclipse state (is the
     satellite sunlit — drives "visible to the eye" badge, using solar position + cylindrical
     shadow test), orbit ground-track sampling for trails.
- **Doppler:** `f_rx = f_tx × (1 − ṙ/c)`. Range-rate computed analytically from the velocity
  vector projection (not finite difference). At 137 MHz LEO: ±3 kHz swing; at 437 MHz: ±10 kHz.
  Engine emits `(f_corrected, drift_rate)` at 1 Hz; the DSP interpolates between updates
  (drift is smooth), so tuning never steps audibly.
- **Pass prediction:** coarse scan of elevation at 30 s steps over the window, refine
  AOS/LOS/TCA by bisection to <1 s. Output: `Pass(aos, tca, los, maxEl, aosAz, losAz,
  polar-arc samples, doppler-curve samples)`. Cache in Room keyed by (sat, observer-geohash,
  TLE epoch); invalidate on TLE update or observer move >10 km.
- **Scheduler:** `TrackingEngine` propagates the *tracked set* (visible + user-followed) at
  1 Hz on `Dispatchers.Default`; the full catalog is only swept every 30 s to find
  rising satellites. AR frames (30–60 Hz) interpolate az/el between 1 Hz fixes —
  LEO angular motion is ≤1°/s, linear interpolation is visually perfect and battery-cheap.

## Sensor fusion (core:sensors)

Goal: a stable **device-attitude quaternion in the East-North-Up (true-north) frame**.

- **Android:** `TYPE_ROTATION_VECTOR` (system-fused gyro+accel+mag). Remap coordinate system
  for portrait/landscape; monitor `SensorManager` accuracy for the calibration UX.
- **iOS:** `CMMotionManager.deviceMotion` with `.xTrueNorthZVertical` reference frame —
  CoreMotion does fusion *and* true-north for us.
- **True north on Android:** rotation vector is magnetic-north-referenced → add declination.
  Ship the **WMM-2025 coefficient model** in `core:orbit` (pure Kotlin port, tiny) rather than
  platform `GeomagneticField`, so iOS/Android use identical math (iOS true-north mode also
  cross-checked against it).
- **Smoothing layer (common):** platform quaternions → slerp-based low-pass with adaptive
  factor (loose when moving fast, tight when still) to kill jitter without lag; gimbal-safe.
  This is the Stardroid lesson: the polish is entirely in this filter.
- **Calibration UX:** expose accuracy as a first-class state → compass chip + figure-8 card.

## AR rendering (feature:arsky)

Approach: **sensor-driven camera overlay** (Stardroid/Star Walk style), *not* ARCore/ARKit.
Rationale: satellites are at "infinity" — we only need orientation, not SLAM world-tracking.
Sensor-only works on every device (no ARCore allowlist), costs far less battery, works at
night pointing at a featureless sky (where SLAM tracking degrades), and is 100 % shareable
KMP math. ARKit/ARCore would add drift-free yaw at the cost of two native integrations —
revisit post-hackathon if yaw drift proves annoying.

- **Projection:** satellite az/el → unit vector in ENU → rotate by inverse device attitude →
  camera-space vector → pinhole projection using the **camera's real FOV** (queried from
  CameraX characteristics / AVCaptureDevice format; per-axis FOV from sensor aspect). Points
  behind the camera culled; off-screen tracked target → edge arrow (angle from projected
  direction).
- **Layers (bottom→top):** camera preview (native view) → sky layer (Compose `Canvas`:
  horizon, cardinal ticks, orbit trails, satellite glyphs, labels with declutter) → HUD
  (Compose widgets). The Canvas redraws from a 30 Hz `withFrameNanos` loop reading interpolated
  snapshots — plain Compose drawing handles ~50 glyphs + arcs easily; no GL needed for v1.
  (The waterfall in the radio feature is the only GPU-texture surface — see 05.)
- **Label declutter:** priority = tracked > radio-active > elevation; greedy screen-space
  collision with fade in/out, hysteresis to prevent flicker.
- **Time travel:** the sky layer renders from `TrackingEngine.at(t)` — scrubbing t previews
  pass paths in AR/planetarium; the only change is the clock source.
- **Planetarium mode:** same renderer, attitude replaced by a drag/pinch-controlled virtual
  camera; doubles as the no-permission fallback and the tablet/demo mode.

## Location (core:sensors)

Coarse is enough (1 km ≪ satellite range). Auto via FusedLocation/CoreLocation single-fix +
significant-change; manual city/map picker fallback stored in DataStore. Observer altitude
from location fix if present, else 0 — negligible effect at LEO ranges.

## Accuracy budget (why users see satellites where we draw them)

| Error source | Magnitude | Mitigation |
|---|---|---|
| TLE age | ~1–3 km/day → <0.5° typical | 24 h refresh + staleness chip |
| GMST-only transform (no nutation) | <0.01° | ignore |
| Compass/attitude | 1–5° (dominant) | fusion + smoothing + calibration UX |
| Interpolation (1 Hz→30 Hz) | <0.03° | ignore |

Attitude dominates → glyphs get a soft glow radius (not pixel-point), and the guide-arrow +
haptic interaction is designed to be satisfying even with 3° of compass error.

# 02 — Architecture

Kotlin Multiplatform + Compose Multiplatform, Android + iOS. Shared UI *and* shared logic;
platform code only where hardware forces it. Multi-module by feature, unidirectional data flow,
DI throughout.

## Tech stack (aligned with JetBrains' recommended Shipaton stack)

| Concern | Choice | Notes |
|---|---|---|
| UI | **Compose Multiplatform** | Single UI codebase; `androidMain`/`iosMain` only for camera preview & platform chrome |
| DI | **Koin** | KMP-native, works in `commonMain`, per-module `module {}` definitions |
| Presentation | **androidx.lifecycle ViewModel (KMP)** + StateFlow, MVI-flavored UDF | `ViewModel` is multiplatform now; one pattern everywhere |
| Navigation | **JetBrains Navigation Compose** (type-safe, `@Serializable` routes) | Deep links for pass notifications |
| Networking | **Ktor client** (Darwin/OkHttp engines) | CelesTrak + SatNOGS; also **raw Ktor sockets** for `rtl_tcp` |
| Serialization | **kotlinx.serialization** | JSON (APIs) + custom binary parsing for SDR protocol |
| Database | **Room KMP** | Satellites, TLEs, transmitters, observations, decoded artifacts metadata |
| Prefs | **DataStore KMP** | Location mode, units, filters, onboarding state |
| Date/time | **kotlinx-datetime** + custom astro time utils | Julian date / GMST conversions live in `core:orbit` |
| Concurrency | **kotlinx.coroutines** | DSP runs on dedicated dispatchers; `Flow` end-to-end |
| Images | **Coil 3 (KMP)** | Decoded image artifacts, satellite art |
| Logging | **Kermit** | |
| Crash/analytics | Firebase (androidMain) / Crashlytics iOS via native SDK, behind `Analytics` interface | Optional, week 8 |

## Module graph

```
satride/
├── androidApp/                 # Android entry: Activity, USB intent filters, FGS declarations
├── iosApp/                     # Xcode project: SwiftUI shell hosting ComposeUIViewController
│
├── composeApp/                 # App shell (commonMain): navigation graph, root scaffold,
│                               #   theme wiring, Koin startup — depends on all features
│
├── core/
│   ├── model/                  # Pure data types: Satellite, Tle, Transmitter, Pass, GeoPos,
│   │                           #   TopocentricCoords, SdrConfig, DecodedArtifact — zero deps
│   ├── common/                 # Dispatchers, Result/AppError, time source, units, extensions
│   ├── designsystem/           # Theme, tokens, typography, shared components, motion specs
│   ├── orbit/                  # ★ Pure-Kotlin SGP4/SDP4 propagator, pass prediction, Doppler
│   │                           #   math, coordinate transforms (TEME→ECEF→ENU→az/el),
│   │                           #   GMST, WMM magnetic declination. 100% commonMain + tests.
│   │                           #   → extracted & published as `orbitk` (ecosystem points)
│   ├── data/                   # Repositories: SatelliteRepo, TleRepo, TransmitterRepo,
│   │                           #   PassRepo, ObservationRepo. Room + DataStore + Ktor behind
│   │                           #   interfaces. TLE freshness policy, catalog sync.
│   ├── network/                # Ktor client, CelesTrak GP API, SatNOGS DB API, DTOs
│   ├── database/               # Room KMP schema + DAOs
│   ├── sensors/                # expect/actual: attitude (rotation vector / CMMotionManager),
│   │                           #   location (FusedLocation / CoreLocation), compass accuracy
│   ├── sdr/                    # ★ SdrSource interface + IQ Flow; commonMain: RtlTcpSource
│   │                           #   (Ktor sockets); androidMain: UsbRtlSdrSource (clean-room
│   │                           #   RTL2832U/R820T driver on android.hardware.usb)
│   │                           #   → extracted & published as `rtlsdr-kmp`
│   ├── dsp/                    # ★ Pure-Kotlin DSP: NCO/mixer, decimators, FIR/IIR, AGC, FM
│   │                           #   demod, resamplers, FFT (spectrum/waterfall), decoder
│   │                           #   framework + SSTV / AFSK-AX.25 / (stretch) LRPT decoders
│   ├── audio/                  # expect/actual audio sink: AudioTrack / AVAudioEngine
│   └── notifications/          # expect/actual local notification scheduling (pass alerts)
│
└── feature/
    ├── onboarding/             # Welcome flow, permission choreography, first-wow AR moment
    ├── arsky/                  # AR camera sky view + manual "planetarium" mode (no camera)
    ├── satellites/             # Catalog, search, filters, satellite detail
    ├── passes/                 # Pass timeline, planner, alert rules
    ├── radio/                  # SDR connect flow, spectrum/waterfall, tuner, Doppler HUD
    ├── decode/                 # Live decoder surfaces: SSTV canvas, telemetry, LRPT, gallery
    └── settings/               # Location, units, TLE refresh, hardware, about
```

Rules: features never depend on features (navigation goes through the app shell via routes);
`core:orbit` and `core:dsp` depend on nothing but `core:model`/`core:common` (pure, testable);
only `core:sensors`, `core:sdr`, `core:audio`, `core:notifications` and the camera preview
composable contain expect/actual.

## expect/actual boundary (the whole list)

| Capability | commonMain contract | androidMain | iosMain |
|---|---|---|---|
| Attitude | `AttitudeProvider: Flow<Quaternion>` (+accuracy) | `ROTATION_VECTOR` sensor | `CMMotionManager` (`xTrueNorthZVertical`) |
| Location | `LocationProvider: Flow<GeoPos>` | FusedLocationProvider | `CLLocationManager` |
| Camera preview | `@Composable CameraLayer(fovState)` | CameraX `PreviewView` in `AndroidView` | `AVCaptureVideoPreviewLayer` in `UIKitView` |
| Camera FOV | reported per-device | `CameraCharacteristics` focal length | `AVCaptureDevice.activeFormat` FOV |
| USB SDR | `SdrSource` impl discovery | `UsbManager` + clean-room RTL driver | **not available** (returns empty — by design) |
| Audio out | `AudioSink.write(FloatArray)` | `AudioTrack` (low-latency) | `AVAudioEngine` source node |
| Notifications | `AlertScheduler.schedule(pass)` | `AlarmManager` + channels | `UNUserNotificationCenter` |
| Keep-awake / bg | `SessionKeeper` | Foreground service (during SDR session) | `UIApplication.isIdleTimerDisabled`; audio session for bg audio |
| Haptics | `Haptics.tick()` | `Vibrator` | `UIImpactFeedbackGenerator` |

**iOS + RTL-SDR (validated):** direct USB SDR on iPhone is impossible — Apple's DriverKit/
USBDriverKit is iPad-M-series-only and Apple has stated no iPhone support plans (the community
librtlsdr DriverKit port runs only on M-series iPads). So `core:sdr` exposes *sources*, not
*USB*: on Android you get `UsbRtlSdrSource` + `RtlTcpSource`; on iOS you get `RtlTcpSource`
(dongle plugged into a Pi/Mac on the LAN running `rtl_tcp`). Everything downstream — tuning,
Doppler, DSP, decoders, UI — is `commonMain` and byte-identical across platforms. The radio
connect screen explains this honestly on iOS and offers a one-tap guide to setting up `rtl_tcp`.

## State & data flow

**Pattern:** MVI-lite per screen. Each feature screen has a `ViewModel` exposing a single
`StateFlow<UiState>` + `onEvent(UiEvent)`; side effects via a `Channel<UiEffect>` (nav, snackbar,
haptic). No global god-store — but two app-wide streams are provided by singletons via Koin:

```
TrackingEngine (core:orbit + core:data)
  observerPos: StateFlow<GeoPos>
  tracked:     StateFlow<List<SatelliteSnapshot>>   // az/el/range/rangeRate/velocity/footprint
  ticker: 1 Hz for lists, on-demand 30 Hz interpolation for AR frame loop
  (SGP4 runs on Dispatchers.Default; AR frames interpolate between 1 Hz fixes — cheap & smooth)

RadioSession (core:sdr + core:dsp)
  state: Disconnected → Connecting → Streaming(config) → Decoding(mode)
  spectrum: Flow<FloatArray>      // FFT frames for waterfall
  decoderOutput: Flow<DecoderEvent>  // audio level, SSTV scanline, telemetry frame, image row
  One active session app-wide; survives navigation (foreground service on Android)
```

**Threading model for the hot path:** USB/socket reader → `Channel<ByteArray>` (conflated
backpressure with drop-oldest for display taps, lossless for decoder taps) → DSP pipeline on a
dedicated 2-thread dispatcher → UI flows sampled at display rate. IQ buffers are pooled and
reused (no per-buffer allocation at 2.4 MS/s).

## Navigation map

```
RootScaffold (bottom bar: Sky · Satellites · Play · Passes · Radio)
├── onboarding/*        (full-screen, once)
├── sky                 (AR view — default tab)
│     └── sky/detail/{satId}   (bottom-sheet detail over AR)
├── satellites → satellites/{satId}
├── play                (3D globe: live constellation, ride-a-satellite mode)
├── passes → passes/{passId}
├── radio → radio/decode/{mode}
├── gallery (from decode)
└── settings/*
```

Type-safe `@Serializable` route objects; pass alerts deep-link to `sky/detail/{satId}?pass={id}`.

## Testing strategy

- `core:orbit`: golden tests against published SGP4 verification vectors (the standard
  AIAA/Vallado test set) + cross-checks vs known ISS ephemeris; pass-prediction regression tests.
- `core:dsp`: decoders fed recorded IQ/WAV fixtures (SSTV test files, AFSK test vectors,
  captured Meteor baseband) — fully deterministic in `commonTest`.
- `core:sdr`: protocol unit tests against recorded `rtl_tcp` byte streams; fake `SdrSource` for UI.
- Feature ViewModels: plain coroutine tests with fake repos.
- Screenshot tests (Android) for design-system components; manual device matrix for AR/sensors.

## Native-code posture

Start 100 % Kotlin, including DSP. At 0.25–1.05 MS/s (our operating rates — see 05) pure Kotlin
comfortably keeps up on both JVM and Kotlin/Native. Escape hatches if profiling says otherwise:
Android → NDK via JNI for the tight loops; iOS → cinterop to Accelerate/vDSP for FFT and FIR.
The `Dsp` interfaces are shaped so either can slot in without touching callers. LRPT's Viterbi
decoder is the one component pre-designed for this swap.

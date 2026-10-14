# SatRide — Progress Tracker

**Legend:** `[ ]` not started · `[~]` in progress · `[x]` done (add date) · `[H]` code done,
needs hardware verification · `[C]` cut (link the cut-line decision in BUILDLOG)

Update this file **in the same commit** as the work. One task = one status line. Phase headers
get ✅ only when the phase's exit checklist is fully ticked in its phase file.

## Blockers (live list — newest first)
- ~~iOS Firebase setup~~ — **cleared 2026-08-06.** `firebase-ios-sdk` 11.15.0 and `GoogleSignIn-iOS`
  8.0.0 are now Swift packages in `iosApp.xcodeproj`, the bundle id lines up
  (`com.developerstring.satride.SatRide` in both `Config.xcconfig` and `GoogleService-Info.plist`), and
  the app **builds, installs and runs on a physical iPhone**.
- **iOS *unit tests* still cannot link.** `:core:auth:linkDebugTestIosSimulatorArm64` and friends fail
  with `ld: framework 'FirebaseCore' not found`. This is a different problem from the one above and
  is not a regression: Xcode resolves the SPM packages into DerivedData **for the app target**, and
  Gradle's standalone Kotlin/Native test binaries have no idea those frameworks exist. The app links
  because Xcode does the linking; the test binaries link themselves.

  Options when it is worth fixing: point the test link at the resolved frameworks with `linkerOpts`
  (fragile — hardcodes a DerivedData path), or move the Firebase-touching code behind an interface
  so `core:auth`'s tests do not need the SDK at all (cleaner, and the module is already shaped that
  way — `AuthRepository` and `ProfileRepository` are interfaces). The second is the real answer.

  Until then `./gradlew check` needs those iOS link tasks excluded. **Everything else is green**, and
  the shared code is still covered on iOS by every other module's `iosSimulatorArm64Test`.
- ~~P6 device checkpoint~~ — cleared 2026-08-04 on a Pixel 6a emulator (Android 16). Full loop:
  timeline → remind → alarm → notification → tap → Sky guided, plus a reboot. Three crashes/races
  found and fixed in the process; see BUILDLOG. Still open on iOS (no hardware).
- ~~`feature:play` fails `ktlintCheck`~~ — cleared 2026-08-04. `ktlintFormat` fixed all but nine
  violations; those were `const val OceanR`-style colour channels that the rule wants in
  SCREAMING_SNAKE, renamed by hand. The globe snapshot test was re-run afterwards and the render is
  unchanged, which is the check that mattered — the reformat touched a hand-tuned hot path.
  `./gradlew check` is now green on a clean tree with nothing excluded.
- **P5 outdoor checkpoint** — the AR view is verified on a Pixel 6a *indoors*: it renders, tracks,
  hit-tests, composites over the camera, holds 0.04% jank and a flat heap, and its numbers match
  real orbits. What is still open is the one thing indoors cannot settle — pointing the phone at a
  satellite during a pass and seeing the glyph land on it. Also unmeasured: battery over a 10-minute
  session (the phone charges over the adb cable) and the guide loop's haptic feel.
- ~~P4 device verification~~ — cleared 2026-08-02. Catalog, glyphs, filters,
  detail, pull-to-refresh, overflow, TLE import end-to-end, and release-build scroll perf all
  verified on a Pixel 6a.
- ~~P3.2 compass on hardware~~ — cleared 2026-08-02 on a Pixel 6a: real GPS fix, compass HIGH,
  and a face-up phone reporting elevation −86.6°, which is what the camera-forward convention
  predicts. See BUILDLOG.
- ~~P2 on a device~~ — cleared 2026-08-02; found and fixed the bundled-snapshot packaging bug.
- **P13** — CI workflow committed but never executed (no git remote yet).
- ~~Back affordance~~ — cleared 2026-08-04 from PR #2. Non-tab routes now get a back button
  in the top bar. It mattered more than logged: the system gesture hid it on Android, and desktop has
  no back gesture at all.
- **PR #2 leftovers** — keyboard orbiting (WASD), magnetic hover and ride-from-anywhere are *not*
  taken. The ideas are good; the implementation consumes every KeyDown (swallowing Escape and Tab,
  which breaks the same PR's Escape-to-back) and never clears held keys on focus loss. Worth doing
  properly as its own change. See BUILDLOG 2026-08-04.
- ~~P0 device verification~~ — cleared 2026-08-02 on a Pixel 6a (Android 16). See BUILDLOG.
- ~~iOS device before P5~~ — **deferred** (2026-08-02, no hardware available). See the Deferred
  section below; this is tracked debt, not a stop.

## Deferred — iOS hardware verification

No iPhone is available, so iOS is verified only by compilation and by `iosSimulatorArm64Test`
(291 tests, identical to JVM). Development continues on Android; this list is what has to be
walked through when a device appears, and it **grows with every phase**, which is the reason for
keeping it rather than relying on a general "test iOS" note.

The simulator covers all shared logic. What it cannot cover is anything backed by real hardware:

| Phase | Needs a device because | Risk if wrong |
|---|---|---|
| P2 | Compose Resources packaging differs per platform — the Android equivalent silently shipped no bundled catalog | First launch has an empty catalog until the network answers |
| P3 | `CMMotionManager` attitude, and the north-west-up → east-north-up conversion. CoreMotion is the one thing no test can stand in for | Sky rotated 90°, or a doubled/absent declination — plausible-looking and completely wrong |
| P3 | `CLLocationManager` authorisation, which needs `NSLocationWhenInUseUsageDescription` to have been read by a real iOS | No observer position; every screen falls back to manual entry |
| P4 | Catalog, filter sheet, detail and import rendering and gestures under UIKit | Layout or touch-target problems Compose desktop/Android do not show |
| P5+ | Camera preview, frame pacing, haptics | The AR view is built directly on P3's attitude — an error there surfaces first here |
| P6 | `UNUserNotificationCenter` authorisation and delivery, and `onOpenURL` — neither exists on the simulator in any form worth trusting | A reminder that never arrives, or arrives and opens the app on the wrong screen. Both are silent: nothing logs, nothing crashes. **Android found three real bugs in this exact path**, none of them visible to the unit tests — assume iOS has its own. Scheduled in `phase-13-release.md`, before the Sep 12 submission |

**The compounding one is P3.** Every later phase that points at the sky rests on that conversion,
so the longer it goes unverified the more work sits on top of an unchecked assumption. It is
written down in `AttitudeFrames.kt` with the reasoning and pinned by fourteen tests, which is the
best that can be done without hardware — but it has never met a magnetometer.

---

## P0 — Scaffold ✅ (2026-08-02)
- [x] P0.1 Gradle workspace & version catalog — 2026-08-02
- [x] P0.2 Module skeletons + dependency rules — 2026-08-02 (violation test proven, see BUILDLOG)
- [x] P0.3 App entries (Android/iOS/desktop launch) — 2026-08-02
- [x] P0.4 Design system seed — 2026-08-02 (incl. custom icon set + DesignSystemPreview)
- [x] P0.5 Navigation shell — 2026-08-02
- [x] P0.6 CI + quality gates — 2026-08-02 (CI yaml committed, not yet run on GitHub)

## P1 — Orbit Core ✅ (2026-08-02)
- [x] P1.1 Domain models — 2026-08-02
- [x] P1.2 Math & time utilities — 2026-08-02
- [x] P1.3 SGP4 propagator + AIAA golden tests — 2026-08-02 (762 comparisons, worst 1.15e-7 km)
- [x] P1.4 Coordinate chain — 2026-08-02 (az/el within 0.01° of Skyfield)
- [x] P1.5 Doppler math — 2026-08-02
- [x] P1.6 Pass prediction — 2026-08-02 (AOS/LOS within 10 s of Skyfield)
- [x] P1.7 WMM declination — 2026-08-02 (within 4e-7° of the NOAA model)
- [x] P1.8 Facade + docs — 2026-08-02

62 tests, green on **both** JVM and iosSimulatorArm64.

## P2 — Data Layer ✅ (2026-08-02)
- [x] P2.1 Ktor client — 2026-08-02 (OkHttp/Darwin/Java, retry, gzip, MockEngine tests)
- [x] P2.2 TLE sources — 2026-08-02 (10 sources merged live; newest-epoch-wins)
- [x] P2.3 SatNOGS transmitters + mode taxonomy — 2026-08-02 (60-mode enum, all 50 live strings map)
- [x] P2.4 Room schema — 2026-08-02 (6 tables, schema v1 exported, DAO tests on JVM + iOS)
- [x] P2.5 Bundled snapshot — 2026-08-02 (260 satellites / 501 transmitters, 123 KB)
- [x] P2.6 Repositories — 2026-08-02
- [x] P2.7 Preferences — 2026-08-02 (DataStore **1.2.1 stable**, not the 1.3.0 alpha)

183 tests, green on **both** JVM and iosSimulatorArm64.

## P3 — Tracking & Sensors ✅ (2026-08-02)
- [x] P3.1 Location provider — 2026-08-02 (LocationManager/CoreLocation, coarse; manual overrides)
- [H] P3.2 Attitude provider — 2026-08-02 (code complete both platforms; **needs a device**)
- [x] P3.3 Attitude smoothing — 2026-08-02 (11.7x jitter rejection, 180 ms step, 2.2° lag @ 60°/s)
- [x] P3.4 TrackingEngine — 2026-08-02 (300 satellites in 0.9 ms; idle when unsubscribed)
- [x] P3.5 Koin + debug surface — 2026-08-02 (Settings → Tracking debug; `--track` on desktop)

244 tests, green on **both** JVM and iosSimulatorArm64.

## P5 — AR Sky `[H]` (2026-08-02 — verified on device indoors; outdoor checkpoint open)
- [x] P5.1 Camera layer — CameraX / AVCaptureVideoPreviewLayer / desktop no-op. On device the
      hardware reports **41.7° × 65.6°** in portrait, which is the sensor's landscape ~67°
      transposed and cropped — the FOV chain working end to end
- [x] P5.2 Projection pipeline — 2026-08-02 (pinhole, allocation-free, 26 hand-computed tests)
- [x] P5.3 Sky renderer — 30 Hz `withFrameNanos` on one Canvas. Release build in AR: **2413 frames
      in 40 s, 0.04% janky, p50 11 ms, p99 15 ms**; in planetarium with the *whole catalog* tracked
      and the sky full of below-horizon ghosts: **28.5 fps at the cap, 0.35% janky, p50 9 ms**, and
      zero background GCs in 75 s
- [H] P5.4 Interaction — hit test and detail sheet verified on device, including below-horizon
      ghosts (COMS 1 at −73.8° opened correctly). **Guide mode is the piece not driven end to
      end** — 11 tests cover the tick curve, but "feels right" is a subjective gate
- [x] P5.5 HUD — compass chip reads OK on device; calibration card and mode toggle work
- [x] P5.6 Planetarium — drag verified on device, inertia framerate-independent (15 tests)
- [x] P5.7 Time travel — built behind `SkyFeatureFlags.timeTravel`, **off**. P5's third cut line;
      the flag ships either way, so taking the cut is a one-line change

## P4 — Satellites Feature ✅ (2026-08-02)
- [x] P4.1 Catalog screen — 2026-08-02 (6 segments, debounced search, live az/el; verified on device)
- [x] P4.2 Filters — 2026-08-02 (class, band, has-downlink, min-elevation; verified on
      device)
- [x] P4.5 Pull-to-refresh — 2026-08-02 (real per-source progress, app-scoped so it survives
      navigation; 11 sources now fetched concurrently)
- [x] P4.3 Satellite detail — 2026-08-02 (live block, next pass, downlinks, ISS block)
- [x] P4.4 Custom TLE import — 2026-08-02 (strict parse, friendly errors;
      reachable from Settings **and** the catalog overflow; verified on device)

287 tests, green on **both** JVM and iosSimulatorArm64.

## P5 — AR Sky
- [ ] P5.1 Camera layer + real FOV
- [ ] P5.2 Projection pipeline (tests first)
- [ ] P5.3 Sky renderer
- [ ] P5.4 Interaction (tap, guide, haptics)
- [ ] P5.5 HUD + calibration UX
- [ ] P5.6 Planetarium mode
- [ ] P5.7 Time travel (flagged)

## P6 — Passes & Alerts ✅ (2026-08-04 — full loop verified on an Android emulator)
- [x] P6.2 Polar plot composable — 2026-08-04 (`PolarPassPlot`, thumbnail 56 dp + full 240 dp;
      projection pinned by 10 arithmetic tests, drawing by an offscreen render test that writes
      PNGs to `composeApp/build/reports/passes/`)
- [x] P6.1 Pass timeline — 2026-08-04 (day grouping in the *user's* zone, 1 Hz countdowns, pass
      cards with polar thumbnail + grade badge, followed/min-elevation/radio filters, 24 h and
      7 d windows with the week actually computed on selection)
- [x] P6.3 Pass detail — 2026-08-04 (full plot with live dot, elevation curve, Doppler preview for
      the highest active downlink, downlink list, [Remind me] + [Open in Sky]; reachable from the
      timeline and from P4.3's next-pass card)
- [x] P6.4 Local notifications — 2026-08-04 (`core:notifications`: AlarmManager + channel + boot
      receiver on Android, `UNCalendarNotificationTrigger` on iOS, Room `pass_alerts` at schema v2
      with a migration test, drift reconcile after every TLE refresh). **Verified on a Pixel 6a
      emulator (Android 16):** alarm armed at exactly AOS − 10 min, notification fired and posted on
      the `pass-alerts` channel reading *"TEVEL2-9 pass / Pass in 10 min — max 85°, rises SSE"*,
      auto-cancelled itself after firing, and the boot receiver re-armed a pending alert across a
      full reboot without the app being opened. The emulator does **not** grant
      `SCHEDULE_EXACT_ALARM`, so the inexact fallback is what actually ran — `dumpsys` showed a
      33-second window and the UI correctly said "give or take a few minutes"
- [x] P6.5 Deep links — 2026-08-04 (`satride://sky/detail/{noradId}?guide=true`, parsed in common
      code, Android intent-filter + `onNewIntent`, iOS `CFBundleURLTypes` + `onOpenURL`).
      **Verified on the emulator:** cold start straight from the URL, warm start including the
      same-satellite-twice case, and a notification tap from a *different* tab, all landing on the
      Sky view with the guide banner up. Three real bugs were found doing it — see BUILDLOG

979 test executions green on **both** JVM and iosSimulatorArm64 — 103 of them new in P6, including
a `ViewModelResolutionTest` that reproduces the crash which reached the emulator. `./gradlew check`
is green with nothing excluded.

### What the emulator could not settle (carried into later phases)
- **Exact alarms on a real phone.** The emulator denies `SCHEDULE_EXACT_ALARM`, so only the
  degraded path has ever run. How late an inexact pass alert actually lands, and whether it is worth
  asking the user for the permission, is a P12 question — noted in `phase-12-polish.md`.
- **iOS notifications and `onOpenURL`.** Still no Apple hardware; the whole iOS half of P6.4/P6.5 is
  compile-verified only. Noted in the deferred table below and in `phase-13-release.md`.
- **Battery over a session with alerts armed.** P12.3.

## ⏸ Deferred to post-v1 (2026-08-04) — P7 · P8 · P9 · P10

The radio track is paused until after the Shipaton submission. Nothing is deleted and nothing is
renumbered; each phase file opens with why and how to resume. See `docs/plan/06-roadmap.md`.

## P15 — Identity & Profile (2026-08-04 — Android verified on a Pixel 6a)
- [x] P15.1 `core:auth` contracts + Firebase wiring — 2026-08-04 (GitLive KMP SDK 2.5.0; `AuthState`
      keeps `Unknown` distinct from `SignedOut`; both bindings fall back to an unavailable
      implementation so a build without Firebase shows a signed-out screen instead of crashing)
- [x] P15.2 All Google accounts offered, none auto-selected — 2026-08-04. **Verified on device:**
      the chooser listed **all nine** Google accounts on the phone, including ones that had never
      used SatRide, and appeared again immediately after a sign-out rather than re-using the previous
      account. Required switching API — see BUILDLOG
- [x] P15.3 Profile over Firestore — 2026-08-04. **Round-trip proven on device:** wrote a profile,
      cleared app data to destroy the local cache, signed in again, and the profile came back from
      the server. Rules in `docs/plan/08-firestore-rules.md`
- [x] P15.4 Sign-in, create-profile and profile screens — 2026-08-04 (searchable 249-country picker,
      name pre-filled from Google, Profile tab with edit / sign out / delete account)
- [ ] P15.5 iOS handoff 🔌 — **needs the project owner in Xcode**: add `firebase-ios-sdk` and
      `GoogleSignIn-iOS` via SPM, and fix the bundle-id mismatch (`GoogleService-Info.plist` is
      registered for `com.developerstring.satride`, `Config.xcconfig` builds
      `com.developerstring.satride.SatRide$(TEAM_ID)`)

## P16 — First Run
- [x] P16.1 Startup orchestration — 2026-08-04 (four independently-failable steps, run concurrently;
      a failed element-set refresh is not a failed startup)
- [x] P16.2 Splash — 2026-08-04 (globe assembling, measured progress, names the step it is on)
- [x] P16.3 Eight-second cap — 2026-08-04. **Measured on device: handoff at 8.001 s from launch.**
      The cap is charged from process start, not from the view model — see BUILDLOG
- [~] P16.4 Shimmer — `ShimmerBox`/`ShimmerList` built and used by the profile screen; **not yet
      applied to the catalog rows or pass cards**, which is where it matters most
- [~] P16.5 Permissions, properly — **partly.** Fixed the real defect the user found: the Android
      location source listened to GPS *only* when fine location was granted, so an indoor phone got
      no fix and fell back to a cached network position **four days old** — the Passes tab was
      computing from where the phone had been on Tuesday. Now registers GPS and network together,
      `GeoFix` carries an age, and `isBetterFix` picks between them (7 tests). On device: ±100 m
      cached → **±34 m fresh**. A "Your location" panel on the Profile tab makes the position, its
      source and its accuracy visible. **Still missing:** `PERMANENTLY_DENIED`, the
      approximate-vs-precise upgrade path, rationale-before-prompt, and the settings deep link The existing ad-hoc handling in `feature:arsky`
      still stands, so camera and location do work; what is missing is `PERMANENTLY_DENIED`,
      the approximate-vs-precise upgrade path, rationale-before-prompt, and the settings deep link

## P7 — SDR Sources ⏸
- [ ] P7.1 Contracts + buffer pool
- [ ] P7.2 FakeSdrSource
- [ ] P7.3 RtlTcpSource
- [ ] P7.4 USB plumbing 🔌
- [ ] P7.5 RTL2832U + R820T init (clean-room) 🔌
- [ ] P7.6 Bulk streaming + soak 🔌
- [ ] P7.7 Session skeleton

## P8 — DSP & NFM
- [ ] P8.1 DSP primitives
- [ ] P8.2 FFT + spectrum
- [ ] P8.3 Demod graph
- [ ] P8.4 Audio out
- [ ] P8.5 Radio screen v1 (spectrum/waterfall/tuner)

## P9 — Doppler & Session
- [ ] P9.1 DopplerController
- [ ] P9.2 RadioSession (full)
- [ ] P9.3 Tune-from-anywhere
- [ ] P9.4 Doppler HUD (money visual)
- [ ] P9.5 Foreground service / iOS background audio 🔌
- [ ] P9.6 Pass-aware UX

## P10 — Decoders
- [ ] P10.1 Decoder framework
- [ ] P10.2 Audio decoder + recording
- [ ] P10.3 SSTV (PD120/PD180)
- [ ] P10.4 AFSK/AX.25/APRS
- [ ] P10.5 Decode surfaces
- [ ] P10.6 Gallery
- [ ] P10.7 LRPT scaffolding (flag off)

## P12 — Polish
- [ ] P12.1 Motion sweep
- [ ] P12.2 Empty/edge-state sweep
- [ ] P12.3 Performance & battery
- [ ] P12.4 Resilience sweep
- [ ] P12.5 Accessibility & intl
- [ ] P12.6 Device matrix 🔌

## P13 — Release
- [ ] P13.1 Release builds & signing
- [ ] P13.2 Store assets
- [ ] P13.3 Compliance package
- [ ] P13.4 Submissions (Apple **Sep 12**, Play **Sep 15**) 🔌
- [ ] P13.5 Publish orbitk + rtlsdr-kmp + blog post

## P18 — No advertising id (2026-09-14) ✅
- [x] `AD_ID` and `ACCESS_ADSERVICES_AD_ID` removed with `tools:node="remove"` — they come from
      Firebase Analytics. Verified absent from the merged release manifest
- [x] Play declarations: no advertising id — `docs/plan/play-store-listing.md`

## P19 — wasm globe demo (scoped only)
- [ ] D1 `wasmJs()` on `core:model` + `core:orbit` — the go/no-go gate
- [ ] D2 Move `SphereMesh.kt` somewhere both the app and a demo can see
- [ ] D3 `:demo:globe-web` — Compose wasmJs, minimal renderer, bundled TLE
- [ ] D4 Publish at `/globe`, link from the Devpost write-up
      See `docs/plan/10-wasm-globe-demo.md`. **Not on the critical path for Sep 30.**

## P14 — Shipaton Submission
- [ ] P14.1 Demo video 🔌
- [ ] P14.2 Devpost entry (**submit by Sep 28**)
- [ ] P14.3 #BuildInPublic archive
- [ ] P14.4 Post-submission hygiene

# SatRide

**Point your phone at the sky. See the satellites. Hear them.**

SatRide turns a phone plus a $30 RTL-SDR dongle into a real-time AR satellite tracker and RF
ground station: satellites overlaid on the live camera view, tap-to-tune reception with
continuous Doppler correction, and in-app decoding of what comes down — weather images, ISS
SSTV, cubesat telemetry. No desktop software.

Kotlin Multiplatform + Compose Multiplatform, targeting **Android, iOS and desktop** (macOS, Windows,
Linux) from one codebase.

---

## Status

**Phase 0 complete — project scaffold.** The app builds and runs on Android, iOS and desktop
with the full module structure, DI, navigation and design system in place. Screens are
labelled placeholders until their phase lands.

Progress is tracked in [`docs/dev/PROGRESS.md`](../docs/dev/PROGRESS.md); notable events and
deviations are logged in [`docs/dev/BUILDLOG.md`](../docs/dev/BUILDLOG.md).

## Documentation

| Where | What |
|---|---|
| [`PLAN.md`](../PLAN.md) | Product overview and index |
| [`docs/plan/`](../docs/plan/) | Architecture, UX, orbital/AR math, SDR pipeline, roadmap, references |
| [`docs/dev/`](../docs/dev/) | The build contract: phases P0–P14 with tasks, contracts and acceptance criteria |

## Getting started

```bash
./gradlew :androidApp:assembleDebug
```

Requires JDK 21, Android SDK (compileSdk 37) and — for iOS — Xcode 26.

| Task | Command |
|---|---|
| Android debug build | `./gradlew :androidApp:assembleDebug` |
| Desktop app | `./gradlew :desktopApp:run` |
| Desktop installer (DMG / MSI / DEB for the host OS) | `./gradlew :desktopApp:packageDistributionForCurrentOS` |
| iOS | open `iosApp/iosApp.xcodeproj` in Xcode and run |
| JVM tests | `./gradlew jvmTest` |
| iOS tests | `./gradlew iosSimulatorArm64Test` |
| Style | `./gradlew ktlintCheck` (`ktlintFormat` to fix) |
| Architecture rules | `./gradlew checkModuleDependencyRules` |
| Everything | `./gradlew check` |

## Module layout

```
composeApp/     App shell: navigation graph, root scaffold, DI aggregation
androidApp/     Android entry point       desktopApp/  Desktop entry point
iosApp/         Xcode project hosting the shared UI

core/
  model         Pure data types                common        Dispatchers, results, utilities
  designsystem  Theme, tokens, components      orbit         SGP4, passes, Doppler  (P1)
  network       Ktor + CelesTrak/SatNOGS       database      Room schema            (P2)
  datastore     Preferences                    data          Repositories, TrackingEngine
  sensors       Location, attitude, camera     sdr           RTL-SDR sources        (P7)
  dsp           Demodulation, decoders         audio         Platform audio output
  notifications Pass alerts                    auth          Firebase sign-in
  permissions   Runtime permissions            web           Links, in-app pages
  analytics     Analytics + crash reporting

feature/        onboarding · auth · profile · startup · arsky · satellites · passes · play ·
                radio · decode · settings
```

Two boundaries are enforced by `checkModuleDependencyRules`, not by convention:

1. **Features never depend on features** — cross-feature navigation goes through the app shell.
2. **`core:orbit` and `core:dsp` stay pure** (model/common only) — they are extracted as
   open-source libraries in P13, so a dependency on Room or Ktor there is a release blocker.

## Stack

Compose Multiplatform · Koin · Ktor · Room · kotlinx-{serialization, datetime, coroutines} ·
JetBrains Navigation Compose · Firebase. Build config is split deliberately: dependency
**versions** live in `gradle/libs.versions.toml`, structural settings (SDK levels, JVM target)
in `gradle.properties` under `satride.*`, because convention plugins in `build-logic/` cannot
cleanly read the version catalog.

## Platform note: desktop

The desktop build runs the same shared UI as the phones. Three things are different because the
hardware is:

- **Sky view** is a planetarium — drag to look around, pinch or scroll to zoom — because a desktop
  has no compass or camera to point at the sky.
- **Location** comes from the network connection (city-level, one HTTPS lookup per launch). A
  position typed into Settings always wins.
- **Pass alerts** are delivered through the system notification centre *while the app is open*.
  Timers are rebuilt from the stored reminders on every launch.

Sign-in needs two things, both git-ignored and both optional — without them the app runs signed
out:

1. `google-services.json` — the Android one is bundled automatically by the desktop build when it
   exists at `androidApp/google-services.json` (or drop a copy into `~/.satride/`). This starts
   Firebase Auth and Firestore.
2. An OAuth client of type **Desktop app** from the Firebase project's Google Cloud console, as
   `client_id` / `client_secret` in `desktopApp/src/main/resources/google-oauth-desktop.properties`,
   `~/.satride/google-oauth-desktop.properties`, or the `SATRIDE_GOOGLE_DESKTOP_CLIENT_ID` /
   `SATRIDE_GOOGLE_DESKTOP_CLIENT_SECRET` environment variables. Sign-in then runs through the system
   browser with a loopback redirect and PKCE.

Data lives in `~/.satride/` (database, preferences, Firebase session). The app icon for all three
desktop formats is generated — see `tools/icon/README.md`.

## Platform note: RTL-SDR

Direct USB SDR access is **not possible on iPhone** — Apple restricts DriverKit to M-series
iPads. SatRide therefore treats the SDR as a *source*, not a USB device:

- **Android** — RTL-SDR over USB-OTG, plus network.
- **iOS** — network source (`rtl_tcp`), with the dongle on a Raspberry Pi or Mac.

Everything above that boundary — tuning, Doppler correction, DSP, every decoder, all UI — is
shared `commonMain` code and identical on both platforms.

## Multi-Play

Multi-Play is the arcade half of the Play tab: a landscape, full-screen arena where up to ten
pilots race the planet at 200 km/s over the same vector Earth the globe draws. It lives in
`feature/play/.../multiplay/` and is reached from the **Multi-Play** button on the Play tab.

- **Modes**: Orbit Race (three laps through 24 gates), Debris Dodge (survival with shields as the
  field thickens), Satellite Tag (ram to pass it on). Every mode shares one flight model with
  drift-to-boost, boost orbs, and real catalog satellites as obstacles (a near miss on the ISS is
  a "flyby").
- **Rooms**: Firestore-backed. A room lives fifteen minutes, holds ten seats, and the host can fill
  empty seats with bots that the host's phone simulates and publishes. Each phone judges its own
  gates and crashes and publishes its craft ~5×/s; others dead-reckon between samples.
- **Solo**: practice against bots on a random track, or the **daily challenge** — one shared seed
  per UTC day with a Firestore leaderboard.
- **Controls**: floating thumb stick, hold-to-drift, tap-to-boost, hold-to-brake; drag or tilt the
  phone to look around (motion camera is self-calibrating from gravity, so it works with Android's
  remapped axes and iOS's raw ones); optional tilt-to-steer.
- **Progression**: local best scores, fourteen trophies, games-played count (all in settings).
- **Solar System**: the fourth game, also reached from the **Solar System** button on the Play tab.
  A starship in free flight through the real solar system — the Sun, the eight planets, the Moon
  and Pluto at today's positions (JPL Keplerian elements + a low-precision lunar series) and true
  sizes, with the vector Earth reused whenever you are near home, procedural flat-vector surfaces
  for the other worlds, Saturn's rings with the planet's shadow, orbit lines and the asteroid belt.
  A logarithmic **speed-of-light dial** (30 km/s to 1000 c) sets the cruise; an **approach
  governor** keeps you from flying through a planet at warp; **AIM** turns the nose onto the chosen
  world and **AUTO** flies the whole trip and parks in orbit. Scoring is an expedition log
  (discoveries, Sun grazes, ring dives, the Grand Tour). Rooms and bots work exactly as in the
  race modes; ships publish heliocentric positions and dead-reckon in straight lines. The same
  Sun, Moon and planets are also drawn in the Play tab's sky at their real directions.

Backend setup, once per Firebase project:

1. Enable the **Anonymous** provider under Authentication → Sign-in method. Guests race under an
   anonymous session; the app's own auth state deliberately treats it as signed out.
2. Deploy the rules: `cd firebase && firebase deploy --only firestore:rules` (or paste
   `firebase/firestore.rules` into the console). The rules cover `rooms/*`, `rooms/*/players/*`
   and `leaderboards/*/scores/*` alongside the existing `users/*`.

## Attribution

Satellite data from [CelesTrak](https://celestrak.org), [SatNOGS DB](https://db.satnogs.org)
(CC-BY-SA), ARISS and AMSAT. Design and architecture inspiration from
[Look4Sat](https://github.com/rt-bishop/Look4Sat) and
[Stardroid](https://github.com/sky-map-team/stardroid) — see
[`docs/plan/07-references.md`](../docs/plan/07-references.md) for how each is used and the
license boundaries that apply.

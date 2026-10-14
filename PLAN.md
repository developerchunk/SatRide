# SatRide — Master Plan

> **Point your phone at the sky. See the satellites. Hear them.**
>
> SatRide turns a phone + a $30 RTL-SDR dongle into a real-time AR satellite tracker and RF ground
> station: AR sky view with live-propagated satellites, tap-to-tune SDR reception with continuous
> Doppler correction, and live in-app decoding (weather images, ISS SSTV, cubesat telemetry) —
> zero desktop software.

**Target:** Shipaton 2026 — JetBrains **"Ship Kotlin Everywhere"** award
(Kotlin Multiplatform + Compose Multiplatform, Android + iOS).
**Window:** first public release between **Aug 1 – Sep 30, 2026**. Today is Aug 1 → **9-week build**.

---

## Plan documents

| Doc | Contents |
|---|---|
| [01-hackathon.md](docs/plan/01-hackathon.md) | Shipaton rules, award strategy, submission checklist |
| [02-architecture.md](docs/plan/02-architecture.md) | KMP/CMP module graph, DI, navigation, state, expect/actual boundaries, tech stack |
| [03-ux-design.md](docs/plan/03-ux-design.md) | Screen-by-screen UX, onboarding, visual language, motion design |
| [04-orbit-ar.md](docs/plan/04-orbit-ar.md) | SGP4 propagation, TLE pipeline, pass prediction, sensor fusion, AR projection math |
| [05-sdr-pipeline.md](docs/plan/05-sdr-pipeline.md) | RTL-SDR USB driver (Android), rtl_tcp (iOS), DSP chain, Doppler correction, decoders |
| [06-roadmap.md](docs/plan/06-roadmap.md) | 9-week milestone schedule, risk register, cut lines |
| [07-references.md](docs/plan/07-references.md) | Look4Sat & Stardroid reference repos, license boundaries, verified data-source URLs |

**Execution:** the step-by-step build contract (phases P0–P14 with tasks, contracts, acceptance
criteria, and progress tracking) lives in [docs/dev/](docs/dev/README.md) — start at
[docs/dev/README.md](docs/dev/README.md), track state in [docs/dev/PROGRESS.md](docs/dev/PROGRESS.md).

---

## The product in one paragraph

SatRide is **Star Walk 2 for satellites, with a radio attached**. The hero surface is a full-screen
camera AR view: hold the phone up and satellites render exactly where they are in the sky,
propagated live from fresh TLEs with SGP4 and anchored via sensor fusion (rotation-vector /
CoreMotion attitude + GPS + magnetic-declination-corrected true north). Tap a satellite and a
"radio card" slides up: if an RTL-SDR is attached (USB-OTG on Android, `rtl_tcp` network source on
iOS/Android), the app tunes to the satellite's downlink (frequencies from SatNOGS DB), applies
continuous Doppler correction from the live range-rate, shows a spectrum/waterfall, and pipes the
signal into an in-app decoder — NFM audio, ISS SSTV (PD120) frames painting in live, AX.25/AFSK
telemetry, and (stretch) Meteor-M LRPT weather imagery. No satellite knowledge required: the app
tells you *what's overhead now*, *what's worth listening to*, and *when the next good pass is*.

## Hard platform facts (validated)

- **Android**: RTL-SDR over USB-OTG works — we implement a clean-room Kotlin driver on
  `android.hardware.usb` (control transfers + bulk IQ endpoint). Android-module only.
- **iPhone**: direct USB SDR is **not possible** — DriverKit is iPad-M-series only, Apple has said
  no iPhone plans. iOS ships the same app with the **`rtl_tcp` network source** (dongle on a
  Raspberry Pi/Mac) — the entire DSP/decode/Doppler stack is `commonMain` and identical.
  Everything above the `SdrSource` interface doesn't know or care.
- All tracking/AR features are 100% cross-platform; SDR is a *source* abstraction, not a fork.

## Stack at a glance

Kotlin Multiplatform + Compose Multiplatform (Android, iOS) · Koin DI · Jetpack ViewModel/StateFlow
(KMP) with MVI-flavored UDF · JetBrains Navigation Compose (type-safe) · Ktor client (CelesTrak
TLEs, SatNOGS transmitters) + Ktor raw sockets (`rtl_tcp`) · Room KMP + DataStore KMP ·
kotlinx-{serialization, datetime, coroutines} · expect/actual for camera, sensors, location, USB,
audio out, notifications · pure-Kotlin `core:orbit`
(SGP4) and `core:dsp` (demod/decode) — fully unit-testable, no platform deps.

## Free, with no limits

SatRide is free. There are no ads, nothing to buy and no usage limits — every feature is
available to everyone.

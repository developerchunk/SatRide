# SatRide — Development Phases: Execution Guide

This directory is the **build contract** for implementing SatRide. It is written to be executed
phase by phase. The product/architecture rationale lives in `docs/plan/01–06`; **this directory
says exactly what to build, in what order, and how to prove each step is done.**

## How to work (rules for the implementing agent)

1. **One phase at a time**, in numeric order unless the dependency graph below says a phase may
   run in parallel. Never start a task whose upstream contract doesn't exist yet.
2. **Track everything in [PROGRESS.md](PROGRESS.md).** Before starting a task, mark it `[~]`
   (in progress); when its acceptance criteria pass, mark `[x]` with the date. PROGRESS.md is
   the single source of truth for "where are we" — keep it accurate in the same commit as the
   work.
3. **Definition of Done for every task:** code compiles for `androidTarget` + `iosSimulatorArm64`,
   new logic in `core:*` has `commonTest` coverage, `./gradlew check` passes, and the task's
   acceptance criteria in the phase file are demonstrably met.
4. **Definition of Done for every phase:** the phase's **Exit checklist** is fully ticked, the
   app still launches on both platforms, and a short entry is appended to
   `docs/dev/BUILDLOG.md` (date, phase, what shipped, deviations from plan). Deviations are
   allowed — but must be logged with a one-line reason.
5. **Contracts are law until amended.** Interface signatures given in phase files are the
   integration points other phases compile against. If a contract must change, update the phase
   file *and* every dependent phase file in the same commit.
6. **Commit style:** conventional commits scoped by module (`feat(core:orbit): …`,
   `test(core:dsp): …`). Small commits per task ID (e.g. `feat(core:sdr): P7.3 rtl_tcp handshake`).
7. **Never mock what you can fixture.** DSP/decoders/orbit are tested against recorded or
   published data (see each phase's Fixtures section), not hand-rolled mocks.
8. **Package root:** `com.duosages.satride`. Module namespaces: `com.duosages.satride.<module>`
   (e.g. `com.duosages.satride.orbit`).
9. **Hardware-gated work:** tasks marked 🔌 need an RTL-SDR dongle / physical phone. If hardware
   is unavailable in the session, implement + unit-test against fixtures, mark the task `[H]`
   (needs hardware verification) in PROGRESS.md, and list it in BUILDLOG for the next
   on-device session. Never mark `[x]` without the hardware check.
10. **When blocked** (missing credential, store account, design asset, ambiguous product call):
    do not stall the phase — log the blocker in PROGRESS.md's Blockers section, pick the next
    unblocked task, and surface the blocker to the user at end of turn.

## Phase index & dependency graph

| Order | Phase | File | Depends on | Status |
|---|---|---|---|---|
| 1 | P0 | [phase-00-scaffold.md](phase-00-scaffold.md) | — | ✅ |
| 2 | P1 | [phase-01-orbit-core.md](phase-01-orbit-core.md) | P0 | ✅ |
| 3 | P2 | [phase-02-data-layer.md](phase-02-data-layer.md) | P0, P1 | ✅ |
| 4 | P3 | [phase-03-tracking-sensors.md](phase-03-tracking-sensors.md) | P1, P2 | ✅ |
| 5 | P4 | [phase-04-satellites-feature.md](phase-04-satellites-feature.md) | P2, P3 | ✅ |
| 6 | P5 | [phase-05-ar-sky.md](phase-05-ar-sky.md) | P3 | `[H]` outdoor check open |
| 7 | P6 | [phase-06-passes-alerts.md](phase-06-passes-alerts.md) | P3, P5 | ✅ |
| 8 | **P15** | [phase-15-identity-profile.md](phase-15-identity-profile.md) | P6 | ← next |
| 9 | **P16** | [phase-16-first-run.md](phase-16-first-run.md) | P6, P15 | |
| 10 | P12 | [phase-12-polish.md](phase-12-polish.md) | P16 | |
| 11 | P13 | [phase-13-release.md](phase-13-release.md) | P16 | |
| 12 | P14 | [phase-14-submission.md](phase-14-submission.md) | P13 | |
| — | P7 | [phase-07-sdr-sources.md](phase-07-sdr-sources.md) | P0 | ⏸ **deferred post-v1** |
| — | P8 | [phase-08-dsp-nfm.md](phase-08-dsp-nfm.md) | P7 | ⏸ **deferred post-v1** |
| — | P9 | [phase-09-doppler-session.md](phase-09-doppler-session.md) | P5, P8 | ⏸ **deferred post-v1** |
| — | P10 | [phase-10-decoders.md](phase-10-decoders.md) | P8 | ⏸ **deferred post-v1** |

**Read the Order column, not the phase numbers.** P15 and P16 were added after the radio track was
deferred on 2026-08-04. P7–P10 keep their original numbers so that every cross-reference inside
them — and there are many — stays correct when they are picked back up. Renumbering four detailed
phase files to make a table look tidy would have been the more expensive mistake.

Scope note: **SatRide is free.** Nothing is sold and nothing is limited — every feature is available
to everyone.

Scope note (2026-08-04): **v1 is a sky-and-play app.** The radio track — P7–P10, the RTL-SDR
receiver, the DSP chain, Doppler correction and the decoders — is deferred until after the Shipaton
submission. Nothing is deleted: `core:sdr`, `core:dsp`, `core:audio` and `feature:radio` still build,
and each deferred phase file opens with why it is paused and how to resume. See
`docs/plan/06-roadmap.md`.

**Calendar anchors (hard):** Apple review submission **Sep 12** (end of P13.4); Play **Sep 15**;
Devpost **Sep 30, 23:45 PDT**. If a phase slips ≥4 days, apply the cut lines in
`docs/plan/06-roadmap.md` — do not slip the anchors.

## Repo-wide conventions

- Kotlin 2.2+, Compose Multiplatform (latest stable at build time), AGP current stable.
  All versions pinned in `gradle/libs.versions.toml` — every dependency goes through the
  version catalog, no hardcoded coordinates.
- Targets per shared module: `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`.
- `explicitApi()` on `core:orbit`, `core:sdr`, `core:dsp` (future published libraries).
- Feature modules never depend on feature modules. `core:orbit`/`core:dsp` depend only on
  `core:model` + `core:common`. Enforced by a Gradle dependency-rules check added in P0.
- UI state pattern (all features): `data class XxxUiState`, `sealed interface XxxEvent`,
  `sealed interface XxxEffect`, `class XxxViewModel : ViewModel()` exposing
  `StateFlow<XxxUiState>` + `fun onEvent(XxxEvent)` + `Flow<XxxEffect>`.
- Verification commands used throughout:
  - `./gradlew check` — all common/unit tests + lint
  - `./gradlew :composeApp:assembleDebug` — Android build
  - `./gradlew :composeApp:iosSimulatorArm64Test` (or `xcodebuild` via the iosApp scheme) — iOS

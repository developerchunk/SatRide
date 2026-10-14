# P9 — Doppler Integration & Radio Session (`feature:radio` v2, session service)

> ## ⏸ DEFERRED — post-v1 (decided 2026-08-04)
>
> **The radio track is not being built for the first release.** v1 ships as a sky-and-play app:
> AR tracking, the 3D globe, passes and alerts, an account and a profile. Everything in this file
> stays exactly as written and stays true — it is paused, not cancelled.
>
> **Why.** The radio half is the largest, riskiest and least demoable part of the plan: it needs a
> clean-room USB driver, a DSP chain, and — for anyone to *see* it work — a $30 dongle, an antenna,
> an outdoor pass and a satellite that happens to be transmitting. The sky and play halves are
> already built, already run on a phone, and are what a person actually reaches for. Shipping those
> well beats shipping both badly against a fixed deadline.
>
> **What stays in the repo.** `core:sdr`, `core:dsp`, `core:audio` and `feature:radio` remain in
> `settings.gradle.kts` and keep compiling. Nothing is deleted. The Radio tab keeps its
> disconnected-state screen, which now reads as an honest "coming soon" rather than a broken
> feature.
>
> **How to resume.** This file is the contract; nothing in it needs rewriting. Pick it up after the
> Shipaton submission, in the original order, against the same acceptance criteria. The one thing to
> re-check first is the version catalog, since the pinned Ktor/coroutines versions will have moved.
>
> See `docs/plan/06-roadmap.md` for the v1 scope this decision produced.



**Objective:** the magic loop — tap a satellite anywhere in the app → SDR tunes to its
downlink → Doppler correction runs continuously → session survives navigation and screen-off.
**Depends on:** P5 (AR detail sheet), P8. **Duration target:** 4 days.

## Tasks

### P9.1 — DopplerController (commonMain)
- Consumes `TrackingEngine` `DopplerState` (1 Hz `correctedHz` + `driftHzPerS`) for the tuned
  (satellite, transmitter) → drives `DspGraph` NCO with linear ramp between updates
  (phase-continuous; **never** retunes source PLL during a pass — coarse source tune happens
  once at track start, centered so the full ±10 kHz swing stays inside the captured band).
- Manual offset knob state (±2 kHz) added on top; correction toggle.
- **Accept:** fixture test — synthetic pass Doppler curve in, NCO frequency trace out matches
  curve within 5 Hz at all times; no phase discontinuities.

### P9.2 — RadioSession (full)
- Extend P7.7 `RadioSessionManager`: `TunedState(satellite, transmitter, doppler on/off,
  offset)`, decode mode slot (P10 plugs in), recording slot (P10), observation logging →
  `ObservationEntity` (sat, freq, start/end, maxEl during session).
- Session is app-singleton; survives tab navigation; **session chip** appears in root scaffold
  when active (sat name, freq, elevation, tap → radio screen).
- **Accept:** state-machine tests incl. tune-while-streaming, source loss mid-track, satellite
  set/LOS (→ prompt: keep listening / stop).

### P9.3 — Tune-from-anywhere (`RadioLauncher` real impl)
- Replace P4.3 stub: satellite detail [Tune] (sheet or screen) → if no source connected, open
  connect flow with the tune intent queued → on stream, apply transmitter tune + Doppler on.
- AR fast-path: tapping a radio-halo satellite's sheet shows [Tune] with transmitter picker
  inline (default = best active downlink: FM > SSTV > telemetry by decoder capability).
- **Accept:** cold path (nothing connected) and hot path (already streaming) both land tuned
  in ≤ 2 taps; UI test on fake source.

### P9.4 — Doppler HUD
- Tuner strip upgrade: satellite chip, transmitter dropdown, **corrected-vs-nominal frequency
  readout** (mono, ticking), Doppler badge with drift direction arrow, elevation mini-arc.
- Waterfall overlay: nominal-frequency line drifts while passband cursor stays locked — the
  "money visual" (plan 03). Implemented as two overlay lines on the existing waterfall.
- **Accept:** during a real or simulated pass (FakeSdrSource + time-travel engine), the drift
  visual behaves as designed; screenshot for devlog.

### P9.5 — Android foreground service 🔌
- `RadioSessionService`: FGS type `connectedDevice`, started when session starts on USB
  (and for tcp sessions during active decode); live notification (sat, freq, elevation,
  stop action); wakelock scoped to session; clean teardown on stop/detach.
  Manifest: FGS permission + type declarations documented for Play review (P13).
- iOS: background audio via P8.4 audio session keeps tcp+decode alive; document the
  limitation (no audio → suspended) and set `isIdleTimerDisabled` during foreground sessions.
- **Accept:** screen-off USB NFM session on Android runs 15 min uninterrupted 🔌; iOS
  backgrounded tcp audio session keeps playing 🔌.

### P9.6 — Pass-aware UX
- Radio screen banner when tuned sat is below horizon: next pass countdown + [Remind me]
  (P6.4) + [Listen anyway]. AOS auto-prompt if session open when pass begins.
- **Accept:** banner logic unit-tested against engine time.

## Exit checklist
- [ ] Live pass: tap sat in AR → tuned, Doppler-corrected, audible — the product thesis 🔌
- [ ] Session chip + navigation persistence + screen-off robustness verified
- [ ] Doppler trace test green; money visual captured for devlog
- [ ] FGS/notification survives Play pre-review checklist (permissions documented)

# P7 — SDR Sources (`core:sdr`)

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



**Objective:** IQ samples flowing on both platforms: `RtlTcpSource` (commonMain, unblocks
everything) then the Android USB driver (clean-room, androidMain). Future `rtlsdr-kmp` library.
**Depends on:** P0 only — **run in parallel with P4–P6 when possible.**
**Duration target:** 5–6 days (USB driver is the long pole).

## Order note
Build P7.1→P7.3 first and hand off to P8 immediately (DSP work proceeds against rtl_tcp +
fixtures); the USB driver (P7.4–P7.6) continues in parallel.

## Tasks

### P7.1 — Contracts + buffer pool (commonMain)
- `SdrSource` interface exactly as specced in `docs/plan/05-sdr-pipeline.md` (caps, open,
  samples(): Flow<IqBuffer>, retune, setGain, close).
- `IqBuffer`: pooled `FloatArray` pairs (I/Q deinterleaved) + sampleRate + centerHz +
  timestamp; `IqBufferPool` (fixed count, e.g. 32 × 16 kSample) with `retain/release`;
  u8→float LUT converter.
- `SdrSourceRegistry`: expect/actual enumerating available sources
  (android: USB + TCP; ios: TCP only).
- **Accept:** pool stress test (no allocation after warm-up, leak detection via refcount
  asserts); registry returns platform-correct sets.

### P7.2 — FakeSdrSource (commonMain, test/debug)
- Plays back IQ files from resources (u8 interleaved) at real-time or max rate; also a
  synthesizer mode (tones/FM at given offsets + noise floor) for deterministic DSP tests.
- **Accept:** used as the debug-menu source; P8 tests build on it.

### P7.3 — RtlTcpSource (commonMain)
- Ktor raw TCP sockets. Parse 12-byte header (`RTL0`, tuner type, gain count); command writer
  (cmd byte + u32 BE): 0x01 freq, 0x02 rate, 0x03 gain mode, 0x04 tuner gain, 0x05 ppm.
- Robustness: connect timeout, read watchdog (no bytes 3 s → `Reconnecting` state),
  clean close, backpressure = drop-oldest into pool.
- Config UI hooks: host/port + recents from `SettingsRepository` (P2.7 already has fields).
- **Accept:** protocol unit tests against a recorded rtl_tcp byte stream fixture + an in-test
  fake server; live test against real `rtl_tcp` on LAN 🔌 (both platforms).

### P7.4 — USB plumbing (androidMain) 🔌
- `device_filter.xml`: 0x0bda:0x2832, 0x0bda:0x2838 + common rebrand IDs; attach
  intent-filter on MainActivity → routes to Radio tab; in-app `requestPermission` flow with
  designed pre-prompt (UI lands P9, callback interface now).
- Claim interface, locate bulk-in endpoint, control-transfer helpers
  (`readReg/writeReg/demodReg` for RTL2832U register access over vendor requests).
- **Accept:** dongle enumerates; register read of known ID succeeds (logged).

### P7.5 — RTL2832U + R820T init (androidMain, clean-room) 🔌
- **License policy (same study-and-reimplement rule as `docs/plan/07-references.md`):**
  librtlsdr is GPL-2. Reading it to understand the init/tuning flow is fine and expected —
  register addresses, magic values, and command sequences are facts and may be taken directly.
  What's forbidden is copying or line-by-line translating its code structure; our driver is
  written fresh in idiomatic Kotlin from understanding + datasheets. Keep a `PROTOCOL.md` in
  the module recording each register sequence and its source (datasheet / osmocom docs /
  observed librtlsdr behavior) — this doubles as the published library's docs and provenance
  record.
- Sequence: demod init (reset, ADC config, FIR defaults) → I2C repeater → detect tuner (R820T
  at 0x34) → tuner init: LO PLL programming for target freq, IF config (RTL in low-IF mode),
  gain tables (LNA/mixer/VGA), auto-gain mode → set sample rate (resampler regs) → enable
  streaming. Support R820T/R860 only; unknown tuner → typed `UnsupportedTuner` surfaced in UI.
- ppm correction + bias-tee GPIO (V3/V4) toggles.
- **Accept:** tuned to a local FM broadcast at 1.024 MS/s, IQ spectrum (via P8 FFT debug view
  or dumped-to-file offline check) shows the station's carrier where expected; retune moves it.

### P7.6 — Bulk streaming (androidMain) 🔌
- 3× queued `UsbRequest`s of 64 KB on a dedicated thread; hand off into `IqBufferPool`;
  drop-oldest under pressure with a counter surfaced to debug HUD; detach handling →
  `SourceState.Disconnected(reason)` cleanly mid-stream.
- **Accept:** 30-minute soak at 2.4 MS/s: zero crashes, drop counter ~0 on a mid-range phone,
  stable memory.

### P7.7 — Session skeleton
- `RadioSessionManager` (commonMain, singleton): owns at most one open source,
  `state: StateFlow<RadioState>` (Disconnected/Connecting/Streaming/Error(reason)), exposes
  the sample Flow to DSP. (Doppler + FGS integration arrives in P9 — keep this minimal.)
- **Accept:** state machine unit tests: open/close/reconnect/detach transitions.

## Exit checklist
- [ ] rtl_tcp streaming verified on Android + iOS 🔌; USB streaming verified on Android 🔌
- [ ] Fake source powers all downstream tests; registry/platform gating correct
- [ ] PROTOCOL.md complete with provenance; `explicitApi` clean (library-ready)
- [ ] Soak test logged in BUILDLOG (device, dongle model, drop stats)

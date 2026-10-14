# P8 — DSP Core & NFM (`core:dsp`, `core:audio`, `feature:radio` v1)

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



**Objective:** IQ in → audible NFM out + live spectrum/waterfall, all `commonMain`, tested
against fixtures. End state: listening to FM broadcast/satellite audio in-app on both platforms.
**Depends on:** P7.1–P7.3 (fake + tcp sources suffice). **Duration target:** 5 days.

## Tasks

### P8.1 — DSP primitives
- `Nco` (complex oscillator w/ phase-continuous frequency ramping — the Doppler hook),
  `FirFilter` (windowed-sinc designer: lowpass(cutoff, transition, atten)), `HalfBandDecimator`
  cascade, `PolyphaseDecimator`, `Agc` (attack/decay), `PowerSquelch` (threshold + hysteresis,
  emits open/close events), `QuadratureDemod` (atan2 conjugate-product), de-emphasis IIR.
- All operate on preallocated `FloatArray` I/Q blocks; benchmarks in `commonTest` (JVM timing
  asserts with generous bounds; document iOS numbers in BUILDLOG).
- **Accept:** unit tests per block — filter passband/stopband measured via test tones; NCO
  frequency ramp is phase-continuous (no discontinuity in unwrapped phase).

### P8.2 — FFT + spectrum pipeline
- Radix-2 complex FFT (2048), Hann window, magnitude-dB frames, exponential averaging;
  `SpectrumTap` producing ~25 fps `FloatArray` frames independent of decode path
  (conflated — UI drops frames, decoder path never does).
- Interface `Fft` kept swappable (vDSP/NDK later per plan 02).
- **Accept:** tone at known offset appears in the correct bin ±1; benchmark logged.

### P8.3 — Demod graph assembly
- `DspGraph(source: Flow<IqBuffer>, config)`: NCO shift → decimate to 48 k → NFM demod chain
  (channel filter 12.5 kHz → quad demod → de-emphasis → AGC → squelch) → `Flow<AudioBlock>` +
  `SpectrumTap` + signal-power flow (S-meter). Runs on `Dispatchers.Default` limited
  parallelism(2); latency budget source→audio < 250 ms.
- **Accept:** fixture test: synthesized NFM voice IQ (via FakeSdrSource synth) demodulates to
  intelligible audio (assert SNR of recovered tone, not vibes); WFM variant for broadcast
  testing (simple wide filter mode — debug/terrestrial use).

### P8.4 — Audio out (`core:audio`)
- `AudioSink` expect/actual: androidMain `AudioTrack` (48 k mono float, low-latency perf mode);
  iosMain `AVAudioEngine` + `AVAudioSourceNode`; underrun counters exposed.
- iOS: configure `AVAudioSession` (`.playback`) — enables background audio during passes.
- **Accept:** sine from FakeSdrSource → clean tone on both devices; underruns ≈ 0 in 5-min run.

### P8.5 — Radio screen v1 (`feature:radio`)
- Disconnected state per plan 03 (source picker: USB [Android] / rtl_tcp / demo source in
  debug); rtl_tcp connect sheet (host/port/recents).
- Connected: **spectrum + waterfall** (scanlines into a scrolling bitmap texture, one draw per
  frame — never per-row recomposition; pinch-zoom span, drag-to-tune), tuner strip
  (freq mono readout, gain auto/manual slider, squelch, S-meter), big mute/audio toggle.
- Terrestrial free-tuning works now (the "SDR is fun with no pass" path).
- **Accept:** waterfall sustains 25 fps during active demod on mid-range Android; drag-to-tune
  retunes NCO (fine) and source (coarse, when crossing captured-band edge) without audio glitch.

## Fixtures
`core/dsp/src/commonTest/resources/`: synthesized NFM IQ, tone sets, recorded FM broadcast IQ
snippet (self-recorded, license-clean), rtl_tcp stream capture. Provenance in FIXTURES.md.

## Exit checklist
- [ ] FM broadcast audible in-app via rtl_tcp on **both** platforms 🔌; via USB on Android 🔌
- [ ] Spectrum/waterfall smooth; latency < 250 ms measured
- [ ] All DSP blocks unit-tested; benchmarks recorded in BUILDLOG
- [ ] CPU during NFM decode < 25 % of one core at 1.024 MS/s (profiled, logged)

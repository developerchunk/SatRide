# P10 — Decoders (`core:dsp` decoders, `feature:decode`)

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



**Objective:** live in-app decoding — SSTV images painting scanline-by-scanline, AX.25/APRS
frames, recordings, and the artifact gallery. LRPT is explicitly out (v1.1, flagged).
**Depends on:** P8 (P9 for live use). **Duration target:** 5–6 days. **Fixtures-first:**
every decoder works against recorded files in `commonTest` before touching live RF.

## Tasks

### P10.1 — Decoder framework
```kotlin
interface Decoder {
    val mode: DecodeMode                    // AUDIO, SSTV, AX25, LRPT(reserved)
    fun process(block: DemodBlock)          // audio-rate or IF-rate input per mode
    val events: Flow<DecoderEvent>          // Progress, Sync, Artifact(part/complete), Health
    fun reset()
}
```
- `DecoderRegistry` maps `TxMode`→available decoders (taxonomy mapping from P2.3);
  `RadioSession` hosts ≤1 active decoder, feeding from the demod graph tap.
- Artifact persistence: `ArtifactEntity` (P2.4) + files dir (`artifacts/{yyyy-MM}/…`), rows:
  type, satId, passId?, freq, startedAt, location, path, decoder metadata JSON.
- **Accept:** framework tests with a trivial tone-detector decoder.

### P10.2 — Audio decoder + recording
- "Audio" mode = NFM path + level meter + optional recording: WAV (PCM float→16-bit), m4a via
  platform encoder expect/actual (androidMain MediaCodec / iosMain AVAudioFile — WAV fallback if m4a
  friction exceeds a day).
- IQ recording: raw u8 + SigMF-style JSON sidecar. **Cut line #4** if schedule slips.
- **Accept:** recorded WAV round-trips; artifacts appear in gallery with metadata.

### P10.3 — SSTV decoder (PD120/PD180)
- Input 48 k demodulated FM audio. Chain: 1200 Hz sync detect (Goertzel/correlator) → VIS code
  read (mode id) → line timing recovery (sync-edge PLL) → 1500–2300 Hz frequency discrimination
  per pixel → PD modes: Y/RY/BY line pairs → RGB scanlines.
- Emit `Artifact.Part(scanlineRange, pixels)` per line pair — UI paints progressively; final
  `Artifact.Complete(imagePath)` with slant-correction pass (resample by measured line-rate
  error).
- **Fixtures:** published SSTV test WAVs + self-generated PD120 encodings (write a tiny
  encoder in test code — it's ~50 lines and gives infinite clean/noisy cases via added AWGN).
- **Accept:** clean fixture decodes pixel-accurate vs reference; −10 dB SNR fixture still
  syncs; wrong-mode audio doesn't false-lock.

### P10.4 — AFSK-1200 / AX.25 / APRS
- Bell-202 correlator demod → bit PLL → NRZI decode → HDLC deframe (flag, bit-unstuff) →
  CRC-16 validate → AX.25 address/control parse → APRS payload parse (position, status,
  telemetry `T#` frames).
- **Fixtures:** standard AFSK test tracks (e.g. WA8LMF-style test audio) + synthetic frames
  from a test-side encoder.
- **Accept:** ≥ 90 % frame recovery on clean fixture; CRC rejects corrupt frames; ISS APRS
  frequency preset (145.825 MHz) wired as a featured target.

### P10.5 — Decode surfaces (`feature:decode`)
- Shared header: satellite, elevation arc, signal meter, REC button, mode chips.
- **SSTV surface:** live-painting canvas (progressive scanlines + subtle leading-edge shimmer),
  mode badge, save/share: original + share sheet with branded frame ("Received live from orbit with SatRide").
- **Telemetry surface:** monospace frame feed (time, from>to, path, payload preview), APRS
  parse cards; known-cubesat dashboards **only if time** (cut line #2 → generic feed).
- **Accept:** VM tests with scripted decoder events; SSTV paint at 60 fps UI while decode runs.

### P10.6 — Gallery
- Grid of artifacts (Coil), filter by type/satellite, detail view (image zoom / audio player /
  frame log), share, delete (confirm). Entry: Radio tab overflow + decode surfaces.
- **Accept:** artifact lifecycle end-to-end test: decode fixture → artifact → gallery → share
  intent (Android) / share sheet (iOS) → delete.

### P10.7 — LRPT scaffolding only (flagged off)
- Reserve `DecodeMode.LRPT`, registry entry behind `FeatureFlags.lrpt=false`, doc pointer to
  plan 05 pipeline. **No implementation in v1.0.**

## Exit checklist
- [ ] SSTV + AX.25 decode fixtures green in commonTest on both target test runs
- [ ] Live SSTV from a transmitted test signal (handheld/ISS event) decoded on device 🔌
- [ ] Gallery + sharing wired; artifacts persist across restarts
- [ ] Decoder CPU budgets logged; no dropped demod blocks during SSTV decode

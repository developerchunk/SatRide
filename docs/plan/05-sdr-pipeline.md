# 05 — SDR, Doppler, Decode Pipeline

The differentiator. Design principle: **sources are platform-specific; everything after the IQ
stream is `commonMain`.**

```
┌─ androidMain ────────────┐
│ UsbRtlSdrSource (OTG)    │─┐
└──────────────────────────┘ │   commonMain ────────────────────────────────────────────
┌─ commonMain ─────────────┐ ├─► Flow<IqBuffer> ► FrequencyShifter(NCO) ► Decimate ► ┬─► FFT ► waterfall
│ RtlTcpSource (Ktor sock) │─┘        ▲                                              └─► Demod ► Decoder ► UI/audio
└──────────────────────────┘   DopplerController (from TrackingEngine range-rate)
```

## SdrSource abstraction (core:sdr → published as `rtlsdr-kmp`)

```kotlin
interface SdrSource {
    val caps: SdrCaps                        // freq range, sample rates, gain steps
    suspend fun open(config: SdrConfig)      // centerHz, sampleRate, gain, ppm, biasTee
    fun samples(): Flow<IqBuffer>            // pooled buffers of interleaved u8 I/Q → Float
    suspend fun retune(centerHz: Long)       // coarse only — fine tuning is digital (NCO)
    suspend fun setGain(gain: Gain)          // Auto | Manual(dB)
    suspend fun close()
}
```

### Android: `UsbRtlSdrSource` (androidMain)

- **Discovery/permission:** `UsbManager` + `device_filter.xml` for RTL2832U VID/PIDs
  (0x0bda:0x2832/0x2838 and common rebrands) → attach intent launches/binds the radio session;
  in-app connect flow calls `requestPermission` with a designed pre-prompt.
- **Driver: clean-room Kotlin implementation** on `UsbDeviceConnection` — *not* a port of GPL
  librtlsdr code. The RTL2832U control protocol (register writes over control transfers) and
  the **R820T/R860 tuner** init/PLL sequences are documented publicly (datasheets +
  osmocom protocol docs); we implement from specs, which (a) keeps licensing clean for a paid
  app, and (b) gives us a genuinely valuable open-source library to publish. Scope discipline:
  support **R820T/R860 tuners only** (that's the $30 dongle: RTL-SDR Blog V3/V4, generic
  R820T2) — detect others and show "tuner not yet supported."
- **Streaming:** bulk-in endpoint via `UsbRequest` queue (double/triple-buffered, 16–64 KB
  buffers) on a dedicated thread → pooled `IqBuffer`s into the Flow. u8→Float LUT conversion.
  2.4 MS/s ≈ 4.8 MB/s — well within OTG bandwidth; buffer pool prevents GC churn.
- **Session lifetime:** Android **foreground service** (`connectedDevice` type) with a live
  notification (satellite, frequency, elevation) so decoding survives screen-off during a
  10-minute pass.
- Extras: ppm correction setting, bias-tee toggle (V3/V4 GPIO), auto/manual tuner gain.

### Both platforms: `RtlTcpSource` (commonMain)

- `rtl_tcp` protocol over **Ktor raw TCP sockets**: 12-byte header (`RTL0`, tuner type/gains),
  then u8 IQ stream; 5-byte command packets for freq/rate/gain. Trivial, well-documented,
  and it's how iOS participates at all.
- iOS connect UX ships a lovely "turn a Pi or Mac into your antenna" guide
  (`rtl_tcp -a 0.0.0.0`); host:port remembered, Bonjour discovery if cheap (stretch).
- Also valuable on Android: dongle on a roof-mounted Pi beats phone-in-hand for real passes.

### Validated iOS constraint (why no USB path)

Direct USB SDR is impossible on iPhone: DriverKit/USBDriverKit is restricted to M-series iPads,
Apple has stated no iPhone plans, and generic `IOUSBHost` access isn't granted to App Store
iPhone apps. The community librtlsdr DriverKit port confirms this boundary (runs on M-series
iPads only). Decision: **no USB code in iosMain at all**; `RtlTcpSource` + identical common DSP
is the iOS story, stated honestly in-app.

## DSP chain (core:dsp — pure Kotlin, `commonMain`)

**Operating rates:** dongle at 1.024 or 2.4 MS/s (constant — no PLL retune during a pass);
first decimation immediately reduces to the working rate. All hot loops: preallocated
`FloatArray`s, no boxing, no allocation per buffer.

| Stage | Implementation |
|---|---|
| Frequency shift | Complex NCO mixer — **this is where Doppler correction happens** (digital, glitch-free), plus fine tuning within the captured band |
| Decimation | Polyphase FIR / half-band cascade → 48 k (audio modes), 120 k (SSTV/telemetry IF), 140 k (LRPT) |
| Filtering | Windowed-sinc FIR designer at runtime (passband follows mode) |
| AGC / squelch | Per-mode AGC; power squelch with hysteresis (drives the "signal lock" moment) |
| FM demod | Quadrature discriminator (atan2 of conjugate product) + de-emphasis |
| Spectrum | 2048-pt FFT (Kotlin radix-2 first; swap to vDSP/NDK behind interface if profiling demands), Hann window, ~25 fps averaged frames → waterfall texture |
| Audio out | 48 k mono float → `AudioSink` (AudioTrack / AVAudioEngine) |

**Doppler controller:** `TrackingEngine` emits `(f_corrected, drift Hz/s)` at 1 Hz →
`FrequencyShifter` ramps NCO frequency linearly between updates. Correction is continuous and
inaudible; the UI shows raw vs corrected frequency diverging live (the money visual).
Manual offset knob (±2 kHz) on top for TLE/oscillator error; "auto-ppm from beacon" is a
stretch nicety.

## Decoders (core:dsp, pluggable `Decoder` interface)

`Decoder` consumes the demodulated/IF stream, emits `Flow<DecoderEvent>` (progress, artifacts,
health). Priority order:

1. **NFM audio** (MVP): ISS voice repeater, SO-50/AO-91 FM sats, terrestrial. Proves the
   whole chain end-to-end.
2. **SSTV — PD120/PD180**: FM audio → 1200/1500–2300 Hz FSK video: sync-pulse detect,
   line timing recovery, YCrCb scanlines → live-painting image. ISS events at 145.800 MHz are
   scheduled, but the decoder is testable any day from WAV fixtures and our own transmitted
   test signals.
3. **AFSK-1200 / AX.25 + APRS parse**: Bell-202 demod (correlator), NRZI, bit-stuffing,
   HDLC framing, CRC; APRS position/telemetry parse for known cubesats + ISS APRS digipeater
   (145.825 MHz). Small, well-specified, huge "it's alive" payoff.
4. **Meteor-M LRPT** (**stretch — behind a flag until quality bar met**): 72 ksym/s QPSK
   at 137.1/137.9 MHz: RRC filter, Costas loop + Gardner timing, soft-decision **Viterbi
   r=1/2 K=7** (the CPU hot spot — pre-designed for a native swap), CADU sync + derandomize,
   **Reed-Solomon (255,223)**, MPDU reassembly → JPEG-ish 8×8 DCT block decode → channel
   images painting in strips. Ship v1.0 without it if needed; it's the flagship v1.1 update
   (also good #BuildInPublic content: "decoding a weather satellite in pure Kotlin").

Every decoder is developed against **recorded fixtures** in `commonTest` (SSTV WAVs, AFSK test
vectors, captured Meteor baseband from the community) — no waiting for passes to iterate.

## Recording & artifacts

- Audio (m4a/wav) and raw IQ (SigMF-style JSON sidecar with sat/freq/time/location).
- Decoded artifacts (images, telemetry logs) → Room metadata + files dir → Gallery → share
  sheet with branded frame.

## Performance & battery notes

- Budget: DSP ≤ 25 % of one core at 1.024 MS/s NFM on a mid-range phone (measure in week 5;
  escape hatches per 02-architecture).
- Waterfall is the one GPU-conscious surface: render scanlines into a scrolling bitmap/texture,
  draw once per frame — never recompose per FFT row.
- AR + SDR simultaneously is the demo case: cap AR at 30 fps during active decode.
- Thermal: sessions are pass-length (≤15 min); show a temperature-aware warning only if the
  OS reports throttling.

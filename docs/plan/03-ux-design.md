# 03 — UX & Design

**North star:** Star Walk 2's fluidity and wonder, rebuilt around satellites and radio. Every
surface should feel like an instrument, not a settings app. The emotional arc: *look up →
recognize → listen → capture.*

## Visual language

- **Palette — "Night Ops":** near-black indigo base (`#070B14`), star-field neutrals, one
  electric accent per domain: **cyan** for tracking/orbits, **amber** for RF/radio,
  **magenta** for decoded artifacts. Accents glow (soft outer blur) against the dark base.
- **Dark-only v1.** A sky app at night is dark. Plus a **red-light night mode** (pure red on
  black, one toggle away) — beloved by the astro community, trivially done with a color layer,
  great store-screenshot material.
- **Typography:** a geometric grotesk for UI (e.g. Inter/Manrope), **tabular mono** for all
  live numerics (frequencies, az/el, countdowns) so digits don't jitter as they tick.
- **Iconography/orbit art:** thin-stroke line icons; satellites rendered as minimal glyphs by
  class (crewed / weather / ham / cubesat / rocket body), not photorealistic models.
- **Depth:** glassy dark panels (blur + 1px hairline borders) floating over the camera/star
  layer. AR stays legible: overlays never exceed ~35 % screen coverage.

## Motion design (the polish that wins the Design Award)

- Satellites in AR move continuously (30 Hz interpolated) — never stepwise.
- Tap a satellite → its orbit line draws itself across the sky dome (600 ms, decelerate),
  detail sheet springs up; everything else dims 40 %.
- Waterfall scrolls at a fixed cadence; tuning changes animate the passband cursor with a
  spring, and the Doppler correction is *visualized* — the passband drifts and the cursor
  visibly counter-tracks it. (This single animation explains the whole product.)
- Signal-lock moment: when squelch opens / decoder syncs, a subtle haptic tick + the amber
  accent "ignites" on the active elements.
- Shared-element transition from decoded-image thumbnail → full-screen gallery.
- All springs from one motion spec in `core:designsystem` (fast: 120 ms, standard: 240 ms,
  celebratory: 500 ms) so both platforms feel identical.

## Information architecture

Bottom bar, four tabs: **Sky · Satellites · Passes · Radio**. Sky is home. Radio tab shows a
live session chip when streaming (session persists across tabs).

---

## Screens

### 1. Onboarding (feature:onboarding)

Choreographed as a story, ~60 seconds, permissions asked *in context* with a custom pre-prompt
before each system dialog:

1. **Hook** — full-bleed animated sky, one line: "There are 8,000 satellites over your head.
   Let's find them." → [Begin]
2. **Location** — "To know what's above *you*." → system prompt. Fallback: manual city picker
   (map + search) — the app fully works with a manual location.
3. **Camera + Motion** — "Point your phone at the sky." → prompts. Fallback: planetarium mode
   (drag-to-pan sky, no camera).
4. **Compass calibration** — the figure-8 dance as a playful animated card; shows live
   accuracy; skippable.
5. **First wow** — drop straight into AR with the *best currently-visible satellite*
   pre-highlighted and an arrow guiding the user to it. TLEs for the curated set were fetched
   during steps 1–4. If ISS is up, it's the target.
6. **Radio tease (not a gate)** — one card: "Got an RTL-SDR? SatRide can *hear* these too."
   [I have one] → radio setup later · [What's that?] → 3-panel explainer · [Skip].

### 2. Sky (feature:arsky) — the hero

Full-screen camera. Overlaid:

- **Satellites** as glyphs with name labels (declutter: labels only for the N most relevant;
  clusters collapse to a count badge). Brightness/size encodes relevance (elevation ×
  user-interest × has-transmitter).
- **Orbit trails**: faint arc showing each visible satellite's next/previous 5 minutes.
- **Radio halo**: satellites with known active downlinks get a subtle amber pulse — the visual
  bridge to the SDR story.
- **Horizon line + cardinal ticks**; below-horizon satellites ghosted at 20 % with rise
  countdowns.
- **Search-and-guide**: pick a satellite → on-screen arrow + haptic ticks that quicken as you
  converge (Star Walk's signature interaction, ours with haptics).
- **Top strip**: sensor-accuracy chip, time controls (⏪ live ⏩ — time-travel up to ±24 h to
  preview a pass path), planetarium/AR toggle.
- **Tap satellite → Detail sheet** (over AR, half-height): name/class/country/launch, live
  az·el·range·velocity in mono, next-pass card, transmitter list — each with a **[Tune]**
  button that jumps to Radio pre-configured. ISS gets special treatment (crew count, SSTV
  event banner when active).

Planetarium mode (no camera) is the same renderer on a drag/pinch virtual sky — also serves
tablets/emulator demos and users who denied camera.

### 3. Satellites (feature:satellites)

Searchable catalog. Segments: **Up now · Popular · Weather · Ham/FM · Crewed · All**.
Rows show live elevation sparkline + next-pass time. Filters: has-transmitter, band,
min elevation, constellation. Custom TLE import for launches/classified-hobbyist sets.

### 4. Passes (feature:passes)

- **Timeline view**: next 24 h / 7 days as a vertical schedule; each pass card
  shows polar-plot thumbnail (the az/el arc), max elevation, duration, and — key detail —
  a **"radio quality" badge** (max el × transmitter power heuristic: 🟢 great / 🟡 ok / ⚪ visual only).
- **Pass detail**: full polar plot, elevation-vs-time curve, Doppler curve preview,
  [Remind me] (schedules a notification 10 min before AOS), [Open in Sky at AOS time].
- Notification deep-links straight into AR with the satellite guided.

### 5. Radio (feature:radio)

- **Disconnected state** teaches instead of erroring: Android — "Plug in your RTL-SDR"
  (USB illustration; auto-detects attach via intent filter and springs to life). Both
  platforms — "Connect over network (rtl_tcp)" with host:port and a beautiful 3-step Pi/Mac
  setup guide. iOS shows *only* the network path with one honest sentence about Apple's USB
  restriction.
- **Connected — the instrument panel**, three stacked zones:
  1. **Spectrum + waterfall** (GPU-drawn, pinch-zoom, drag-to-tune) with passband cursor and
     Doppler-track visualization.
  2. **Tuner strip**: satellite chip (tap to change), transmitter dropdown (from SatNOGS),
     mono frequency readout showing *corrected* frequency live, Doppler on/off, gain
     (auto/manual), squelch.
  3. **Decoder dock**: mode chips [Audio | SSTV | Telemetry | LRPT*] → opens Decode surface.
- If tuned satellite is not overhead: banner shows next pass + [Remind me]; the panel still
  works for terrestrial listening (NFM anywhere — makes the SDR fun even with no pass).

### 6. Decode (feature:decode)

Mode-specific live surfaces sharing a header (satellite, elevation arc, signal meter, REC):

- **Audio**: big S-meter, audio scope, one-tap record.
- **SSTV**: the image paints **scanline-by-scanline live** — the single most demo-able screen.
  Auto-detects PD120/PD180. Save/share the original.
- **Telemetry (AFSK/AX.25)**: decoded frame feed with callsigns, plus parsed dashboards for
  known cubesats (battery/temp gauges).
- **LRPT (stretch)**: Meteor-M channel images rendering as strips arrive; sync/Viterbi/RS
  health indicators.
- **Gallery**: every captured artifact (image/audio/telemetry log) tagged with satellite, pass,
  time, location; share sheet with a branded frame ("Received live from orbit with SatRide") —
  the social/viral loop.

### 7. Settings

Location (auto/manual), units, TLE refresh policy + last-updated, night mode, hardware
(gain defaults, ppm correction, bias-tee toggle), notifications, about/licenses, devlog link.

---

## Empty/edge states (designed, not defaulted)

- No satellites visible now → "Quiet sky. Next up: NOAA-19 in 23 min" + [Remind me].
- Stale TLEs (>5 days, offline) → accuracy warning chip in AR.
- Magnetic interference → compass-accuracy chip pulses; tap → recalibration card.
- SDR unplugged mid-session → session pauses gracefully, resumes on reattach.
- Indoors/GPS-denied → manual location, planetarium mode; nothing dead-ends.

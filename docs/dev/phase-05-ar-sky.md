# P5 — AR Sky (`feature:arsky`, camera actuals in `core:sensors`)

**Objective:** the hero screen — camera AR with satellites rendered where they really are,
plus planetarium fallback. This phase decides whether the app feels magical; budget polish
time accordingly.
**Depends on:** P3. **Duration target:** 5–6 days.

## Tasks

### P5.1 — Camera layer (expect/actual)
- `@Composable expect fun CameraLayer(modifier: Modifier, onFov: (CameraFov) -> Unit)`
  where `CameraFov(horizontalDeg, verticalDeg)` reflects the **active format's real FOV**.
- androidMain: CameraX `PreviewView` in `AndroidView`; FOV from `CameraCharacteristics`
  (focal length + sensor size, corrected for aspect crop of the preview).
- iosMain: `AVCaptureVideoPreviewLayer` in `UIKitView`; FOV from
  `AVCaptureDevice.activeFormat.videoFieldOfView` (+ derive vertical from aspect).
- No camera permission → composable renders nothing and reports it (screen falls back to
  planetarium).
- **Accept:** preview fills screen, correct orientation, on both platforms; FOV values logged
  and plausible (~60–80° h).

### P5.2 — Projection pipeline (commonMain, `feature:arsky/projection`)
- `SkyProjector(fov, screenSize, attitude: Quaternion)`:
  `fun project(azDeg, elDeg): ProjectedPoint?` (screen x/y, or null if behind camera) and
  `fun directionTo(azDeg, elDeg): Float` (bearing for edge arrows).
- Pinhole model per plan 04; portrait + landscape; unit tests with hand-computed cases
  (attitude = identity facing north/horizon → az 0/el 0 at screen center, el 45 above center
  by tan relationship, az behind → null).
- **Accept:** all projection unit tests green — do not proceed to rendering until they are.

### P5.3 — Sky renderer
- 30 Hz `withFrameNanos` loop → reads `TrackingEngine.interpolated` + smoothed attitude →
  draws on a single Compose `Canvas`:
  horizon line + cardinal ticks, satellite glyphs by class (cyan; amber pulse halo if active
  transmitter), name labels, orbit trails (±5 min sampled arc, faded), below-horizon ghosts
  (20 % alpha + rise countdown for followed sats).
- Label declutter: priority tracked > radio-active > elevation; greedy AABB collision;
  fade with hysteresis (≥ 1 s state hold) to prevent flicker.
- Draw-loop discipline: zero allocations per frame (preallocated paths/arrays, no string
  formatting in the loop — cache label layouts, refresh at 1 Hz).
- **Accept:** 50 satellites + 10 trails at stable 30 fps on a mid-range Android; allocation
  tracker shows flat heap during steady state.

### P5.4 — Interaction
- Tap hit-testing (nearest glyph within 24 dp) → **detail bottom-sheet** over AR (reuses P4
  detail content composable — extract it to be host-agnostic), rest of sky dims 40 %,
  selected orbit arc draws in with 600 ms animation.
- **Guide mode:** select target (from sheet/search) → edge arrow + haptic ticks accelerating
  as pointing error shrinks ( >30°: none, 30–10°: slow, <10°: quick, <3°: lock haptic +
  glyph flare). `Haptics` expect/actual (Vibrator / UIImpactFeedbackGenerator).
- **Accept:** guide loop feels right on device 🔌 (subjective gate — iterate tick curve);
  hit-testing unit tests.

### P5.5 — HUD
- Top strip: compass-accuracy chip (taps → calibration card with figure-8 animation),
  AR/planetarium toggle, search icon. Status chips are `GlassPanel`s per design system.
- **Accept:** LOW accuracy state visibly prompts calibration; card dismisses on accuracy
  recovery.

### P5.6 — Planetarium mode
- Same renderer; attitude source replaced by drag/pinch virtual-camera controller
  (azimuth/elevation drag with inertia, pinch FOV 30–90°). Default when camera or motion
  permission missing; manual toggle always available.
- **Accept:** works on simulator/emulator (this is the demo-safe mode); inertia feels native.

### P5.7 — Time travel (flagged)
- Scrub control (⏪ live ⏩ up to ±24 h) driving `TrackingEngine.at(t)`; sky + trails render
  for t; "LIVE" chip returns to now. Behind a feature flag — **cut line #3** if the schedule
  slips; flag ships either way.
- Porting source: Stardroid's `TimeTravelClock` design (speed steps, smooth now-transition) —
  Apache-2.0, port with attribution per `docs/plan/07-references.md`.
- **Accept:** scrubbing to a predicted pass time shows the satellite on its arc.

## Exit checklist
- [ ] Point phone at ISS during a pass → glyph sits on the real satellite 🔌 (the checkpoint) —
      **still open.** Everything under it verified indoors on a Pixel 6a; this one needs an
      outdoor pass, so it needs the right ten minutes as well as the right hardware.
- [x] Planetarium fallback + permission-denied paths all land somewhere usable — both modes and
      the toggle between them exercised on device
- [x] 30 fps sustained, zero steady-state allocations — **release build, 40 s of AR: 2413 frames,
      1 janky (0.04%), p50 11 ms, p90 13 ms, p99 15 ms, zero missed vsync**, nothing above 21 ms.
      Post-GC live heap flat across 90 s (6208 → 6284 → 6212 KB) with one background collection in
      ~5400 frames.
- [ ] Battery drain < 8 %/10 min AR session — **not measured.** The phone charges over the same
      cable adb uses, so a drain figure taken now would be meaningless. Needs wireless adb.
- [x] Projection + declutter + hit-test unit tests green — 90 tests in `feature:arsky`
      (26 projection, 13 camera FOV, 14 declutter, 11 hit-test, 11 guide, 15 virtual camera)
- [ ] BUILDLOG records tick-curve tuning — the curve is written down and pinned by tests, but the
      guide loop was not driven end to end on device (the tap missed and the cable dropped), so
      the subjective gate is untouched

### Verified on device (Pixel 6a, release build, 2026-08-02)

- Camera FOV read from hardware as **41.7° × 65.6°** in portrait. The sensor reports ~67°
  horizontally in landscape; transposed and cropped to a 20:9 screen that is what should come out,
  and it is what `CameraFovMath` produced.
- Sky layer composites correctly over the live preview: horizon, cardinal ticks, class glyphs,
  amber halos on satellites with downlinks, orbit trails, decluttered labels.
- Hit-test → detail sheet opened the right satellite; its numbers tick once a second while the
  glyph moves every frame, which is the two-rate design visible in one screen.
- **Physics validated against the sheet.** NOAA 15: altitude 812 km, speed 7.45 km/s, range
  2018 km at 15.6° elevation, footprint 3061 km — a real 810 km sun-synchronous orbit, internally
  consistent geometry, and its actual 137.350 MHz DSB downlink. Separately, ES'HAIL 2,
  METEOSAT-10, METEOSAT-11 and MTG-S1 all appeared clustered in a narrow band to the west, which
  is what geostationary satellites do and a strong check that the projection is not merely
  plausible.

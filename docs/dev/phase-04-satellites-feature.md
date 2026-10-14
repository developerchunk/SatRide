# P4 — Satellites Feature (`feature:satellites`)

**Objective:** the catalog: browse, search, filter, and a satellite detail screen with live
numbers — first real product screens, establishing the ViewModel/UI patterns every later
feature copies.
**Depends on:** P2, P3. **Duration target:** 3 days.

## Tasks

### P4.1 — Catalog screen
- Segments: **Up now · Popular · Weather · Ham/FM · Crewed · All**.
- Row: name, class glyph, live elevation + az (from `TrackingEngine.snapshots`, 1 Hz),
  next-pass time (from `PassRepository`), radio-halo dot if active transmitter exists.
- Search field (debounced 300 ms → `SatelliteRepository.search`).
- `SatellitesViewModel` per repo-backed segment; state per repo conventions in
  `docs/dev/README.md`.
- **Accept:** VM unit tests (fake repos + Turbine): segment switch, search, live updates;
  smooth 60 fps scroll with 250 rows (no per-frame allocations in row composables — use
  `NumericText` and stable lambdas).

### P4.2 — Filters
- Filter sheet: has-transmitter, band (VHF/UHF/L), min elevation now, class.
- **Accept:** filter state survives navigation (VM-scoped), tests for combination logic.

### P4.3 — Satellite detail screen
- Header: name, class, country, launch date, NORAD id, follow toggle
  (`TrackingEngine.follow`).
- Live block: az / el / range / velocity / sunlit badge — mono type, ticking 1 Hz.
- Next pass card: time-until, maxEl, quality badge (heuristic from
  `docs/plan/03-ux-design.md`) → navigates to `PassDetailRoute` (screen lands P6).
- Transmitters list (from `TransmitterRepository`): freq, mode, status; **[Tune]** button —
  disabled with "radio arrives soon" tooltip until P9 wires it (leave a `RadioLauncher`
  interface injected, no-op impl for now).
- ISS special block behind `noradId == 25544`: crew placeholder + SSTV event banner fed by the
  hosted events JSON (fetch in `core:network`, cache 6 h; harmless if endpoint 404s).
- **Accept:** VM tests; detail opens from catalog on both platforms; live numbers tick.

### P4.4 — Custom TLE import
- Settings-reachable + catalog overflow: paste TLE text → `SatelliteRepository.importTle` →
  typed success/error snackbar.
- **Accept:** malformed input shows friendly error; imported sat appears in All + trackable.

## Exit checklist
- [x] Catalog + search + detail shipped with live data — verified on a Pixel 6a. iOS compiles and
      passes the full simulator suite; on-device iOS is **deferred** (no hardware) and tracked in
      PROGRESS.md's Deferred section.
- [x] VM/UI pattern documented by example — `SatellitesViewModel` is the reference: one
      `StateFlow<UiState>`, one `onEvent`, one effects channel, every stream composed declaratively
- [x] RadioLauncher interface injected (stub), zero TODO-comment debt
- [x] Tests: 47 view-model tests across the three screens
- [x] Scroll perf — **release** build, 260 live rows: 3.5% janky, 50th percentile 11 ms, 90th
      17 ms. (The debug build reads 48% janky at a flat 23–26 ms per frame: uniform Compose
      debug-mode overhead, not an algorithmic cost. Measuring the debug build would have been
      measuring the wrong thing.)
- [x] Import verified end to end on device: pasted element set → stored with `user-import`
      provenance → searchable and classified (GPS BIIR-5 → NAVIGATION from its name alone)

## Corrections to this plan, made while building (see BUILDLOG for detail)
- `TrackingEngine` is consumed through a `SatelliteTracker` interface; its perpetual one-second
  loop cannot be driven by a test's virtual clock.
- The `[Tune]` button is enabled and explains itself rather than being disabled with a tooltip.
  Phones have no hover, so a disabled control has nowhere to put its reason.
- The catalog-wide staleness banner reports time since the last fetch, not the oldest element set;
  the per-satellite epoch age moved to the detail screen.

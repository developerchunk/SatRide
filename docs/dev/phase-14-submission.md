# P14 — Shipaton Submission

**Objective:** the Devpost submission that wins. **Hard deadline: Sep 30, 23:45 PDT — submit
by Sep 28.**
**Depends on:** P13. **Duration target:** W8–9 (video work starts W7).

## Tasks

### P14.1 — Demo video (≤ 2 min, YouTube/Vimeo public)
- Script beats (lock by Sep 15): cold open on real sky AR pan → ISS glides through frame →
  tap → radio card + waterfall ignition → Doppler counter-drift close-up → SSTV image painting
  live → gallery share → 5-s KMP architecture flash (module graph, "one Kotlin codebase,
  Android + iOS side-by-side") → logo + store badges.
- Capture plan: screen-record both platforms side-by-side segment; real-pass footage windows —
  check ARISS SSTV event calendar first; fallback targets: SO-50/AO-91 FM pass audio,
  NOAA/Meteor waterfall visuals. Shoot ≥ 3 passes to get one great take 🔌.
- Rule: everything shown is real (fixture-driven B-roll only if labeled "recorded pass replay").
- **Accept:** 2 external viewers understand the product without narration help; captions
  included; uploaded unlisted → public at submission.

### P14.2 — Devpost entry
- Fields: description (product story → KMP story, in that order),
  store URL(s), video link, icon 1024², screenshots 1179×2556, team, category selections
  (Ship Kotlin Everywhere + Design + #BuildInPublic + any qualifying).
- **Judge access:** the app is free with nothing locked, so no codes are needed; paste an
  rtl_tcp note into the testing-instructions field: "radio features testable without hardware via
  any rtl_tcp server; the app is fully functional with zero hardware."
- KMP write-up (extra points): link blog post + `orbitk`/`rtlsdr-kmp` repos + devlog thread
  archive; include the shared-code percentage (compute via cloc: commonMain vs platform) —
  expect ~90 %+; state it precisely, not roundly.
- **Accept:** entry preview reviewed against the checklist in `docs/plan/01-hackathon.md`;
  submitted ≥ 48 h early; confirmation screenshot archived.

### P14.3 — #BuildInPublic archive
- Collate the weekly devlog posts (started P0) into a thread/page linked from Devpost;
  final "we shipped" post with the video on submission day.
- **Accept:** archive link works logged-out.

### P14.4 — Post-submission hygiene
- Tag `v1.0-shipaton` on the exact submitted build; freeze branch; open `v1.1` milestone
  (LRPT decoder first — see plan 05/06); monitor stores for crash spikes daily through Oct 20
  (The Shippies) — judges will be using the live app.
- **Accept:** tagged, frozen, monitoring dashboard (Crashlytics/console) checked into routine.

## Exit checklist
- [ ] Submitted by Sep 28 with all assets; confirmation archived
- [ ] Video public, captioned, honest
- [ ] Judge testing path verified from a clean device 🔌
- [ ] v1.0 tagged; v1.1 (LRPT) milestone opened

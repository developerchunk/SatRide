# P13 — Release Engineering & Ecosystem Contribution

**Objective:** both stores approved and live, libraries published. **Calendar-anchored:**
Apple submission **Sep 12**, Play **Sep 15** — start P13.1 while P12 is in flight.
**Depends on:** P16 (P12 parallel). **Duration target:** spread across W6–W8.

## Tasks

### P13.1 — Release builds & signing
- Android: release keystore (user-held; document storage), R8 rules (Room, Ktor,
  serialization — verify release build byte-for-byte feature parity), AAB, versioning scheme
  `1.0.0 (code = build number)`.
- iOS: distribution cert + profile, bitcode-free archive, TestFlight internal from first
  archive; crash symbolication verified.
- CI release lanes: tagged commit → AAB + IPA artifacts.
- **Accept:** release builds pass the P12 critical-flow list (release ≠ debug behavior is the
  classic trap: minification, FGS).

### P13.2 — Store assets
- 1024×1024 icon (+ Android adaptive layers, iOS sizes), screenshots **1179×2556 no frame**
  (Shipaton spec — shoot: AR with ISS, waterfall+Doppler HUD, SSTV painting, pass timeline,
  hero), short promo video for Play listing (reuse demo-video footage from P14).
- Listing copy both stores: title "SatRide — Satellite AR & Radio", subtitle, description
  (keyword pass: satellite tracker, ISS, SDR, SSTV, weather satellite), what's-new.
- **Accept:** all assets in `store/` directory, checked against each store's current specs.

### P13.3 — Compliance package
- Privacy policy + terms hosted (simple static page); iOS privacy manifest + nutrition labels;
  Play Data Safety form (location: on-device use only, no sale; camera: not stored).
- Permission strings (the review-critical ones): camera ("show satellites over the real sky"),
  location ("compute which satellites are above you"), motion, local notifications,
  background audio justification (iOS), FGS `connectedDevice` justification (Play).
- Export compliance (standard encryption exemption), content rating questionnaires.
- **Accept:** dry-run against App Review Guidelines checklist; no "minimum functionality"
  risk (app is fully functional without hardware — highlight in review notes + provide a
  demo-mode note for reviewers: FakeSdrSource-backed demo source is **debug-only**; reviewers
  assess the tracking features + rtl_tcp docs).

> **Carried over from P6 — the iOS half of alerts and deep links is compile-verified only.**
>
> Android's whole P6 loop was walked on an emulator; iOS has never run any of it, and the two pieces
> at risk have no simulator-equivalent worth trusting:
> - `UNUserNotificationCenter` authorisation and delivery via `UNCalendarNotificationTrigger`
>   (`IosAlertScheduler`). The failure mode is a reminder that silently never arrives.
> - `CFBundleURLTypes` + SwiftUI's `onOpenURL` → `MainViewControllerKt.handleDeepLink`. The failure
>   mode is a notification that opens the app on whatever screen it was last on.
>
> Both must be walked on an iPhone **before** the Sep 12 submission, because both are user-visible
> features the store listing will describe. Android found three real bugs in exactly this path — a
> constructor-order crash, an activity `launchMode` fault and a consume race — and none of them were
> visible to the unit tests. Budget an afternoon, not ten minutes, and do it early enough that a fix
> can still make the submission.

### P13.4 — Submissions 🔌 (calendar-anchored)
- **Sep 12:** App Store submission (expect 1 rejection cycle; answer within hours, resubmit).
- **Sep 15:** Play production (or open testing → production promote same week).
- Track review status daily in PROGRESS.md Blockers; any rejection → BUILDLOG with resolution.
- **Accept:** both live before Sep 30 (any date within window qualifies for Shipaton).

### P13.5 — Ecosystem contribution: publish `orbitk` + `rtlsdr-kmp`
- Extract `core:orbit` → `orbitk` and `core:sdr` (+ minimal DSP needed by sources) →
  `rtlsdr-kmp` as standalone repos: README with usage samples, API docs, Apache-2.0 license,
  CI, sample app snippet; publish to Maven Central (Sonatype namespace `dev.duosages`);
  submit both to **Klibs.io**; app depends on the published artifacts (or keeps internal copy
  — decide by risk; published-artifact dependency is the better story if time allows).
- Write the KMP architecture blog post (doubles as Shipaton write-up §"how we used KMP/CMP").
- **Accept:** both libraries resolvable from Maven Central; Klibs listings live; post published.

## Exit checklist
- [ ] Both stores live 🔌
- [ ] Compliance artifacts archived in `store/compliance/`
- [ ] `orbitk` + `rtlsdr-kmp` on Maven Central + Klibs.io; blog post live
- [ ] Release retro notes in BUILDLOG (review cycle times, gotchas)

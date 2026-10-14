# 06 — Roadmap: 9 Weeks (Aug 1 → Sep 30)

Working rule: **the app must be shippable from week 6 onward**. Everything after week 6 makes
it better, not possible. Store review time is a hard external dependency — Apple submission
target **Sep 12**, Play **Sep 15**.

## Milestones

### Week 1 (Aug 1–8) — Skeleton + orbit core
- Repo, module graph, CI (GitHub Actions: build both targets, run common tests), Koin, nav
  shell, design tokens, theme.
- `core:orbit`: SGP4 port + golden-vector tests passing; coordinate chain; Doppler math.
- `core:network`/`data`/`database`: CelesTrak + SatNOGS fetch, Room schema, bundled catalog
  snapshot.
- Register on Devpost; claim JetBrains offers; **first #BuildInPublic post**.

### Week 2 (Aug 8–15) — Tracking engine + sensors
- `TrackingEngine` (1 Hz propagation, 30 Hz interpolation), pass prediction + tests.
- `core:sensors` actuals both platforms; smoothing filter; WMM declination.
- Satellites tab (catalog/search/detail, live numbers) — first end-to-end screens.

### Week 3 (Aug 15–22) — AR sky
- Camera actuals (CameraX / AVFoundation) + real-FOV projection; sky Canvas layer: glyphs,
  labels + declutter, horizon, trails.
- Guide-arrow + haptics; planetarium fallback; detail sheet over AR.
- **Checkpoint: the Star Walk moment works on both phones.** (Aug 25: JetBrains Q&A webinar.)

### ⏸ Scope change — 2026-08-04

Weeks 1–3 below are history and happened as written. From week 4 the plan changed: **the radio
track is deferred to post-v1.**

**What v1 is now:** an AR satellite tracker with a 3D play globe — point the phone at the sky and
see what is above you, spin the Earth and watch the constellation, get told before a pass, keep an
account and a profile.

**Why.** The radio half is the largest, riskiest and least demonstrable part of the plan. It needs a
clean-room USB driver, a full DSP chain, and — for anyone to *see* it work — a dongle, an antenna,
an outdoor pass, and a satellite that happens to be transmitting at the time. Against a fixed
deadline that is a lot of engineering standing between the work and anything a judge can watch. The
sky and globe halves are built, run on a phone today, and are the parts a person actually opens the
app for. Two things done well beats four done badly.

**What it costs.** "RF" in the name goes unearned for one release, and the Doppler-corrected NFM
demo — genuinely the most technically impressive thing in the plan — does not ship in v1. That is a
real loss and worth being clear-eyed about. It is deferred, not deleted: `core:sdr`, `core:dsp`,
`core:audio` and `feature:radio` all still build, and `docs/dev/phase-07..10` are intact contracts
with resume notes.

### Week 4 (Aug 22–29) — Passes, account, first run
- P6 passes and alerts — **done**, verified on device including the notification → AR loop.
- P15 identity: optional Google sign-in, Firestore profile, profile tab.
- P16 first run: splash with real progress and an 8 s cap, shimmer, proper permission flows.

### Week 5 (Aug 29–Sep 5) — Make Play the centrepiece
- The globe is the app's best asset and now carries the demo. Depth: satellite selection and
  follow-cam polish, orbit families, day/night terminator, a genuine sense of scale.
- Whatever "game" means here gets decided and prototyped this week, not later — it is the one
  remaining open product question and it is on the critical path.
- Flagship devlog post: the vector Earth and how it is drawn in common Compose.

### Week 6 (Sep 5–12) — Onboarding → **Apple submission**
- Onboarding choreography.
- App icon, store listing, screenshots.
- **Submit to App Store review Sep 12.**

### Week 7 (Sep 12–19) — Polish + Play submission
- P12: motion pass, empty and edge states, perf and battery on min-spec, accessibility, device
  matrix. The 1 Hz passes timeline and the globe's ride mode are the two profiling targets.
- **Play submission Sep 15.** Address any Apple rejection immediately.

### Week 8 (Sep 19–26) — Ship + ecosystem contribution
- Public release on both stores as approvals land.
- Extract and publish **`orbitk`** (SGP4, coordinate chain, pass prediction, WMM) to Maven Central.
  `rtlsdr-kmp` moves to post-v1 with the rest of the radio track — `orbitk` alone is a real
  contribution and is the one that is finished, tested against AIAA vectors, and dependency-free.
- Demo video (2 min): globe spin → AR pan → tap the ISS → guide arrow → pass alert firing.

### Week 9 (Sep 26–30) — Submit

## Cut lines (pre-decided, in order)

Cuts 1–6 below were all inside the radio track and are **superseded**: the whole track is deferred,
so there is nothing left to shave off it. The live cut lines for the revised v1 are:

1. Time-travel scrubbing in AR → detail-sheet pass preview only *(already built behind a flag, off)*
2. Country and referral fields on the profile → name only
3. Ride-mode camera polish on the globe → orbit view only

**Never cut:** AR quality, the Play globe, onboarding, both-store launch.

<details>
<summary>Superseded radio cut lines (kept for when P7–P10 resume)</summary>

1. LRPT decoder → v1.1
2. Telemetry dashboards for specific cubesats → generic AX.25 frame view only
3. IQ recording → audio recording only
4. OneSignal integration → local notifications only
5. Bonjour discovery for rtl_tcp → manual host:port
</details>

## Risk register

| Risk | L | Impact | Mitigation |
|---|---|---|---|
| Apple review rejection/delay | M | Miss window | Submit Sep 12; app fully functional without hardware; honest permission strings; TestFlight throughout |
| Compass error makes AR feel broken | M | Core UX | Smoothing filter + calibration UX + glow-radius design; test on 4+ devices early |
| "Tracker without the RF" reads as thin | M | Weak submission | Play globe carries the demo; lean on the KMP story (one Kotlin codebase, custom 3D in common Compose) and on `orbitk` as a real ecosystem contribution |
| Firebase iOS setup blocked on Xcode work | M | iOS auth slips | Auth is optional by design, so an iOS build without it still ships; bundle-id mismatch flagged 2026-08-04 |
| CMP iOS camera/sensor interop friction | M | Week 3 slip | UIKitView-hosted preview is a known pattern; JetBrains Discord + Aug 25 webinar as support channels |
| Scope creep | H | Everything | This document. Cut lines are pre-agreed. |

## Post-hackathon (so decisions age well)

**v1.1 is the radio release.** P7–P10 resume in their original order against their original
acceptance criteria: `RtlTcpSource` first (works on both platforms and unblocks everything), then
the Android USB driver, the DSP chain, Doppler correction, and SSTV. That is a genuine second
launch with a genuine story — "the tracker you already have now listens too" — rather than a
feature buried in a point release.

v1.2: LRPT, rotator/antenna helpers, more SSTV modes (Robot36), POCSAG/ADS-B terrestrial modes for
dongle-owners between passes. Desktop (CMP) is a natural fit later — the architecture (rtl_tcp plus
pure-common DSP) already supports it.

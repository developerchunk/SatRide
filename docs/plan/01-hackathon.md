# 01 — Shipaton 2026: Rules, Strategy

## The competition

**Shipaton 2026** ([devpost](https://revenuecat-shipaton-2026.devpost.com/),
[shipaton.com](https://www.shipaton.com/)). Primary target: the JetBrains
**Ship Kotlin Everywhere** award ([announcement](https://blog.jetbrains.com/kotlin/2026/07/know-kotlin-ship-it-everywhere-and-win-at-shipaton-2026/)).

### Hard requirements (must all be true)

1. **Brand-new app** — first public release between **Aug 1 and Sep 30, 2026**. Updates to
   existing apps don't qualify.
2. Published on a real store — **App Store and/or Google Play** (either qualifies; we ship both,
   since Ship Kotlin Everywhere judges cross-platform quality on iOS + Android).
3. Devpost submission by **Sep 30, 2026, 11:45 pm PDT** with:
   - Text description of features
   - **Demo video ≤ 2 minutes**, public on YouTube/Vimeo
   - Store URL(s)
   - **1024×1024** app icon
   - Screenshots at **1179×2556 px, no device frame**

### Ship Kotlin Everywhere award specifics (from the official page, saved locally)

- $30,000 pool: $15k / $10k / $5k across three winners; 1st place also gets the full Shipaton
  winner package (Times Square feature, NYC Shippies trip Oct 20, trophy, media spotlight).
- One Devpost submission competes for this award **plus** the $100k Grand Prize and any other
  qualifying category (an app can win one overall award).
- **Judges score three areas** — our plan explicitly plays all three:
  1. **Cross-platform quality** (core): a creative, polished CMP app on multiple platforms;
     *"consistency and quality matter more than platform count."* Android + iOS done excellently
     beats four platforms done adequately.
  2. **Community interaction** (optional, extra points): devlog / blog / social posts that are
     actually enjoyable — this stacks with the #BuildInPublic award below.
  3. **Ecosystem contribution** (optional, extra points): publish a library, PR a KMP library,
     open-source a reusable piece. **Our plan: extract and publish two KMP libraries —
     `orbitk` (pure-Kotlin SGP4/pass-prediction) and `rtlsdr-kmp` (RTL-SDR USB/rtl_tcp source).**
     Both are genuinely novel in the KMP ecosystem (nothing like them on Klibs.io), and
     open-sourcing the driver also neutralizes any GPL question around librtlsdr-derived code.
- JetBrains' recommended stack for the award is CMP + Ktor + Room + kotlinx.serialization +
  Koin — **our architecture uses exactly this list** (see 02-architecture.md), which
  makes the write-up land effortlessly.
- Offers to claim: 3 months IntelliJ IDEA Ultimate (Ship Kit), 2 months Junie. **Aug 25 live Q&A
  webinar** — attend; Discord has JetBrains advocates for unblocking (useful for CMP-on-iOS
  camera/sensor questions).
- Submission wants a short write-up of *how* KMP/CMP was used + links to public sharing — we
  draft this as we go (it's the devlog, repurposed).

### Secondary awards worth playing for (same work, more tickets)

| Award | Why SatRide fits | Extra effort |
|---|---|---|
| **#BuildInPublic** ($30k/$20k/$10k) | AR satellites + live decodes are extremely postable | Post 2–3×/week on X/Threads from week 1: AR clips, first-signal moments, waterfall GIFs |
| **Design Award** | The whole pitch is Star Walk-level polish | None beyond the plan |
| **Keep Them Coming Back** (OneSignal, $25k 1st) | Pass alerts are a natural re-engagement loop | Optional: route pass notifications through OneSignal — decide week 6, only if cheap |

## Why SatRide can win

1. **Demo-video gold.** Two minutes of: phone pans across the sky → ISS glides through AR →
   tap → waterfall lights up → Doppler-corrected voice/SSTV image paints in live. No other
   Shipaton entry will look like this.
2. **A real KMP showcase.** SGP4 orbital mechanics, a DSP/decoder chain, and sensor-fusion AR
   all in `commonMain`, with surgical expect/actual for USB/camera/sensors — the exact
   "shared where it counts, native where it matters" narrative JetBrains judges reward.
3. **Genuine product, not a demo.** Satellite tracking apps (Look4Sat, ISS Detector, Star Walk)
   have large audiences; nobody has merged tracking + SDR reception into one mobile app with
   consumer polish. There's a real gap and a real community (r/RTLSDR, r/amateursatellites,
   ham operators, weather-image hobbyists) that will amplify it — which also feeds the
   traction-based Grand Prize.
4. **Works without hardware.** Critical: the app must be great for a user with *no dongle*
   (AR tracking + passes + alerts), so the store audience isn't capped by SDR ownership. The
   SDR is the "holy crap" upgrade, not the entry fee.

## Submission checklist (owned by week 9, prepared earlier)

- [ ] Google Play listing live (open testing → production; start review **by Sep 15**)
- [ ] App Store listing live (submit **by Sep 12** — Apple review + possible rejection buffer;
      note: camera, location, and background-audio usage strings must be airtight)
- [ ] 2-min demo video: script in week 7, shoot real passes week 8 (check ISS SSTV event calendar;
      always-on targets: NOAA/Meteor passes, FM ham sats like SO-50)
- [ ] 1024² icon + 1179×2556 screenshots (design in week 6 alongside store listings)
- [ ] Devpost write-up: product story + KMP architecture story
- [ ] #BuildInPublic thread archive linked

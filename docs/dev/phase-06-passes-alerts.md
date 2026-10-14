# P6 — Passes & Alerts (`feature:passes`, `core:notifications`)

**Objective:** the planning loop — see upcoming passes, understand them at a glance, get
notified, land in AR at the right moment. This is also the retention mechanic.
**Depends on:** P3 (P5 for deep-link target). **Duration target:** 3 days.

## Tasks

### P6.1 — Pass timeline screen
- Vertical schedule from `PassRepository.upcoming(24h | 7d)`. Group by day; "now" indicator
  line.
- Pass card: satellite name/glyph, AOS local time + countdown, duration, maxEl, **polar-plot
  thumbnail** (mini az/el arc), radio-quality badge 🟢🟡⚪ (heuristic: maxEl ≥ 40° & active
  transmitter → 🟢; ≥ 20° → 🟡; else ⚪).
- Filters: followed-only toggle, min maxEl slider, radio-capable toggle.
- **Accept:** VM tests incl. day grouping + countdown ticking; empty state ("Quiet sky…" per
  design doc) implemented.

### P6.2 — Polar plot composable (`core:designsystem`)
- Reusable `PolarPassPlot(arc: List<TimedTopo>)`: N-E-S-W ring, elevation rings 0/30/60,
  arc path with AOS/TCA/LOS markers, optional live satellite dot (for in-pass state).
  Sizes: thumbnail (56 dp) and full (min 240 dp).
- **Accept:** screenshot test with a golden fixture arc; renders identically both platforms.

### P6.3 — Pass detail screen
- Full polar plot, elevation-vs-time curve, Doppler curve preview (from P1.6 samples),
  transmitter quick-list, actions: **[Remind me]** (→ P6.4), **[Open in Sky]**
  (→ `SkyRoute` guided at this satellite; if pass is future, opens time-travel when flag on,
  else guides now).
- **Accept:** navigation from timeline + from satellite detail (P4.3 card) both work.

### P6.4 — Local notifications (`core:notifications`)
```kotlin
interface AlertScheduler {
    suspend fun schedule(alert: PassAlert): Result<Unit>   // fires at aos - lead(10 min default)
    suspend fun cancel(alertId: String)
    fun scheduled(): Flow<List<PassAlert>>
}
```
- androidMain: `AlarmManager.setExactAndAllowWhileIdle` + POST_NOTIFICATIONS permission flow +
  channel "Pass alerts"; reschedule-on-boot receiver; body like
  *"ISS pass in 10 min — max 78°, rises W"*.
- iosMain: `UNUserNotificationCenter` calendar triggers; permission via onboarding/first use.
- Invalidation: TLE refresh shifts pass times → recompute + resched silently if drift > 60 s.
- Storage: alerts in Room (reuse `PassCacheEntity` linkage).
- **Accept:** fake-clock tests for scheduling/invalidation math; on-device: notification fires
  and deep-links 🔌.

### P6.5 — Deep links
- `satride://sky/detail/{satId}?guide=true` registered (Android intent-filter, iOS custom URL
  scheme + `onOpenURL` → nav): notification tap → app opens → Sky tab → target guided.
- **Accept:** cold-start and warm-start deep link both land correctly on both platforms.

## Exit checklist
- [x] Timeline → detail → remind → notification → AR guided: full loop demonstrated 🔌 — walked on
      a Pixel 6a emulator (Android 16) on 2026-08-04, including a reboot. Found and fixed three
      defects the unit tests could not see; see BUILDLOG. **iOS is compile-verified only** and is
      scheduled against `phase-13-release.md`, before the Sep 12 submission
- [x] Pass math surfaces (plots/curves) reusable from design system — `PolarPassPlot` lives in
      `core:designsystem` and takes its own `SkyPoint`, so it stays free of domain types and can be
      rendered from a test or a preview with no data layer behind it
- [x] Empty/stale-TLE states designed per plan 03 — "Quiet sky" is distinguished from "no location
      yet" and from "your filters hide everything"; a pass whose orbit was refreshed away gets its
      own "that pass is gone" screen rather than a blank one

## Amendments made during implementation (P6)

Contracts are law until amended; these were amended, and every dependent call site was updated in
the same change.

- `AlertScheduler` gained `val capability: AlertCapability` — the interface as written could not
  express "supported but inexact" or "supported but not yet permitted", both of which are ordinary
  states on Android 12+.
- `PassRepository` gained `suspend fun refreshUpcoming(window: Duration): Int`.
- `SatRideRoute.PassDetail(passId: String)` → `PassDetail(noradId: Int, aosEpochMillis: Long)`, and
  `SatelliteDetailEffect.OpenPass` (P4.3) carries the AOS with it.
- The deep link's home moved from `composeApp/navigation` to `core:common/SkyDeepLink`, and
  handling goes through `SkyGuideRequests` rather than Navigation Compose's own deep-link support —
  the target is a tab, which must not gain a back-stack entry per notification tap.
- `SkyGuideRequests.consume()` was **removed** after the emulator run. A request has two readers
  that must both act on it — the sky view, which guides, and the app shell, which has to be showing
  the sky view — and whichever consumed first left the other with nothing. Each reader now tracks
  the highest sequence it has handled. `MainActivity` also requires `launchMode="singleTop"`, which
  is a prerequisite of the deep link rather than a preference: without it every link starts a second
  activity with a second view model racing for the same request.

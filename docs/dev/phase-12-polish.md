# P12 — Polish (motion, perf, resilience, accessibility)

**Objective:** the pass that turns "working" into "Star Walk-level". Runs as a checklist sweep
across every surface; nothing new, everything better.
**Depends on:** P9, P10, P16. **Duration target:** 4–5 days (time-boxed — polish expands to
fill any container).

## Tasks

### P12.1 — Motion sweep (per plan 03 §Motion)
- [ ] Orbit-arc draw-in on selection (600 ms decelerate) + 40 % dim of rest
- [ ] Detail sheet spring-up; shared-element thumbnail→gallery transition
- [ ] Signal-lock moment: squelch-open/decoder-sync → haptic tick + amber ignite animation
- [ ] Passband cursor spring on tune; waterfall cadence fixed & judder-free
- [ ] Tab transitions, onboarding page choreography
- All springs from `MotionSpec` only — audit for ad-hoc `tween`s.
- **Accept:** side-by-side screen recordings reviewed against plan 03; no animation > 600 ms.

### P12.2 — Empty/edge states sweep (per plan 03 §Empty states)
- [ ] Quiet sky, stale TLE chip, magnetic interference, SDR detach mid-session, offline
  refresh failure, denied-permission variants, empty gallery, no-transmitter satellite
- **Accept:** every state reachable in a debug menu ("state gallery") and visually designed —
  no default Material empty boxes.

### P12.3 — Performance & battery
- Profile on min-spec Android (SD 6xx class) + oldest supported iPhone:
  AR 30 fps sustained; AR+decode combined session caps AR at 30 fps; app cold start < 2 s to
  first frame; steady-state allocations flat in AR and decode loops.
- Battery: 10-min AR session < 8 %; 15-min decode session < 10 % (log methodology + numbers).
- Fix the top findings; record before/after in BUILDLOG.
- **Accept:** numbers logged; no regression vs P5/P8 exit budgets.

### P12.4 — Resilience sweep
- [ ] Process death/restore: session state, nav state, in-progress decode (decoder restarts
  cleanly, artifact partials preserved)
- [ ] Rotation/foldable/tablet-size sanity (support landscape in radio/decode at minimum)
- [ ] Clock skew, TLE fetch failures, corrupted DB (fallback to snapshot re-seed)
- [ ] rtl_tcp flaky-network chaos test (delay/drop injector in FakeServer)
- **Accept:** chaos scenarios scripted and passing.

### P12.5 — Accessibility & intl
- [ ] TalkBack/VoiceOver labels on all interactive elements; AR announces guided-target
  bearing changes; waterfall has a textual S-meter alternative
- [ ] Dynamic type sanity (numerics scale without layout breaks); touch targets ≥ 44 dp
- [ ] All strings externalized (English-only v1, but extraction done); units metric/imperial
- **Accept:** screen-reader pass through onboarding→AR→radio→decode on both platforms.

> **Carried over from P6 — three things only real hardware can settle.**
>
> 1. **Exact alarms.** The Pixel 6a *emulator* denies `SCHEDULE_EXACT_ALARM`, so P6's pass alerts
>    have only ever run on the inexact fallback — `dumpsys` showed a 33-second delivery window on a
>    ~40-second alarm. On a real phone the permission may be grantable, and the open product
>    question is whether to ask for it at all: a pass reminder that lands three minutes late is
>    still useful, and an extra permission prompt is not free. Measure the actual lateness on a
>    device across a few alerts, then decide. If the answer is "ask", the request flow belongs in
>    the onboarding choreography, not bolted on here.
> 2. **Battery with alerts armed.** Add to P12.3's budget: an `RTC_WAKEUP` per armed reminder, plus
>    the reconcile pass that runs after every element-set refresh. Expected to be negligible — it is
>    a handful of wakeups a day — but it has never been measured, and "expected to be negligible" is
>    how background battery drain always starts.
> 3. **The 1 Hz timeline on a min-spec device.** The Passes screen rebuilds its whole state every
>    second so countdowns stay honest. With the 24-hour window that was ~440 rows on the
>    emulator's catalog. It scrolled fine there; it has not been profiled on the min-SDK device, and
>    it is the obvious candidate for the P12.3 sweep after the AR view.

### P12.6 — Device matrix 🔌
- Android: min-SDK device, mid-range, flagship, one tablet; dongles: RTL-SDR Blog V3, V4,
  generic R820T2. iOS: oldest supported iPhone, current iPhone, (iPad compat sanity — not a
  target, must not crash).
- **Accept:** matrix table in BUILDLOG with pass/fail per critical flow; all criticals green.

## Exit checklist
- [ ] Motion + empty-state sweeps complete against plan 03
- [ ] Perf/battery numbers logged and within budget on min-spec
- [ ] Chaos + process-death scenarios green; accessibility pass done
- [ ] Device/dongle matrix green on critical flows

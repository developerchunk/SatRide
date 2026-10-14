# P16 — First Run: splash, permissions, shimmer (`feature:startup`, `core:permissions`)

**Objective:** the first thirty seconds. A splash that is doing real work and says so, permission
requests that are asked for at a moment they make sense, and screens that degrade to a shimmer
rather than a spinner when data has not landed yet.
**Depends on:** P6, P15. **Duration target:** 2–3 days.

## The rule

**Eight seconds, hard.** Whatever has or has not finished, the user is on the home screen by then.
A splash that waits for the network is a splash that never ends on a train, and "it hung on the
loading screen" is the single most common one-star review an app like this gets.

Everything the splash was waiting for keeps running afterwards. Screens that need it show a shimmer
placeholder; they do not block, and they do not show a spinner, because a spinner says "wait" and a
shimmer says "this is coming".

## Tasks

### P16.1 — Startup orchestration
- `StartupCoordinator` exposes `StateFlow<StartupProgress>` over the real steps, each of which
  already exists and is currently invisible: seed the bundled catalog, refresh element sets,
  parse the globe's vector Earth, take a first location fix, restore the auth session.
- Every step is independently failable and independently reported. A failed TLE refresh is not a
  failed startup — the bundled catalog is three years of satellites and works offline.
- **Accept:** unit tests with a fake clock covering all-fast, one-slow, one-failed and all-failed.

### P16.2 — The splash itself
- Progress is **measured, not animated** — the same rule the pull-to-refresh bar already follows.
  It names the step ("Loading orbital data", "Building the globe"), because a named pause explains
  itself and a bare bar does not.
- Visually: the globe assembling. It is the app's best asset and it is already being built during
  those seconds anyway.
- **Accept:** on a device with the network disabled the splash still completes and hands off.

### P16.3 — The eight-second cap
- A race between "everything finished" and an eight-second timer. Whichever wins, the handoff is
  the same call, so there is exactly one path into the home screen.
- **Accept:** a test that holds every step open forever and asserts handoff at 8 s; a test that
  completes everything in 200 ms and asserts handoff then, not at 8 s.

### P16.4 — Shimmer
- `SatRideShimmer` in `core:designsystem`: a placeholder box matching the shape of what is coming.
  Used by the catalog rows, the pass cards and the profile screen.
- **Accept:** shimmer appears only where data is genuinely still loading, and never on a screen
  whose data is simply *empty* — "no passes tonight" is an answer and must not look like a wait.

### P16.5 — Permissions, properly
- `core:permissions`: one `PermissionController` contract for camera and location, covering the
  states the current ad-hoc code in `feature:arsky` conflates — `NOT_REQUESTED`, `GRANTED`,
  `DENIED`, `PERMANENTLY_DENIED`, `UNSUPPORTED`, plus **`APPROXIMATE`** for location.
- **Rationale before the system dialog**, once, explaining what the permission buys. A prompt with
  no preceding context is the most reliable way to earn a permanent refusal, and here it would cost
  the app its hero feature.
- **Precise location:** Android 12+ lets a user grant approximate from inside the dialog. That is a
  supported way to use SatRide — 2 km of observer error is about 0.14° at satellite range — so it is
  never treated as a refusal. The upgrade to precise is offered once, explained in those terms,
  and never nagged.
- **Permanently denied** opens the app's settings page, on both platforms, rather than re-asking a
  dialog the OS will no longer show.
- **Accept:** every state reachable and correctly rendered on both platforms 🔌; declining
  everything still leaves a usable app (planetarium mode + manual location).

## Exit checklist
- [ ] Home screen reached within 8 s with the network off, airplane mode, and a cold cache
- [ ] Splash progress reflects real work, and names the step it is on
- [ ] Shimmer only where data is loading; empty states still read as answers
- [ ] Camera and location: rationale, grant, deny, permanently-denied and approximate all handled
- [ ] Denying everything leaves a working app

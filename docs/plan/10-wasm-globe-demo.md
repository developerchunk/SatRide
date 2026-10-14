# 10 — The wasm globe demo: scope

**Status:** scoped, not started. **Priority: after the Play release is live.** Nothing in this document is on the critical path for Sep 30.

---

## What this is, and what it is emphatically not

A single page — `satride.developerstring.com/globe` — running SatRide's real SGP4 propagator,
compiled from the same Kotlin the phone runs, drawing a spinning Earth with a few hundred satellites
in their actual orbits. Open it, drag the planet, watch the ISS go round. No install, no account,
no download.

**It is not a platform entry, and pretending otherwise would cost points.** The Shipaton rules are
explicit: submissions must be "fully published to Apple's App Store, the Google Play Store, or the
Samsung Galaxy Store", and web apps do not qualify outside the Next Gen Award. Worse, the Ship
Kotlin Everywhere criteria say in as many words that *"consistency and quality matter more than
platform count"* — so a fourth, half-working target actively weakens the entry.

What it *is* is a **link in the write-up**: one URL a judge can click to see that the orbital
mechanics are genuinely shared Kotlin rather than a claim in a paragraph. That is worth something,
and it is worth nothing if it ships broken or late.

## Why `core:orbit` is the one module that can do this

Everything that blocks a full Compose-for-Web port of SatRide is in the data layer, not the UI:

| Blocker | Where it bites |
| --- | --- |
| Room 2.8.4 + `sqlite-bundled` 2.7.0 | no wasmJs target; `core:database` and the bundled catalogue rest on it |
| `androidx.datastore` 1.2.1 | every setting |
| GitLive Firebase 2.5.0 | auth and the Multi-Play rooms; wasmJs is a different target from js |
| `core:sensors`, camera, AR | a rewrite of every actual, not a port |

`core:orbit` touches **none** of them. Verified from the build files rather than assumed:

```
core:orbit   →  api(core:model), implementation(kotlinx.datetime)   + explicitApi()
core:model   →  implementation(kotlinx.datetime)
```

Fourteen files of pure Kotlin, `explicitApi()` already on because the module is earmarked for
extraction as the `orbitk` library. kotlinx.datetime supports wasmJs. So the propagator crosses for
free; the only real work is drawing.

## The renderer is the actual scope

`GlobeRenderer` lives in `feature:play` and imports `core.orbit`, `core.model`, Compose UI
graphics — and also `feature.play.PlayGroup` and `feature.play.solar.*`, which drag the solar-system
mode and, through the feature module, the whole data layer. It cannot come across as-is.

`SphereMesh.kt` next to it, by contrast, imports nothing but `kotlin.math`.

**Option A — a self-contained demo module (recommended for a first cut).** A new
`:demo:globe-web` with `wasmJs()` as its only target: Compose Multiplatform for web, `SphereMesh`
moved down into a shared module (it has no dependencies to untangle), and a deliberately small
renderer written for the demo — sphere, day/night terminator, satellite dots, no labels, no solar
system, no Multi-Play. Depends on `core:orbit` and `core:model` and nothing else.

**Option B — extract `core:globe` properly.** Cut `PlayGroup` and the solar dependency out of
`GlobeRenderer`, move it to a module depending only on `core:orbit`, `core:model` and
`compose-ui`, and have both the app and the demo render through it. This is the right end state and
it is not a deadline-week job: the entanglement is real and the app's globe is the best thing in the
product to break.

Start with A. If A lands and B becomes worth doing, A's renderer is deleted rather than migrated,
which is the correct relationship between a demo and a product.

## Scope

**In:**

- `wasmJs()` added to `core:orbit` and `core:model` (the convention plugin currently declares
  Android, iOS arm64, iOS simulator and JVM — this is a fifth target on two leaf modules, not a
  project-wide change).
- A bundled TLE snapshot as a resource — a few hundred objects, generated at build time from the
  same CelesTrak data the app ships. No network call, no Room, no cache.
- Drag to spin, pinch/scroll to zoom, a real-time clock and a fast-forward toggle.
- One page, published alongside the existing React site.

**Out, and stated so nobody scope-creeps back into it:** search, the catalogue, pass predictions,
reminders, AR, Multi-Play, the solar system, and any persistence. The demo
stores nothing.

## Tasks

- [ ] **D1** Add `wasmJs()` to `core:model` and `core:orbit`; confirm both compile and that
      `core:orbit`'s existing `commonTest` suite runs on the new target. **This is the go/no-go
      gate** — if the propagator does not cross cleanly, stop here and the idea is dead cheap.
- [ ] **D2** Move `SphereMesh.kt` to a module both `feature:play` and the demo can see; no
      behaviour change, and the existing `GlobeRendererSnapshotTest` must still pass.
- [ ] **D3** `:demo:globe-web` — Compose Multiplatform wasmJs, minimal renderer, bundled TLE.
- [ ] **D4** Publish at `/globe` on the existing site; link it from the Devpost write-up.

## Risks, in the order they will actually bite

1. **The Skiko shader-sampling difference.** The app's globe already has one known gotcha in how
   Skiko samples the Earth texture; the wasm target runs Skiko on a canvas and there is no reason to
   assume it behaves identically. Budget for the globe looking subtly wrong before it looks right.
2. **Bundle size.** Compose for web is not small. A demo that takes fifteen seconds to appear is
   worse than a screenshot. Measure before committing to publish it.
3. **Compose Multiplatform wasmJs maturity** at 1.11.1 — fine for a canvas and pointer input, less
   fine for anything resembling a real app. Which is another argument for keeping the scope to a
   canvas and pointer input.

## The honest exit criterion

If D1 does not pass in an afternoon, drop it. The write-up is already strong — Android, iOS and
desktop from one codebase, with SGP4, sensor fusion and the renderer all in `commonMain` — and a
broken web link attached to it makes it weaker, not stronger.

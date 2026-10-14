# SatRide Visual Language — "Cosmic Playground"

The app's UI direction: **Kurzgesagt-style flat vector illustration**, game-like motion, and
tactile (haptic) feedback. Everything is drawn — no bitmaps, no icon fonts — so it scales to any
density on Android, iOS and desktop from one common Compose codebase.

Reference frames live in `docs/design/reference/` (see its README). They come from
Kurzgesagt's "Secret Base on the Moon" short and define the look we are borrowing:

## The rules of the style

1. **Flat shapes, no gradients inside objects.** An object is 2–4 solid fills: a body colour, a
   darker shade of the same hue for the shadowed side, a lighter tint for highlights. Depth comes
   from *palette steps*, never from blurs or soft shadows.
2. **Everything is rounded.** Corners are generous (10–32 dp). Clouds, craters, rockets, panels —
   all built from circles, capsules and rounded rects. Nothing has a sharp corner.
3. **Deep indigo space, saturated toys.** The world is a dark blue-indigo stage; the actors on it
   are highly saturated: sunshine yellow, cyan, magenta, coral, mint, lavender. Yellow windows
   glow against navy hulls.
4. **Layered parallax scenery.** Backgrounds are 2–3 bands of the same hue at different
   lightness (cloud banks, crater rims, horizon arcs). Nearer = lighter or more saturated.
5. **Chunky, physical controls.** Buttons look pressable: a face colour sitting on a darker
   "edge" of the same hue, which compresses when pressed (Duolingo-style 3D press).
6. **Bouncy, overshooting motion.** Things pop in with overshoot, float with slow sine bobbing,
   and settle with springs. Nothing fades in linearly if it can bounce in.
7. **Haptics accompany motion.** Button presses, tab switches and page turns tick; earned
   moments (finish onboarding, signal lock) get the heavy click.

## Palette (`core/designsystem/theme/Color.kt`)

| Role | Colour | Notes |
|---|---|---|
| Base | `#111539` | deep indigo space |
| Surface | `#1B2150` | panels, cards |
| SurfaceElevated | `#262E68` | floating chrome, nav bar |
| DeepShadow | `#0B0E2A` | button edges, dark bands |
| TextPrimary | `#F4F6FF` | |
| TextSecondary | `#A8B3E6` | |
| Tracking (cyan) | `#3FE0FF` | orbits, tracking domain |
| Radio (amber) | `#FFC94D` | RF domain |
| Artifact (magenta) | `#FF54C8` | decoded artifacts |
| Sunshine | `#FFE14D` | primary CTA yellow, glowing windows |
| Coral | `#FF7E5C` | rockets, warm props |
| Lavender | `#8E7BFF` | purple props, secondary scenes |
| Mint | `#B9F6D8` | cloud highlights, foliage |
| SkyBlue | `#4D7DFF` | mid-blue scenery band |
| Success / Warning / Danger | `#43F0A0` / `#FFD84D` / `#FF5C74` | |

Night mode (astronomy red-on-black) still collapses every accent to red — the illustrated art
respects it by drawing with `SatRideTheme.accents`, never raw hex.

## Type

Rounded-feeling, heavy hierarchy: display/headline = ExtraBold/Bold, labels = Bold with wide
tracking. (A bundled rounded font — e.g. Nunito or Baloo 2, OFL-licensed — can be dropped into
`core/designsystem/src/commonMain/composeResources/font/` later to complete the look; the type
scale is already tuned for it.)

## Motion vocabulary (`theme/MotionSpec.kt`)

- `playful()` — 350 ms overshoot tween: pop-ins, page-turn content
- `springPop()` — medium-bouncy spring: selection indicators, scale on press
- `float` bobbing — 2.5–4 s sine loops for floating props (see `art/Floating.kt`)
- existing `fast()/standard()/celebratory()` remain for functional motion

## Art library (`core/designsystem/art/`)

Reusable Canvas vector props, all theme-aware: `StarField`, `CloudBank`, `SpaceBackground`,
`KurzPlanet`, `KurzEarth`, `KurzMoon`, `KurzSatellite`, `KurzRocket`, `KurzGroundStation`,
`RadioWaves`. Compose them into scenes; onboarding is the showcase.

## Haptics

`LocalSatRideHaptics` (design system) → adapter over `core/sensors` `Haptics` (Android
`VibrationEffect`, iOS `UIImpactFeedbackGenerator`, desktop no-op). Buttons and tabs fire
`tap()`; completing onboarding fires `success()`.

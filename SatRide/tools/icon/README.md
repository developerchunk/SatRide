# App icon

The SatRide mark, generated rather than drawn by hand. **Edit the scripts, never the PNGs** — the
PNGs are output and are overwritten wholesale on every run.

```bash
cd tools/icon && python3 emit_icons.py
```

Needs `pillow` and `numpy`.

## What comes out

| Output | Where |
| --- | --- |
| Legacy launcher icons, square and round, 5 densities | `androidApp/src/main/res/mipmap-*/ic_launcher{,_round}.png` |
| Adaptive icon layers: background, foreground, monochrome | `androidApp/src/main/res/mipmap-*/ic_launcher_{background,foreground,monochrome}.png` |
| iOS app icon, plus dark and tinted variants | `iosApp/.../AppIcon.appiconset/` |
| Desktop icons: `.icns` (macOS, rounded to Apple's grid), `.ico` (Windows), `.png` (Linux + window) | `desktopApp/src/main/icons/`, `desktopApp/src/main/resources/icons/` |
| The 1024 px master | `SatRide/logo.png` |

`logo-original.png` is the hand-designed source the recreation was based on. It is kept for
reference and is not used by the build.

## Two things worth knowing before editing

**Everything is downscaled from one master.** Rendering each density separately looks equivalent
and is not: stars are sized in supersampled pixels, so on the 48 px canvas they came out five per
cent of the icon wide — mdpi was a handful of white blobs. Anything drawn at a fixed pixel size has
that problem, and downscaling a single master cannot.

**The adaptive split is not cosmetic.** The background is the space-and-Earth plate and bleeds past
every mask shape; the foreground is the satellite alone, scaled into the 72dp safe zone so no
launcher mask can clip it. `SAFE` in `emit_icons.py` is that ratio.

## The coastlines

`make_icon.py` holds the same continent outlines as the app's globe
(`CelestialArt.kt`'s `EarthLandmasses`) and projects them the same way, so the icon and the planet
inside the app are the same world. If you change one, change the other — they diverge silently
otherwise, and nobody notices until the two are seen side by side.

`VIEW_LON` picks which face of the Earth the icon shows.

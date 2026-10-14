# 07 — Open-Source References & Data Sources

The two repos that inspired SatRide, what to learn (and what *not* to copy) from each, and the
full catalog of satellite data sources verified from Look4Sat's production code.

## Reference repositories

### Look4Sat — https://github.com/rt-bishop/Look4Sat
Radio satellite tracker & pass predictor for Android (Kotlin, Compose, coroutines), inspired
by Gpredict. **License: GPL-3.0.**

**Learn from it:**
- Data-source strategy: multi-source TLE ingestion + SatNOGS transmitters (full URL list
  below, extracted from its `core/domain/.../source/Sources.kt`).
- Repository split (`DatabaseRepo` / `SatelliteRepo` / `SelectionRepo` / `SettingsRepo`) and
  offline-first posture: fetch → Room → everything computed locally.
- Pass-prediction UX conventions: 10-day windows, polar trajectory plots, min-elevation
  filtering, weekly-TLE-update guidance, radio tracking service abstraction
  (`IRadioTrackingService` — it drives external rotators/rigs; our analog is the in-app SDR).
- Its `satelliteModes` list (58 modes, below) is a ready-made taxonomy for classifying
  SatNOGS transmitter entries in our UI filters.

**License policy — study-and-reimplement:** Look4Sat is GPL-3.0. We **actively read its
codebase** as a reference — it solves the same data layer and processing problems we have
(TLE ingestion, multi-source merging, transmitter handling, pass caching, tracking service
design), and learning from a production-proven implementation is the whole point. Copyright
protects *expression*, not knowledge: algorithms, processing approaches, API usage, schemas,
and architecture are free to learn and reuse. The rules that keep us safe:

1. **Read to understand, then close the file and write our own.** Never copy code, and never
   translate a file/function line-by-line into our codebase (a Kotlin-to-Kotlin "port" of GPL
   code is still a derivative work). Our code should express the same *ideas* in our own
   structure, naming, and design — which it will anyway, since ours is KMP/commonMain with
   different abstractions.
2. Facts are free: URLs, register values, protocol details, mode lists, API response shapes,
   constants dictated by external systems — copy freely (already done in this doc).
3. High-risk math stays on independent lineage: our SGP4 comes from the public-domain Vallado
   reference, **not** Look4Sat's predict4java-derived code — never use their propagator as
   the reference while implementing ours (validate against test vectors instead).
4. When a nontrivial design is adopted after studying their code, note it in the module's
   docs ("approach informed by Look4Sat's X") — good provenance hygiene and honest credit.

### Stardroid (Sky Map) — https://github.com/sky-map-team/stardroid
The original Google Sky Map: AR sky-pointing planetarium for Android (Java/Kotlin).
**License: Apache-2.0.**

**Learn from it:**
- Sensor-fusion camera-overlay approach (no ARCore) — validates our P5 architecture choice.
- Its `astronomy/` and `control/` layers: attitude smoothing, coordinate transforms,
  time-travel controller design, and the layer/renderer split (`layers/` feeding a renderer)
  which our `TrackingEngine` → sky-Canvas mirror.
- Hard-won lessons in its issue tracker about compass calibration UX and device sensor
  variance — worth a skim before P3.3/P5.5 tuning.

**License policy — learn and copy freely:** Apache-2.0 — unlike Look4Sat, we may read,
copy, and port Stardroid code without restriction; the only obligation is attribution (add a
NOTICE entry + license header note on ported files). The practical constraint isn't legal,
it's technical: Stardroid is Android-centric Java, and everything we need must live in Kotlin
`commonMain` — so its code is a **porting source**, not a paste source. Translate the logic
into idiomatic KMP Kotlin (our `Vector3`/`Quaternion` types, Flows instead of listeners, no
Android sensor APIs in common code).

Parts worth porting/adapting directly (map to our phases):
- Attitude smoothing + sensor-noise handling in `control`/`touch` layers → P3.3 smoothing
  filter starting point (then tune on device).
- Coordinate/astronomy math in `math`/`ephemeris` utilities (rotation matrices, RA-Dec↔Az-El
  patterns) → cross-check for P1.2/P1.4 (our chain is TEME-based, but their transforms are a
  proven sanity reference).
- Time-travel controller design (`TimeTravelClock`) → P5.7 scrub behavior (speed steps,
  smooth transition to "now").
- Zoom/FOV-to-screen projection handling → P5.2 projector edge cases (screen rotation,
  FOV clamping).

## Verified data sources (from Look4Sat `Sources.kt`, production-proven)

### TLE / satellite catalogs

| Source | URL | Notes for SatRide |
|---|---|---|
| CelesTrak (all active) | `https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv` | We use `FORMAT=json` + per-group queries (`stations`, `weather`, `amateur`, `cubesat`, `visual`) for smaller payloads — same API |
| AMSAT | `https://amsat.org/tle/current/nasabare.txt` | Amateur-satellite-focused bare TLEs; good redundancy for ham sats |
| SatNOGS TLE | `https://db.satnogs.org/api/tle/?format=3le` | 3le format; alternative/fallback TLE source tied to the same DB as transmitters |
| ARISS | `https://live.ariss.org/iss.txt` | Dedicated fresh ISS TLE — use for the ISS special-case path |
| McCants classified | `https://www.mmccants.org/tles/classfd.zip` | Community-maintained TLEs for objects absent from CelesTrak; **zip, classic-TLE parse; v1.1 candidate** (needs unzip + niche audience) |
| McCants integrated | `https://www.mmccants.org/tles/inttles.zip` | Same, integrated set |
| R4UAB | `https://r4uab.ru/satonline.txt` | Community list; low priority, unreliable availability |

**SatRide v1 policy:** primary = CelesTrak GP JSON per group; ISS = ARISS URL; fallback on
CelesTrak failure = SatNOGS 3le; AMSAT as amateur-group redundancy. McCants/R4UAB deferred to
v1.1 alongside custom-TLE import polish. Multi-source merge rule: newest epoch wins per NORAD id.

### Transmitters

| Source | URL | Notes |
|---|---|---|
| SatNOGS transmitters | `https://db.satnogs.org/api/transmitters/?format=json&status=active` | Exactly what Look4Sat uses — note the `status=active` server-side filter (adopt it; simpler than client filtering). License CC-BY-SA → attribution in About + satellite detail |

### Transmitter mode taxonomy (Look4Sat's 58-mode list, adopt as enum/labels)

`4FSK, 64-QAM, AFSK, AFSK TUBiX10, AHRPT, AM, APT, ASK, BPSK, BPSK PMT-A3, CERTO, CW, DATV,
DBPSK, DOKA, DPSK, DQPSK, DSB, DSTAR, DUV, DVB-S2, FFSK, FM, FMN, FSK, FSK AX.100 Mode 5,
FSK AX.100 Mode 6, FSK AX.25 G3RUH, FT8, GENESIS FSK, GFSK, GFSK Pkst, GFSK Rktr, GFSK/BPSK,
GMSK, GMSK USP, HRPT, LoRa, LRPT, LSB, MFSK, MSK, MSK AX.100 Mode 5, MSK AX.100 Mode 6, OFDM,
OQPSK, PPM, PSK, PSK31, PSK63, QPSK, QPSK31, QPSK63, SIDLOC, SQPSK, SSDV, SSTV, UNKNOWN, USB, WSJT`

SatRide maps these onto decoder capability: `FM/FMN` → NFM decoder; `SSTV` → SSTV decoder;
`AFSK`/`FSK AX.25 G3RUH` → AX.25 decoder; `LRPT` → LRPT (stretch); everything else →
"listen only" (NFM/raw audio where sensible).

### Other data (not from Look4Sat)

| Data | Source | Used in |
|---|---|---|
| SGP4 verification vectors | AIAA/Vallado public-domain reference distribution | P1.3 golden tests |
| WMM coefficients | NOAA NCEI (public domain) | P1.7 declination |
| ISS SSTV event windows | ARISS announcements → our tiny hosted JSON | P4.3 banner |
| SSTV/AFSK/LRPT test recordings | Community sample archives (verify per-file licenses before bundling in repo fixtures) | P10 fixtures |

## Attribution requirements checklist (About screen)
- SatNOGS DB (CC-BY-SA) — data attribution
- CelesTrak — courtesy credit (requested by T.S. Kelso)
- ARISS / AMSAT — courtesy credit
- Stardroid (Apache-2.0) — NOTICE entry + header note on any ported files (expected: yes —
  smoothing filter and time-travel controller at minimum)
- Look4Sat, Gpredict — "inspired by" credit (studied, no code copied — per policy above)

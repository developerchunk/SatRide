# Network fixtures

Real responses, captured 2026-08-02. Recorded rather than hand-written so the parsers are tested
against what the servers actually send — including the parts that are messy.

| File | Source | Notes |
|---|---|---|
| `celestrak_stations.json` | `https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json` | Complete response. Mean elements in CCSDS OMM form, **not** TLE text |
| `ariss_iss.txt` | `https://live.ariss.org/iss.txt` | Complete response. Same ISS element set as the CelesTrak capture above, which is what makes the cross-source test possible |
| `amsat_nasabare_head.txt` | `https://amsat.org/tle/current/nasabare.txt` | First 40 lines. Bare 3-line sets, no `0 ` prefix |
| `satnogs_3le_head.txt` | `https://db.satnogs.org/api/tle/?format=3le` | First 60 lines. Mixes prefixed (`0 NAME`) and unprefixed name lines in one file — the first entry has no prefix and the rest do |
| `satnogs_transmitters_sample.json` | `https://db.satnogs.org/api/transmitters/?format=json&status=active` | 100 of 2,904 records, selected rather than sampled: one example of each of the 50 distinct `mode` strings present, plus every awkward shape — null downlink, null `norad_cat_id`, inverting transponders, wideband passbands, records with a baud rate, and all 41 ISS transmitters |

## Why the ISS appears twice

`celestrak_stations.json` and `ariss_iss.txt` were captured minutes apart and carry the **same
element set** (epoch `26213.79682580`). CelesTrak serves it as JSON mean elements; ARISS serves it
as TLE text. Rendering the JSON through `TleFormatter` must reproduce the ARISS bytes exactly.

That is the strongest check available for the JSON path — two unrelated endpoints, one expected
answer, and no reliance on our own code being right. If either endpoint's element set is refreshed
independently the fixtures will need recapturing together; the test says so when it fails.

## Recapturing

```
curl -s "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json" -o celestrak_stations.json
curl -s "https://live.ariss.org/iss.txt" -o ariss_iss.txt
```

Capture those two in the same minute, or the epochs will differ and
`GpElementDtoTest.celesTrakJsonReproducesTheArissTextForTheSameElementSet` will fail for a reason
that is not a bug.

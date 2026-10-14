#!/usr/bin/env python3
"""Build the Play globe's vector-surface data from Natural Earth (public domain).

The illustrated globe draws continents as real vector polygons projected onto the
sphere — crisp at any zoom, unlike any bitmap. This script turns Natural Earth
GeoJSON into two compact LOD binaries the app bundles:

  earth_vec_lod0.bin — simplified, for the whole-globe view
  earth_vec_lod1.bin — detailed, for ride-mode close-ups

Inputs (fetch from https://github.com/nvkelso/natural-earth-vector /geojson):
  ne_50m_land.geojson
  ne_50m_geography_regions_polys.geojson   (only featurecla == 'Desert' is used)
  ne_50m_glaciated_areas.geojson
  ne_50m_lakes.geojson

Binary format, big-endian:
  int32 magic 0x53564543 ('SVEC'), int32 layerCount,
  per layer: int32 ringCount, per ring: int32 pointCount, pointCount × (lat f32, lon f32)
Layer order: land, desert, glacier, lake.

Needs: python3 (stdlib only).
"""
import json
import math
import struct

def rings_of(path, want=None):
    with open(path) as f:
        gj = json.load(f)
    out = []
    for feat in gj["features"]:
        props = feat.get("properties", {})
        if want and props.get("featurecla") != want and props.get("FEATURECLA") != want:
            continue
        geom = feat["geometry"]
        polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
        for poly in polys:
            if poly:
                out.append(poly[0])  # outer ring only; holes are covered by the lake layer
    return out

def dp(points, tol):
    """Douglas–Peucker in degree space (fine at these scales)."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy)
        worst, wd = -1, tol
        for i in range(a + 1, b):
            px, py = points[i]
            if norm < 1e-9:
                # Degenerate chord (closed ring endpoints coincide): fall back to
                # distance from the anchor so the ring cannot silently collapse.
                d = math.hypot(px - ax, py - ay)
            else:
                d = abs(dx * (ay - py) - dy * (ax - px)) / norm
            if d > wd:
                worst, wd = i, d
        if worst >= 0:
            keep[worst] = True
            stack.append((a, worst))
            stack.append((worst, b))
    return [p for p, k in zip(points, keep) if k]

def bbox_diag(ring):
    xs = [p[0] for p in ring]
    ys = [p[1] for p in ring]
    return math.hypot(max(xs) - min(xs), max(ys) - min(ys))

def clip_half(pts, inside, intersect):
    out = []
    if not pts:
        return out
    prev = pts[-1]
    pin = inside(prev)
    for cur in pts:
        cin = inside(cur)
        if cin != pin:
            out.append(intersect(prev, cur))
        if cin:
            out.append(cur)
        prev, pin = cur, cin
    return out

def clip_rect(ring, x0, x1, y0, y1):
    """Sutherland–Hodgman against a lon/lat rectangle."""
    def ix(a, b, x):
        t = (x - a[0]) / (b[0] - a[0])
        return (x, a[1] + (b[1] - a[1]) * t)
    def iy(a, b, y):
        t = (y - a[1]) / (b[1] - a[1])
        return (a[0] + (b[0] - a[0]) * t, y)
    pts = clip_half(ring, lambda p: p[0] >= x0, lambda a, b: ix(a, b, x0))
    pts = clip_half(pts, lambda p: p[0] <= x1, lambda a, b: ix(a, b, x1))
    pts = clip_half(pts, lambda p: p[1] >= y0, lambda a, b: iy(a, b, y0))
    pts = clip_half(pts, lambda p: p[1] <= y1, lambda a, b: iy(a, b, y1))
    return pts

def grid_split(rings, cell_deg):
    """Tile rings into a lon/lat grid so the renderer's cone cull has small targets.

    Without this, a continent's bounding cone always intersects the visible cap and the
    whole ring gets clipped and tessellated every frame — the difference between ride mode
    at 60 fps and ride mode at 15. Shapely produces true MultiPolygons per cell, so pieces
    are genuinely disjoint — no zero-width connector bridges rendering as hairline seams.
    A hair of overlap hides antialiasing seams between same-color tiles.
    """
    from shapely.geometry import Polygon, box

    overlap = cell_deg * 0.004
    out = []
    for ring in rings:
        xs = [p[0] for p in ring]
        ys = [p[1] for p in ring]
        cx0 = int(math.floor((min(xs) + 180) / cell_deg))
        cx1 = int(math.floor((max(xs) + 180) / cell_deg))
        cy0 = int(math.floor((min(ys) + 90) / cell_deg))
        cy1 = int(math.floor((max(ys) + 90) / cell_deg))
        if (cx1 - cx0 + 1) * (cy1 - cy0 + 1) <= 1:
            out.append(ring)
            continue
        poly = Polygon(ring)
        if not poly.is_valid:
            poly = poly.buffer(0)
        for cx in range(cx0, cx1 + 1):
            for cy in range(cy0, cy1 + 1):
                cell = box(
                    cx * cell_deg - 180 - overlap,
                    cy * cell_deg - 90 - overlap,
                    (cx + 1) * cell_deg - 180 + overlap,
                    (cy + 1) * cell_deg - 90 + overlap,
                )
                piece = poly.intersection(cell)
                if piece.is_empty:
                    continue
                geoms = getattr(piece, "geoms", [piece])
                for g in geoms:
                    if g.geom_type != "Polygon" or g.area <= 0:
                        continue
                    coords = [(x, y) for x, y in list(g.exterior.coords)[:-1]]
                    if len(coords) < 3:
                        continue
                    # Bound the worst case: a fjord-coast tile with tens of thousands of
                    # points would spike the frame whenever it scrolls into view. Escalating
                    # simplification keeps every tile inside a predictable render budget.
                    tol = 0.002
                    while len(coords) > MAX_TILE_POINTS:
                        coords = dp(coords, tol)
                        tol *= 2.0
                    if len(coords) >= 3:
                        out.append(coords)
    return out

MAX_TILE_POINTS = 2200

def build(scale, lod_tol, min_diag, out_path, cell_deg=None):
    layers = [
        rings_of(f"ne_{scale}_land.geojson"),
        rings_of(f"ne_{scale}_geography_regions_polys.geojson", want="Desert"),
        rings_of(f"ne_{scale}_glaciated_areas.geojson"),
        rings_of(f"ne_{scale}_lakes.geojson"),
    ]
    blob = bytearray(struct.pack(">ii", 0x53564543, len(layers)))
    total_pts = 0
    for rings in layers:
        packed = []
        for ring in rings:
            if bbox_diag(ring) < min_diag:
                continue
            if len(ring) > 1 and ring[0] == ring[-1]:
                ring = ring[:-1]
            simple = dp(ring, lod_tol)
            if len(simple) < 4:
                continue
            packed.append(simple)
        if cell_deg is not None:
            packed = grid_split(packed, cell_deg)
        for ring in packed:
            total_pts += len(ring)
        blob += struct.pack(">i", len(packed))
        for ring in packed:
            blob += struct.pack(">i", len(ring))
            for lon, lat in ring:  # GeoJSON is lon,lat; we store lat,lon
                blob += struct.pack(">ff", lat, lon)
    with open(out_path, "wb") as f:
        f.write(blob)
    print(f"{out_path}: {total_pts} points, {len(blob) // 1024} KB")

# Three tiers: whole-globe, mid zoom, ride-mode close-up. App size is an accepted cost —
# the close LOD keeps ~1 km coastline fidelity, which is what stays crisp from 400 km up.
# The finer tiers are grid-tiled so the renderer's cone cull only ever pays for what is on
# screen; the far tier stays whole because the entire globe is on screen anyway.
# Every tier is tiled: pieces must always be smaller than the visible cap, or the clip's
# connector chords can wrap around it and flood-fill the planet with one color.
build("50m", lod_tol=0.40, min_diag=1.1, out_path="earth_vec_lod0.bin", cell_deg=45)
build("10m", lod_tol=0.05, min_diag=0.12, out_path="earth_vec_lod1.bin", cell_deg=30)
build("10m", lod_tol=0.008, min_diag=0.05, out_path="earth_vec_lod2.bin", cell_deg=12)

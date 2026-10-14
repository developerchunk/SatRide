#!/usr/bin/env python3
"""Bake the Play globe's city lights into vector points.

The globe's surface is polygon geometry — crisp at any zoom. The night lights were
not: they stayed a 2048x1024 raster stretched across a 56x80 sphere mesh, so as soon
as the camera came in they smeared into orange fog while the coastlines beside them
stayed sharp. The mismatch is what makes it look broken rather than merely soft.

This turns the same NASA Black Marble image into a point set — one entry per lit
place, with an intensity — which the renderer draws as discrete dots. A point has no
resolution, so it is as sharp from 200 km as from 200,000.

Input:  composeApp/src/commonMain/composeResources/files/earth_night.jpg
Output: composeApp/src/commonMain/composeResources/files/earth_lights.bin

Binary format, big-endian (matching earth_vec_lod*.bin):
  int32 magic 0x534C4954 ('SLIT')
  int32 count
  count x (int16 lat_centideg, int16 lon_centideg, uint8 intensity)

Five bytes a point. Centidegrees are about 1.1 km at the equator — far finer than a
city, and far coarser than the float32 the rings use, because a light is a dot and
does not need a coastline's precision.

Needs: python3, Pillow.
"""
import math
import struct
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: pip install Pillow")

SRC = "composeApp/src/commonMain/composeResources/files/earth_night.jpg"
DST = "composeApp/src/commonMain/composeResources/files/earth_lights.bin"

# How many lights to keep.
#
# Every point costs a projection and a draw each frame, so this is a budget, not a
# quality dial. Twelve thousand covers every conurbation on Earth plus the bright
# coastal strings that make a continent recognisable at a glance; past about twenty
# thousand the additions are single-pixel noise in deserts and open ocean (gas flares
# and fishing fleets) that add nothing but cost.
MAX_POINTS = 12000

# Below this the pixel is skyglow, sensor noise or moonlit cloud rather than a place.
MIN_BRIGHTNESS = 34

# Merge lights within this many degrees of each other.
#
# A large city occupies dozens of pixels in the source. Drawn as dozens of overlapping
# dots it becomes exactly the orange blob this script exists to remove, so each cluster
# collapses to one brighter point instead.
MERGE_DEG = 0.45


def main():
    image = Image.open(SRC).convert("L")
    width, height = image.size
    pixels = image.load()

    # Accumulate into a coarse lat/lon grid: sum of brightness and a brightness-weighted
    # centroid, so a merged cluster lands on the bright core of the city rather than the
    # middle of its bounding box.
    cells = {}
    for y in range(height):
        # Equirectangular: row 0 is the north pole.
        lat = 90.0 - (y + 0.5) * 180.0 / height
        # Skip the poles. There is nothing there, and cells converge to zero width so the
        # merge radius stops meaning anything.
        if lat > 84.0 or lat < -84.0:
            continue
        for x in range(width):
            value = pixels[x, y]
            if value < MIN_BRIGHTNESS:
                continue
            lon = (x + 0.5) * 360.0 / width - 180.0
            key = (int(math.floor(lat / MERGE_DEG)), int(math.floor(lon / MERGE_DEG)))
            cell = cells.get(key)
            weight = float(value)
            if cell is None:
                cells[key] = [weight, lat * weight, lon * weight, value]
            else:
                cell[0] += weight
                cell[1] += lat * weight
                cell[2] += lon * weight
                if value > cell[3]:
                    cell[3] = value

    points = []
    for total, lat_sum, lon_sum, peak in cells.values():
        points.append((lat_sum / total, lon_sum / total, peak, total))

    # Keep the brightest by *total* emitted light, not peak: that favours a large city
    # over a single saturated pixel from a gas flare.
    points.sort(key=lambda p: p[3], reverse=True)
    points = points[:MAX_POINTS]

    with open(DST, "wb") as out:
        out.write(struct.pack(">ii", 0x534C4954, len(points)))
        for lat, lon, peak, _total in points:
            out.write(
                struct.pack(
                    ">hhB",
                    max(-9000, min(9000, int(round(lat * 100.0)))),
                    max(-18000, min(18000, int(round(lon * 100.0)))),
                    max(0, min(255, int(peak))),
                )
            )

    print(f"{len(points)} lights -> {DST} ({len(points) * 5 + 8} bytes)")


if __name__ == "__main__":
    main()

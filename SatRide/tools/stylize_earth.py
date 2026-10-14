#!/usr/bin/env python3
"""Turn NASA Blue/Black Marble into the Play tab's illustrated Earth textures.

Real coastlines, friendly flat palette, but *not* featureless: after classifying each
pixel (ocean depth bands, lush/dry land, snow) into flat colors, a high-pass relief
layer from the original imagery is blended back in. That restores mountains, forest
grain and coastline contrast — the detail that keeps ride-mode close-ups sharp —
while the palette stays vector-illustration flat at a glance.

Classification runs at the source's full 5400x2700 and only then downsamples, so
edges come out of the resample crisp instead of pre-blurred.

Inputs (public domain, NASA Earth Observatory), expected in the working directory:
  earth_day_raw.jpg   — Blue Marble world.topo.bathy 5400x2700
    https://eoimages.gsfc.nasa.gov/images/imagerecords/73000/73909/world.topo.bathy.200412.3x5400x2700.jpg
  earth_night_raw.jpg — Black Marble 2016 3600x1800
    https://eoimages.gsfc.nasa.gov/images/imagerecords/144000/144898/BlackMarble_2016_01deg.jpg

Outputs earth_day_toon.jpg / earth_night_toon.jpg; copy them over
composeApp/src/commonMain/composeResources/files/earth_{day,night}.jpg.

Needs: python3, Pillow, numpy.
"""
import numpy as np
from PIL import Image, ImageFilter

OUT_W, OUT_H = 4096, 2048

# --- Day ---
src = Image.open("earth_day_raw.jpg").convert("RGB")
full = np.asarray(src).astype(np.int32)
soft = np.asarray(src.filter(ImageFilter.GaussianBlur(0.8))).astype(np.int32)
r, g, b = soft[..., 0], soft[..., 1], soft[..., 2]
v = soft.max(axis=2)
mn = soft.min(axis=2)
sat = v - mn

out = np.zeros_like(soft)

ocean = (b >= g) & (b >= r)
deep = ocean & (v < 108)
shallow = ocean & ~deep

snow = (~ocean) & (mn > 150) & (sat < 60)
land = (~ocean) & (~snow)
greenish = land & (g * 100 >= r * 92)
lush = greenish & (v < 110)
grass = greenish & ~lush
dry = land & ~greenish
desert = dry & (r > 150) & (g > 115)
brown = dry & ~desert

def paint(mask, color):
    out[mask] = color

paint(deep,    (38, 108, 213))    # deep ocean blue
paint(shallow, (64, 148, 236))    # shelf blue
paint(lush,    (58, 156, 24))     # dense forest green
paint(grass,   (108, 198, 62))    # bright meadow green
paint(desert,  (242, 205, 122))   # sand
paint(brown,   (196, 144, 88))    # dry highlands
paint(snow,    (240, 248, 255))   # ice

flat = Image.fromarray(out.astype(np.uint8)).filter(ImageFilter.MedianFilter(3))
flat_a = np.asarray(flat).astype(np.float32)

# Relief: a high-pass of the real imagery's luminance, stronger over land than ocean
# (a painted ocean should stay calm; painted mountains should not).
lum = np.asarray(src.convert("L")).astype(np.float32)
low = np.asarray(src.convert("L").filter(ImageFilter.GaussianBlur(6))).astype(np.float32)
high = lum - low
relief_gain = np.where(ocean, 0.16, 0.55).astype(np.float32)
shade = 1.0 + (high[..., None] / 255.0) * relief_gain[..., None] * 3.2
# Plus a whisper of large-scale tonal variation so continents read as landscapes.
broad = (low - float(low.mean())) / 255.0
shade *= 1.0 + np.where(ocean, 0.05, 0.14).astype(np.float32)[..., None] * broad[..., None] * 2.0

styled = np.clip(flat_a * shade, 0, 255).astype(np.uint8)
img = Image.fromarray(styled).resize((OUT_W, OUT_H), Image.LANCZOS)
img = img.filter(ImageFilter.UnsharpMask(radius=2, percent=60, threshold=2))
img.save("earth_day_toon.jpg", quality=90)
print("day done", img.size)

# --- Night: warm city glow dots on black (drawn additively in-app) ---
night = Image.open("earth_night_raw.jpg").convert("L")
night = night.resize((2048, 1024), Image.LANCZOS)
n = np.asarray(night).astype(np.float32) / 255.0
glow = np.clip((n - 0.14) * 1.8, 0, 1) ** 0.8
no = np.zeros((1024, 2048, 3), np.float32)
no[..., 0] = glow * 255
no[..., 1] = glow * 196
no[..., 2] = glow * 92
nimg = Image.fromarray(no.astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))
nimg.save("earth_night_toon.jpg", quality=82)
print("night done", nimg.size)

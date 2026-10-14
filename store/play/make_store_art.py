"""
Play Store graphics, drawn from the same scene description as the launcher icon.

The point of sharing `make_icon` rather than art-directing these separately: the icon a user sees
in search results and the feature graphic above the listing are two views of one place. Same
palette, same Earth, same satellite, same star field — so the listing reads as one product rather
than as two pieces of stock art that happen to be adjacent.
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

import make_icon as M

OUT = os.path.dirname(os.path.abspath(__file__))
FONTS = ("/Users/adityashinde/Documents/Developer/crazy/SatRide/SatRide/core/designsystem/"
         "src/commonMain/composeResources/font")

SS = 2  # supersample; the feature graphic is wide, so 2x is plenty and keeps memory sane


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def wide_space(w, h):
    """The deep-space gradient, stretched to a non-square canvas."""
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    xn, yn = x / (w - 1), y / (h - 1)
    top = np.array(M.SPACE_TOP, np.float32)
    deep = np.array(M.SPACE_DEEP, np.float32)
    t = np.clip((yn - 0.05) / 0.85, 0, 1)[..., None]
    img = top * (1 - t) + deep * t
    # The violet bloom sits under the Earth's limb, lower-left as in the icon.
    d = np.sqrt(((xn - 0.10) * 1.6) ** 2 + (yn - 1.15) ** 2)
    g = np.clip(1.0 - d / 1.15, 0, 1) ** 2.2
    img = img + np.array(M.SPACE_GLOW, np.float32) * g[..., None] * 0.9
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def earth_arc(w, h, cx, cy, r):
    """
    The planet as a limb sweeping across the lower edge.

    Same lighting model as the icon — day-side toward the sun, continents clipped inside the
    disc, a hot rim on the lit edge — but with the centre pushed far below the canvas so only
    the top of the limb is in frame.
    """
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    inside = d <= r
    lit = np.clip(((x - cx) / r * M.SUN[0] + (y - cy) / r * M.SUN[1]) * 0.5 + 0.5, 0, 1) ** 1.35

    deep = np.array(M.OCEAN_DEEP, np.float32)
    mid = np.array(M.OCEAN_MID, np.float32)
    edge = np.array(M.OCEAN_EDGE, np.float32)
    lo = deep + (mid - deep) * np.clip(lit / 0.66, 0, 1)[..., None]
    hi = mid + (edge - mid) * np.clip((lit - 0.66) / 0.34, 0, 1)[..., None]
    rgb = np.where((lit < 0.66)[..., None], lo, hi)
    a = np.where(inside, 255.0, 0.0)
    plate = Image.fromarray(np.dstack([np.clip(rgb, 0, 255), a]).astype(np.uint8), "RGBA")

    # Continents, in the band that is actually on canvas.
    land = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ld = ImageDraw.Draw(land)

    def blob(u, v, br, col=M.LAND):
        px, py = cx + r * u, cy + r * v
        rr = r * br
        ld.ellipse([px - rr, py - rr, px + rr, py + rr], fill=col + (255,))

    # Bigger and overlapping, and deliberately straddling the bottom edge: at this crop only a
    # sliver of the globe is on canvas, so small discs read as semicircles sitting on the horizon.
    # Masses that run off the frame read as continents continuing past it.
    for u, v, br in [(-0.28, -0.972, 0.055), (-0.21, -0.948, 0.068), (-0.15, -0.922, 0.058),
                     (-0.24, -0.900, 0.046), (-0.35, -0.950, 0.040), (-0.10, -0.892, 0.036),
                     (0.10, -0.978, 0.052), (0.17, -0.952, 0.066), (0.14, -0.926, 0.056),
                     (0.06, -0.905, 0.040), (0.26, -0.958, 0.046), (0.31, -0.932, 0.038),
                     (0.44, -0.958, 0.030), (0.49, -0.940, 0.024)]:
        blob(u, v, br)

    land = land.filter(ImageFilter.GaussianBlur(SS * 5.0))
    la = np.array(land.split()[3], np.float32)
    land.putalpha(Image.fromarray(np.clip((la - 118) * 7.5, 0, 255).astype(np.uint8)))
    clip = Image.new("L", (w, h), 0)
    ImageDraw.Draw(clip).ellipse([cx - r * 0.955, cy - r * 0.955, cx + r * 0.955, cy + r * 0.955], fill=255)
    land.putalpha(Image.fromarray(
        (np.array(land.split()[3], np.float32) * np.array(clip, np.float32) / 255).astype(np.uint8)))
    plate.alpha_composite(land)

    night = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    night.putalpha(Image.fromarray(np.clip((1 - lit) ** 1.7 * inside * 190, 0, 255).astype(np.uint8)))
    plate.alpha_composite(night)

    glow = np.zeros((h, w, 4), np.float32)
    band = np.clip(1.0 - np.abs(d - r * 0.997) / (r * 0.012), 0, 1) ** 1.4 * inside
    halo = np.clip(1.0 - (d - r) / (r * 0.030), 0, 1) ** 2.6 * (d > r)
    strength = np.clip(lit * 1.25, 0, 1) ** 1.2
    glow[..., :3] = np.array(M.LIMB_HOT, np.float32)
    glow[..., 3] = np.clip((band * 255 + halo * 185) * strength, 0, 255)
    plate.alpha_composite(Image.fromarray(glow.astype(np.uint8), "RGBA"))
    return plate


def feature_graphic(width=1024, height=500):
    w, h = width * SS, height * SS
    img = wide_space(w, h)

    # Stars, kept off the planet and out of the headline's way.
    stars = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sd = ImageDraw.Draw(stars)
    rng = np.random.default_rng(11)
    ecx, ecy, er = w * 0.34, h * 3.62, h * 2.92
    placed = 0
    while placed < 70:
        px, py = rng.random() * w, rng.random() * h
        if math.hypot(px - ecx, py - ecy) < er * 1.02:
            continue
        rr = (0.9 + rng.random() * 1.9) * SS
        roll = rng.random()
        c = (210, 130, 255) if roll < 0.07 else (120, 220, 255) if roll < 0.14 else (235, 238, 255)
        sd.ellipse([px - rr, py - rr, px + rr, py + rr], fill=c + (int(85 + rng.random() * 140),))
        placed += 1
    img.alpha_composite(stars)

    img.alpha_composite(earth_arc(w, h, ecx, ecy, er))

    # The satellite, right of the headline, scaled to the graphic's height.
    sat = M.satellite_layer(int(h * 1.5), scale=0.62)
    box = sat.getbbox()
    sat = sat.crop(box)
    # Fitted by width against the right-hand third, so the solar panels always land inside the
    # frame — sizing by height alone let the wingspan run off the edge.
    target_w = int(w * 0.30)
    scale = target_w / sat.size[0]
    sat = sat.resize((target_w, max(1, int(sat.size[1] * scale))), Image.LANCZOS)
    img.alpha_composite(sat, (int(w * 0.635), int(h * 0.10)))

    d = ImageDraw.Draw(img)

    # Text on the left. Play crops the feature graphic on some surfaces, so nothing important
    # goes within about 6% of any edge.
    x0 = int(w * 0.070)
    title_f = font("baloo2_extrabold.ttf", int(h * 0.215))
    tag_f = font("nunito_semibold.ttf", int(h * 0.082))
    sub_f = font("nunito_medium.ttf", int(h * 0.060))

    ty = int(h * 0.175)
    # A soft shadow under the wordmark so it stays legible if it ever overlaps the limb glow.
    d.text((x0 + 3 * SS, ty + 3 * SS), "SatRide", font=title_f, fill=(4, 6, 26, 170))
    d.text((x0, ty), "SatRide", font=title_f, fill=(246, 248, 255, 255))

    d.text((x0 + 4, int(h * 0.435)), "Satellites overhead, in AR",
           font=tag_f, fill=M.LIMB_HOT + (255,))
    d.text((x0 + 4, int(h * 0.560)), "Live passes · ISS alerts · 3D Earth · Works offline",
           font=sub_f, fill=(176, 186, 224, 255))

    return img.convert("RGB").resize((width, height), Image.LANCZOS)


if __name__ == "__main__":
    icon = M.plate(512).convert("RGB")
    icon.save(os.path.join(OUT, "play-icon-512.png"), optimize=True)
    print("play-icon-512.png", icon.size)

    fg = feature_graphic()
    fg.save(os.path.join(OUT, "play-feature-1024x500.png"), optimize=True)
    print("play-feature-1024x500.png", fg.size)

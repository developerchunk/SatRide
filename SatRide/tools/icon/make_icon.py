"""
Recreate the SatRide mark as vector art, rendered full-bleed with no border.

Everything is drawn at 4x and downsampled, so edges are analytically smooth rather
than resampled from the 1254px source. Three renders come out of the same scene
description: the whole mark, the space+Earth plate alone, and the satellite alone —
which is exactly the split an Android adaptive icon needs.
"""
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# --- palette, sampled from the source mark -----------------------------------
SPACE_TOP = (7, 6, 34)
SPACE_DEEP = (3, 2, 22)
SPACE_GLOW = (48, 22, 112)       # the violet bloom hugging the Earth's limb
OCEAN_DEEP = (22, 28, 150)
OCEAN_MID = (36, 56, 226)
OCEAN_EDGE = (58, 120, 250)
LIMB_HOT = (196, 240, 255)
LAND = (58, 44, 178)
LAND_DARK = (38, 30, 140)
PANEL = (92, 74, 236)
PANEL_DARK = (58, 44, 176)
PANEL_EDGE = (150, 132, 255)
BODY_LIT = (247, 246, 255)
BODY_SHADE = (188, 182, 226)
CAP = (108, 88, 232)
DISH_LIT = (250, 249, 255)
DISH_SHADE = (176, 168, 220)
DISH_RIM = (120, 214, 240)
WAVE = (138, 106, 246)
BOOM = (168, 160, 214)

SS = 4  # supersample factor


def _lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def space_plate(n):
    """Deep-space gradient with a violet bloom in the lower-left, as an RGB array."""
    y, x = np.mgrid[0:n, 0:n].astype(np.float32) / (n - 1)
    top = np.array(SPACE_TOP, np.float32)
    deep = np.array(SPACE_DEEP, np.float32)
    # Vertical fall-off first: the top of the frame is the lightest part of the void.
    t = np.clip((y - 0.05) / 0.75, 0, 1)[..., None]
    img = top * (1 - t) + deep * t

    # Bloom centred just outside the lower-left corner, where the Earth's limb sits.
    d = np.sqrt((x - 0.02) ** 2 + (y - 1.05) ** 2)
    g = np.clip(1.0 - d / 1.05, 0, 1) ** 2.4
    img = img + np.array(SPACE_GLOW, np.float32) * g[..., None] * 0.85
    return np.clip(img, 0, 255).astype(np.uint8)


def draw_stars(draw, n, rng):
    """Scattered stars, kept clear of the Earth so none appear to sit on the planet."""
    ecx, ecy, er = EARTH[0] * n, EARTH[1] * n, EARTH[2] * n
    placed = 0
    tries = 0
    while placed < 46 and tries < 4000:
        tries += 1
        px, py = rng.random() * n, rng.random() * n
        if math.hypot(px - ecx, py - ecy) < er * 1.10:
            continue
        r = (1.0 + rng.random() * 2.0) * SS
        roll = rng.random()
        if roll < 0.08:
            c = (210, 130, 255)
        elif roll < 0.16:
            c = (120, 220, 255)
        else:
            c = (235, 238, 255)
        a = int(95 + rng.random() * 135)
        draw.ellipse([px - r, py - r, px + r, py + r], fill=c + (a,))
        placed += 1


EARTH = (0.02, 1.22, 0.76)  # centre x, centre y, radius — all as fractions of the side
# Smaller and nearer the corner than a limb-matching sphere would be: it puts the same
# horizon through the frame while exposing far more of the globe's face, which is where
# the coastlines are. With the original radius only latitudes above 30N were on canvas.
SUN = (0.50, -0.866)         # the light comes from beyond the upper-right limb

# Longitude of the meridian facing the viewer. The visible crescent of this Earth is the
# band above about 30°N — everything south of it falls off the bottom of the icon — so the
# globe is turned to put the Atlantic, Europe and North Africa in frame rather than empty
# ocean.
VIEW_LON = 0.0

# Coastlines as (longitude east, latitude north), closed. Recognisable rather than
# survey-accurate: the point is that the markings read as *land*, with capes and gulfs and
# a taper, which is the one thing a circle can never do.
CONTINENTS = [
    # North America
    [(-168, 65), (-140, 70), (-110, 73), (-80, 73), (-60, 60), (-55, 47), (-70, 43),
     (-80, 26), (-90, 29), (-97, 26), (-105, 20), (-95, 16), (-84, 10), (-78, 9),
     (-92, 18), (-110, 30), (-124, 40), (-130, 55)],
    # Greenland
    [(-45, 60), (-30, 68), (-22, 76), (-32, 82), (-50, 82), (-58, 75), (-54, 66)],
    # Eurasia
    [(-9, 43), (3, 51), (5, 58), (20, 70), (45, 68), (75, 73), (110, 74), (140, 70),
     (160, 68), (142, 54), (130, 43), (122, 31), (105, 20), (92, 22), (78, 8), (68, 24),
     (58, 30), (45, 42), (28, 40), (12, 44), (0, 40)],
    # Africa
    [(-18, 34), (0, 36), (14, 33), (32, 31), (43, 12), (51, 12), (42, -2), (40, -16),
     (33, -26), (25, -34), (18, -34), (12, -23), (9, -5), (0, 5), (-8, 4), (-16, 12),
     (-17, 22)],
    # South America
    [(-80, 8), (-66, 11), (-52, 5), (-35, -6), (-38, -18), (-48, -28), (-56, -35),
     (-62, -46), (-70, -54), (-74, -46), (-72, -32), (-71, -18), (-78, -6), (-80, 2)],
]


def project(outline, cx, cy, r):
    """
    Orthographic projection of one coastline onto the globe, or None if it is round the back.

    Vertices past the limb are pulled onto it rather than dropped: dropping them cuts the
    coastline off with a straight chord through the middle of the disc.
    """
    lons = [((lon - VIEW_LON + 180) % 360) - 180 for lon, _ in outline]
    # Dropped once most of the outline has gone round the back. Clamping the stragglers onto
    # the limb is what keeps a departing coast intact, but applied to a coastline that is
    # almost entirely behind the globe it collapses the whole shape onto the edge and draws
    # it as a thin thread lying along the limb, which reads as a rendering fault.
    facing = sum(1 for l in lons if math.cos(math.radians(l)) > 0) / len(lons)
    if facing < 0.45:
        return None
    pts = []
    for (_, lat), lon in zip(outline, lons):
        a = math.radians(max(-90.0, min(90.0, lon)))
        b = math.radians(lat)
        pts.append((cx + r * math.cos(b) * math.sin(a), cy - r * math.sin(b)))
    return pts


def smooth(pts, steps=14):
    """
    Rounds a closed polygon by running a quadratic through each vertex.

    Each segment starts and ends at a midpoint and bends around the vertex between them, so
    the outline keeps the shape's capes and bays without any of its corners.
    """
    out = []
    m = len(pts)
    for i in range(m):
        p0 = pts[i - 1]
        p1 = pts[i]
        p2 = pts[(i + 1) % m]
        a = ((p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2)
        b = ((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2)
        for s in range(steps):
            t = s / steps
            u = 1 - t
            out.append((u * u * a[0] + 2 * u * t * p1[0] + t * t * b[0],
                        u * u * a[1] + 2 * u * t * p1[1] + t * t * b[1]))
    return out


def earth_layer(n):
    """The planet: day-lit ocean falling into night, continents, hot limb and atmosphere."""
    cx, cy, r = EARTH[0] * n, EARTH[1] * n, EARTH[2] * n
    y, x = np.mgrid[0:n, 0:n].astype(np.float32)
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    inside = d <= r

    # Lighting runs along the sun direction rather than radially: a purely radial ramp
    # lights the whole limb evenly, which reads as a glowing ring instead of a lit sphere.
    lit = np.clip(((x - cx) / r * SUN[0] + (y - cy) / r * SUN[1]) * 0.5 + 0.5, 0, 1) ** 1.35

    deep = np.array(OCEAN_DEEP, np.float32)
    mid = np.array(OCEAN_MID, np.float32)
    edge = np.array(OCEAN_EDGE, np.float32)
    lo = deep + (mid - deep) * np.clip(lit / 0.66, 0, 1)[..., None]
    hi = mid + (edge - mid) * np.clip((lit - 0.66) / 0.34, 0, 1)[..., None]
    rgb = np.where((lit < 0.66)[..., None], lo, hi)
    a = np.where(inside, 255.0, 0.0)

    plate = Image.fromarray(
        np.dstack([np.clip(rgb, 0, 255), a]).astype(np.uint8), "RGBA")

    # Continents, as real coastlines projected onto the sphere.
    land = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ld = ImageDraw.Draw(land)
    for outline in CONTINENTS:
        pts = project(outline, cx, cy, r)
        if pts is None:
            continue
        ld.polygon(smooth(pts), fill=LAND + (255,))
    # Clip inside the disc, short of the limb so land never touches the hot rim.
    clip = Image.new("L", (n, n), 0)
    ImageDraw.Draw(clip).ellipse(
        [cx - r * 0.955, cy - r * 0.955, cx + r * 0.955, cy + r * 0.955], fill=255)
    land.putalpha(Image.fromarray(
        (np.array(land.split()[3], np.float32) * np.array(clip, np.float32) / 255).astype(np.uint8)))
    plate.alpha_composite(land)

    # Night falls over ocean and land alike, so the shading reads as one sphere.
    night = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    night.putalpha(Image.fromarray(
        np.clip((1 - lit) ** 1.7 * inside * 205, 0, 255).astype(np.uint8)))
    plate.alpha_composite(night)

    # The lit limb, and the atmosphere bleeding outward from it. Both are strongest on
    # the sun side and fade away round the back, which is what makes the sphere read.
    glow = np.zeros((n, n, 4), np.float32)
    band = np.clip(1.0 - np.abs(d - r * 0.995) / (r * 0.020), 0, 1) ** 1.4 * inside
    halo = np.clip(1.0 - (d - r) / (r * 0.055), 0, 1) ** 2.6 * (d > r)
    strength = np.clip(lit * 1.25, 0, 1) ** 1.2
    glow[..., :3] = np.array(LIMB_HOT, np.float32)
    glow[..., 3] = np.clip((band * 255 + halo * 175) * strength, 0, 255)
    plate.alpha_composite(Image.fromarray(glow.astype(np.uint8), "RGBA"))
    return plate


def satellite_layer(n, scale=0.74, dx=0.0, dy=0.0):
    """
    The satellite, plus its transmission arcs.

    Body, booms and panels are drawn square in their own frame and rotated as one
    piece — a tilted rounded rectangle is otherwise a polygon approximation with
    visibly flat corners. The dish and arcs are placed afterwards in world space,
    which is where their aim has to read correctly.
    """
    TILT = 24.0  # degrees clockwise; matches the original mark's rake
    box = int(n * 1.25)
    rig = Image.new("RGBA", (box, box), (0, 0, 0, 0))
    d = ImageDraw.Draw(rig)
    c = box / 2
    u = n * scale  # one unit = the icon's side, before tilt

    def panel(sign):
        x0 = c + sign * u * 0.175
        x1 = c + sign * u * 0.470
        y0, y1 = c - u * 0.145, c + u * 0.145
        lo, hi = min(x0, x1), max(x0, x1)
        d.rounded_rectangle([lo, y0, hi, y1], radius=u * 0.014,
                            fill=PANEL + (255,), outline=PANEL_EDGE + (255,),
                            width=max(1, int(u * 0.008)))
        # Cell divisions: three columns, two rows, as on the source art.
        for k in (1, 2):
            gx = lo + (hi - lo) * k / 3
            d.line([gx, y0, gx, y1], fill=PANEL_DARK + (255,), width=max(1, int(u * 0.009)))
        d.line([lo, c, hi, c], fill=PANEL_DARK + (255,), width=max(1, int(u * 0.009)))
        # A lit inner edge, so the panel reads as catching the sun.
        d.line([lo + u * 0.012, y0 + u * 0.012, hi - u * 0.012, y0 + u * 0.012],
               fill=PANEL_EDGE + (150,), width=max(1, int(u * 0.006)))

    # Booms first, so the panels and body sit on top of them.
    d.line([c - u * 0.20, c, c + u * 0.20, c], fill=BOOM + (255,), width=max(2, int(u * 0.024)))
    panel(-1)
    panel(+1)

    # The bus: a capsule standing across the boom axis, with a lit cap.
    bw, bh = u * 0.150, u * 0.245
    d.rounded_rectangle([c - bw / 2, c - bh / 2, c + bw / 2, c + bh / 2],
                        radius=bw / 2, fill=BODY_LIT + (255,))
    d.rounded_rectangle([c + bw * 0.10, c - bh / 2, c + bw / 2, c + bh / 2],
                        radius=bw * 0.20, fill=BODY_SHADE + (255,))
    d.ellipse([c - bw / 2, c - bh / 2 - bw * 0.16, c + bw / 2, c - bh / 2 + bw * 0.62],
              fill=CAP + (255,))

    rig = rig.rotate(-TILT, resample=Image.BICUBIC, center=(c, c))

    # Place the rig so the bus lands where the source mark puts it.
    layer = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    bus = (0.665 + dx, 0.345 + dy)
    layer.alpha_composite(rig, (int(bus[0] * n - c), int(bus[1] * n - c)))

    wd = ImageDraw.Draw(layer)
    # The dish, slung below-left of the bus on a short mast and aimed at the planet.
    #
    # Angles here follow PIL's convention throughout — degrees clockwise from three
    # o'clock with y running *down* the image — so "down-left" is 148, not 214. The
    # aim direction is shared by the dish's tilt, its horn and its arcs, which is the
    # only way all three end up pointing at the same place.
    AIM = 148.0
    ar = math.radians(AIM)

    # Every offset here is a multiple of `u`, the same unit the rig is built from.
    # Mixing canvas fractions in — which is what this did — leaves the dish and the arcs
    # anchored to the icon while the bus and panels scale with the satellite, so the two
    # halves of the machine drift apart at any scale but the one it was tuned at. That was
    # the "satellite looks misaligned" report: the dish was sitting on the bus instead of
    # hanging off it.
    bx, by = bus[0] * n, bus[1] * n
    dcx = bx - u * 0.150
    dcy = by + u * 0.120
    dr = u * 0.098
    # The mast, drawn from the bus to the back of the dish so the dish reads as slung under
    # the bus rather than stuck to it.
    wd.line([bx - u * 0.030, by + u * 0.030, dcx - math.cos(ar) * dr * 0.4,
             dcy - math.sin(ar) * dr * 0.4],
            fill=BODY_SHADE + (255,), width=max(2, int(u * 0.022)))

    # Drawn face-on in its own tile — open face toward -x, i.e. 180 — then turned by
    # the difference onto the aim. Same trick as the rig, and for the same reason.
    m = dr * 2.4
    dish = Image.new("RGBA", (int(m * 2), int(m * 2)), (0, 0, 0, 0))
    dd = ImageDraw.Draw(dish)
    dd.ellipse([m - dr * 0.62, m - dr, m + dr * 0.62, m + dr], fill=DISH_SHADE + (255,))
    dd.ellipse([m - dr * 0.58, m - dr * 0.92, m + dr * 0.26, m + dr * 0.92], fill=DISH_LIT + (255,))
    dd.arc([m - dr * 0.62, m - dr, m + dr * 0.62, m + dr], 90, 270,
           fill=DISH_RIM + (255,), width=max(2, int(u * 0.013)))
    dish = dish.rotate(180 - AIM, resample=Image.BICUBIC)
    layer.alpha_composite(dish, (int(dcx - m), int(dcy - m)))

    # The feed horn, out in front of the dish on its own stub.
    hx, hy = dcx + math.cos(ar) * u * 0.050, dcy + math.sin(ar) * u * 0.050
    wd.line([dcx, dcy, hx, hy], fill=BODY_SHADE + (255,), width=max(2, int(u * 0.013)))
    hr = u * 0.018
    wd.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=BODY_LIT + (255,))

    # Three transmission arcs, centred on the horn and opening along the aim.
    for i, k in enumerate((0.080, 0.125, 0.170)):
        rr = u * k
        wd.arc([hx - rr, hy - rr, hx + rr, hy + rr], AIM - 50, AIM + 50,
               fill=WAVE + (255 - i * 30,), width=max(2, int(u * 0.016)))
    return layer


def render(n, space=True, sat=True, sat_scale=0.74, sat_dx=0.0, sat_dy=0.0, seed=7):
    """Compose one plate at `n` px, already supersampled by the caller."""
    if space:
        img = Image.fromarray(space_plate(n), "RGB").convert("RGBA")
        stars = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        draw_stars(ImageDraw.Draw(stars), n, np.random.default_rng(seed))
        img.alpha_composite(stars)
        img.alpha_composite(earth_layer(n))
    else:
        img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    if sat:
        img.alpha_composite(satellite_layer(n, sat_scale, sat_dx, sat_dy))
    return img


def plate(size, **kw):
    return render(size * SS, **kw).resize((size, size), Image.LANCZOS)

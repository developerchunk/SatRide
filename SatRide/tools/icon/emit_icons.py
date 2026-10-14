"""Emit every launcher-icon asset the three platforms need, from the one scene description."""
import json
import os
import shutil
import subprocess
import tempfile
import numpy as np
from PIL import Image, ImageDraw
import make_icon as M

ROOT = "/Users/adityashinde/Documents/Developer/crazy/SatRide/SatRide"
RES = os.path.join(ROOT, "androidApp/src/main/res")
IOS = os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
# jpackage reads the platform icons from here; the window icon is loaded from the classpath.
DESKTOP = os.path.join(ROOT, "desktopApp/src/main/icons")
DESKTOP_RES = os.path.join(ROOT, "desktopApp/src/main/resources/icons")

# Legacy square/round mipmaps, and the 108dp adaptive layers, per density.
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Fraction of the 108dp adaptive canvas that survives every mask shape. Anything the
# user must actually see — the satellite — has to live inside it.
SAFE = 72.0 / 108.0


def write(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print(f"  {os.path.relpath(path, ROOT)}  {img.size[0]}px")


def circle_mask(img):
    n = img.size[0]
    mask = Image.new("L", (n * 4, n * 4), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, n * 4 - 1, n * 4 - 1], fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask.resize((n, n), Image.LANCZOS))
    return out


def centred_satellite(n, coverage=SAFE):
    """The satellite alone, scaled and centred to sit inside the adaptive safe zone."""
    big = n * M.SS
    raw = M.satellite_layer(big, scale=1.0)
    box = raw.getbbox()
    art = raw.crop(box)
    side = max(art.size)
    target = int(big * coverage)
    scale = target / side
    art = art.resize((max(1, int(art.size[0] * scale)), max(1, int(art.size[1] * scale))),
                     Image.LANCZOS)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.alpha_composite(art, ((big - art.size[0]) // 2, (big - art.size[1]) // 2))
    return out.resize((n, n), Image.LANCZOS)


def monochrome(n):
    """Themed-icon layer: the satellite's silhouette in flat white."""
    art = centred_satellite(n, coverage=SAFE * 0.92)
    solid = Image.new("RGBA", (n, n), (255, 255, 255, 0))
    # Alpha is pushed toward opaque so the thin booms survive the tint.
    a = np.array(art.split()[3], np.float32)
    solid.putalpha(Image.fromarray(np.clip(a * 1.9, 0, 255).astype(np.uint8)))
    return solid


# Every asset is downscaled from one high-resolution master rather than re-rendered per density.
#
# Re-rendering looked equivalent and was not. Stars are sized in supersampled pixels, so at the
# 48 px canvas they came out five per cent of the icon wide — the mdpi launcher icon was a
# handful of white blobs with a satellite behind them, while the 1024 px master was a starfield.
# Anything drawn at a fixed pixel size has this problem; downscaling one master cannot.
print("Android legacy mipmaps")
master = M.plate(512)
for density, size in LEGACY.items():
    full = master.resize((size, size), Image.LANCZOS)
    write(full.convert("RGB").convert("RGBA"), f"{RES}/mipmap-{density}/ic_launcher.png")
    write(circle_mask(full), f"{RES}/mipmap-{density}/ic_launcher_round.png")

print("Android adaptive layers")
# The plate bleeds past every mask, so nothing about it is confined to the safe zone.
bg_master = M.render(512 * M.SS, sat=False).resize((512, 512), Image.LANCZOS)
fg_master = centred_satellite(512)
mono_master = monochrome(512)
for density, size in ADAPTIVE.items():
    write(bg_master.resize((size, size), Image.LANCZOS).convert("RGB").convert("RGBA"),
          f"{RES}/mipmap-{density}/ic_launcher_background.png")
    write(fg_master.resize((size, size), Image.LANCZOS),
          f"{RES}/mipmap-{density}/ic_launcher_foreground.png")
    write(mono_master.resize((size, size), Image.LANCZOS),
          f"{RES}/mipmap-{density}/ic_launcher_monochrome.png")

print("iOS app icon")
master = M.plate(1024).convert("RGB")
write(master, f"{IOS}/app-icon-1024.png")
write(master, f"{IOS}/app-icon-1024-dark.png")
# Tinted: iOS drives the tint from luminance, so the source has to be greyscale.
grey = master.convert("L").convert("RGB")
write(grey, f"{IOS}/app-icon-1024-tinted.png")

contents = {
    "images": [
        {"filename": "app-icon-1024.png", "idiom": "universal",
         "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "dark"}],
         "filename": "app-icon-1024-dark.png", "idiom": "universal",
         "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "tinted"}],
         "filename": "app-icon-1024-tinted.png", "idiom": "universal",
         "platform": "ios", "size": "1024x1024"},
    ],
    "info": {"author": "xcode", "version": 1},
}
with open(f"{IOS}/Contents.json", "w") as fh:
    json.dump(contents, fh, indent=2)
    fh.write("\n")
print("  Contents.json")

# The in-app source of truth, kept next to the project as the master artwork.
write(M.plate(1024), f"{ROOT}/logo.png")


def macos_icon(square, n=1024):
    """The plate on Apple's icon grid: inset to 824/1024 and rounded to the Big Sur squircle.

    jpackage applies no mask of its own. A full-bleed square in the Dock sits visibly larger and
    sharper-cornered than every icon beside it, so the shape is built into the asset.
    """
    canvas = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    inner = int(round(n * 824 / 1024))
    art = square.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    radius = int(round(inner * 0.2237))
    mask = Image.new("L", (inner * 4, inner * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, inner * 4 - 1, inner * 4 - 1], radius=radius * 4, fill=255)
    art.putalpha(mask.resize((inner, inner), Image.LANCZOS))
    canvas.alpha_composite(art, ((n - inner) // 2, (n - inner) // 2))
    return canvas


print("Desktop icons")
square = M.plate(1024).convert("RGB").convert("RGBA")
# Linux (.deb) and the window/taskbar icon: the plain plate, the OS draws it as-is.
write(square.resize((512, 512), Image.LANCZOS), f"{DESKTOP}/satride.png")
write(square.resize((256, 256), Image.LANCZOS), f"{DESKTOP_RES}/satride.png")

# Windows: every size the shell asks for, in one .ico.
ico_path = f"{DESKTOP}/satride.ico"
square.save(ico_path, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print(f"  {os.path.relpath(ico_path, ROOT)}  multi-size")

# macOS: an .icns, which only iconutil (part of Xcode's command-line tools) can write.
if shutil.which("iconutil"):
    mac = macos_icon(square)
    with tempfile.TemporaryDirectory() as tmp:
        iconset = os.path.join(tmp, "satride.iconset")
        os.makedirs(iconset)
        for size in (16, 32, 128, 256, 512):
            mac.resize((size, size), Image.LANCZOS).save(f"{iconset}/icon_{size}x{size}.png")
            mac.resize((size * 2, size * 2), Image.LANCZOS).save(f"{iconset}/icon_{size}x{size}@2x.png")
        icns_path = f"{DESKTOP}/satride.icns"
        subprocess.run(["iconutil", "-c", "icns", iconset, "-o", icns_path], check=True)
        print(f"  {os.path.relpath(icns_path, ROOT)}")
else:
    print("  iconutil not found: satride.icns left as it was (macOS only)")

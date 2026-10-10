"""Builds the launcher icon files the HushTelegram icon patch adds, from assets/icon.png.

Run from the repository root with Pillow installed (pip install Pillow):

    py -3.13 scripts/gen-launcher-icon.py

It writes, for each screen density, the badge itself for launchers older than Android 8, the
badge as an adaptive icon's foreground layer, and a one-color H and paper plane for themed
icons on Android 13 and up, under patches/src/main/resources/hushtelegram/icon/. The patch
copies them into the app as mipmaps. The background layer is a color in the patch's adaptive
icon XML (BACKGROUND below), so there's no file for it.
"""

from collections import deque
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "assets" / "icon.png"
OUTPUT = ROOT / "patches" / "src" / "main" / "resources" / "hushtelegram" / "icon"

# The badge in assets/icon.png: its center and the radius of its outer edge, past which there's
# only a soft glow, and the radius inside the ring.
CENTER = (627, 630)
RADIUS = 522
INNER = 452
# Where the H and the plane are; above it, inside the ring, is a glare that isn't part of either.
GLYPH_BOX = (320, 275, 985, 960)

# The ring's outer dark blue, so a launcher shape that shows past the badge's edge blends in.
BACKGROUND = "#FF00218A"

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
# A legacy icon is 48dp, and a round one is 44dp of that. An adaptive layer is 108dp, of which a
# launcher shows the middle 72dp; the badge goes a little past that so the circle mask never
# leaves a sliver of background. A themed glyph stays well inside the 66dp safe zone.
LEGACY_DP, LEGACY_BADGE_DP = 48, 44
LAYER_DP, LAYER_BADGE_DP, GLYPH_DP = 108, 74, 46


def badge(source: Image.Image) -> Image.Image:
    """The badge alone, cut to its circle so the glow around it is gone."""
    cx, cy = CENTER
    box = (cx - RADIUS, cy - RADIUS, cx + RADIUS, cy + RADIUS)
    crop = source.crop(box)
    # Drawn four times larger and scaled down, so the circle's edge is smooth.
    scale = 4
    mask = Image.new("L", (crop.width * scale, crop.height * scale), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, mask.width - 1, mask.height - 1), fill=255)
    mask = mask.resize(crop.size, Image.LANCZOS)
    crop.putalpha(ImageChops.multiply(crop.getchannel("A"), mask))
    return crop


def keep_large(mask: Image.Image, share: float) -> Image.Image:
    """The mask without specks: only parts at least `share` of its largest part's size stay."""
    width, height = mask.size
    pixels = mask.load()
    seen = bytearray(width * height)
    parts = []
    for start in range(width * height):
        x, y = start % width, start // width
        if seen[start] or pixels[x, y] < 128:
            continue
        seen[start] = 1
        part, queue = [], deque([(x, y)])
        while queue:
            px, py = queue.popleft()
            part.append((px, py))
            for nx, ny in ((px + 1, py), (px - 1, py), (px, py + 1), (px, py - 1)):
                index = ny * width + nx
                if 0 <= nx < width and 0 <= ny < height and not seen[index] and pixels[nx, ny] >= 128:
                    seen[index] = 1
                    queue.append((nx, ny))
        parts.append(part)
    largest = max((len(part) for part in parts), default=0)
    kept = Image.new("L", mask.size, 0)
    out = kept.load()
    for part in parts:
        if len(part) >= largest * share:
            for x, y in part:
                out[x, y] = 255
    return kept


def glyph(source: Image.Image) -> Image.Image:
    """The H and the paper plane as one white shape, with a gap where the plane crosses the H."""
    cx, cy = CENTER
    pixels = source.load()
    letter = Image.new("L", source.size, 0)
    plane = Image.new("L", source.size, 0)
    lp, pp = letter.load(), plane.load()
    left, top, right, bottom = GLYPH_BOX
    for y in range(top, bottom):
        for x in range(left, right):
            if (x - cx) ** 2 + (y - cy) ** 2 > INNER * INNER:
                continue
            r, g, b, _ = pixels[x, y]
            if min(r, g, b) > 185:
                lp[x, y] = 255  # the white H
            elif g > 150 and b > 230 and r < 200:
                pp[x, y] = 255  # the cyan plane, and the H's thin cyan outline

    def close(mask, size):
        return mask.filter(ImageFilter.MaxFilter(size)).filter(ImageFilter.MinFilter(size))

    def open_(mask, size):
        return mask.filter(ImageFilter.MinFilter(size)).filter(ImageFilter.MaxFilter(size))

    letter = keep_large(open_(close(letter, 5), 11), 0.05)
    # A wide opening drops the outline and keeps the plane's faces; growing them back inside the
    # first reading restores the plane's edges without reaching along the outline.
    traced = close(plane, 7)
    plane = open_(traced, 15)
    for _ in range(8):
        plane = ImageChops.darker(plane.filter(ImageFilter.MaxFilter(3)), traced)
    plane = keep_large(open_(close(plane, 9), 5), 0.05)
    gap = plane.filter(ImageFilter.MaxFilter(19))
    shape = ImageChops.lighter(ImageChops.subtract(letter, gap), plane)
    shape = shape.filter(ImageFilter.GaussianBlur(5)).point(lambda value: 255 if value >= 128 else 0)
    white = Image.new("RGBA", source.size, (255, 255, 255, 0))
    white.putalpha(shape)
    return white.crop(shape.getbbox())


def placed(picture: Image.Image, canvas: int, size: int, by_height: bool = False) -> Image.Image:
    """The picture scaled to `size` pixels (its width, or its height) and centered on a square canvas."""
    if by_height:
        width, height = round(picture.width * size / picture.height), size
    else:
        width, height = size, round(picture.height * size / picture.width)
    scaled = picture.resize((width, height), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.alpha_composite(scaled, ((canvas - width) // 2, (canvas - height) // 2))
    return out


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")
    circle = badge(source)
    shape = glyph(source)
    for density, factor in DENSITIES.items():
        folder = OUTPUT / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        legacy = placed(circle, round(LEGACY_DP * factor), round(LEGACY_BADGE_DP * factor))
        foreground = placed(circle, round(LAYER_DP * factor), round(LAYER_BADGE_DP * factor))
        monochrome = placed(shape, round(LAYER_DP * factor), round(GLYPH_DP * factor), by_height=True)
        legacy.save(folder / "hush_launcher.png", optimize=True)
        foreground.save(folder / "hush_launcher_foreground.png", optimize=True)
        monochrome.save(folder / "hush_launcher_monochrome.png", optimize=True)
    print(f"wrote {len(DENSITIES) * 3} files under {OUTPUT.relative_to(ROOT)}; background {BACKGROUND}")


if __name__ == "__main__":
    main()

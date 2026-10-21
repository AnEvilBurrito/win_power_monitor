"""Frame the original screenshot without resampling its contents (requires Pillow)."""
from pathlib import Path
import math

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent


def render():
    screenshot = Image.open(ROOT / "docs" / "early-promo.png").convert("RGBA")
    padding = 140
    width, height = screenshot.size
    size = (width + padding * 2, height + padding * 2)
    backdrop = Image.new("RGB", size)
    pixels = []
    for y in range(size[1]):
        for x in range(size[0]):
            blue = math.exp(-(((x - size[0] * 0.17) / 540) ** 2 + ((y - size[1] * 0.17) / 390) ** 2))
            purple = math.exp(-(((x - size[0] * 0.85) / 540) ** 2 + ((y - size[1] * 0.88) / 390) ** 2))
            pixels.append((int(9 + 10 * blue + 27 * purple),
                           int(14 + 29 * blue + 9 * purple),
                           int(28 + 53 * blue + 46 * purple)))
    backdrop.putdata(pixels)
    backdrop = backdrop.convert("RGBA")
    bounds = (padding, padding, padding + width - 1, padding + height - 1)
    shadow = Image.new("RGBA", size)
    ImageDraw.Draw(shadow).rounded_rectangle(
        (bounds[0] - 5, bounds[1] + 18, bounds[2] + 5, bounds[3] + 28),
        radius=24, fill=(0, 0, 0, 185))
    backdrop = Image.alpha_composite(backdrop, shadow.filter(ImageFilter.GaussianBlur(30)))
    mask = Image.new("L", screenshot.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), radius=16, fill=255)
    backdrop.paste(screenshot, (padding, padding), mask)
    ImageDraw.Draw(backdrop).rounded_rectangle(bounds, radius=16, outline=(115, 135, 181, 110), width=1)
    backdrop.convert("RGB").save(ROOT / "docs" / "promo.png", optimize=True)
    print(f"docs\\promo.png: {size[0]} x {size[1]} (screenshot at native resolution)")


if __name__ == "__main__":
    render()

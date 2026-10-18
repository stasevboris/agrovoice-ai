"""Build Android icons from Boris's supplied artwork (requires Pillow)."""

from pathlib import Path

from PIL import Image, ImageChops


ROOT = Path(__file__).resolve().parent
RES = ROOT.parent / "app/src/main/res"
BACKGROUND = (243, 243, 235, 255)


def centered(source: Image.Image, side: int, artwork_side: int) -> Image.Image:
    scale = artwork_side / max(source.size)
    artwork = source.resize(
        (round(source.width * scale), round(source.height * scale)),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGBA", (side, side), BACKGROUND)
    position = ((side - artwork.width) // 2, (side - artwork.height) // 2)
    canvas.alpha_composite(artwork, position)
    return canvas


def main() -> None:
    source = Image.open(ROOT / "logo-source.png").convert("RGBA")
    drawable = RES / "drawable-nodpi"
    drawable.mkdir(exist_ok=True)
    source.save(drawable / "agrovoice_logo.png")
    # 72 dp of source artwork in the 108 dp adaptive layer: the painted mark
    # itself fits within the central 66 dp safe zone, including circular masks.
    adaptive = centered(source, 432, 288)
    adaptive.save(drawable / "agrovoice_launcher.png")
    background = Image.new("RGB", adaptive.size, BACKGROUND[:3])
    difference = ImageChops.difference(adaptive.convert("RGB"), background)
    red, green, blue = difference.split()
    alpha = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    alpha = alpha.point(lambda value: max(0, min(255, (value - 18) * 4)))
    monochrome = Image.new("RGBA", adaptive.size, (255, 255, 255, 0))
    monochrome.putalpha(alpha)
    monochrome.save(drawable / "agrovoice_monochrome.png")
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, side in densities.items():
        icon = centered(source, side, round(side * 0.9))
        folder = RES / f"mipmap-{density}"
        for name in ("ic_launcher", "ic_launcher_round"):
            icon.save(folder / f"{name}.webp", lossless=True)


if __name__ == "__main__":
    main()

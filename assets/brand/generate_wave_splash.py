"""Reveal the original AO letterforms with layered moving liquid waves."""
from pathlib import Path
import math

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent
SIZE = 640
TERRA = (184, 68, 50)
CREAM = (255, 247, 238)


def build():
    original = Image.open(ROOT / "arenaone-ao-app-icon-v4.png").convert("RGBA")
    width, height = original.size
    original = original.crop((round(width * 0.14), round(height * 0.28), round(width * 0.88), round(height * 0.74)))
    # Isolate the existing cream letters; preserve their original contours.
    alpha = Image.new("L", original.size)
    alpha.putdata([
        round(max(0, min(1, (g - 160) / 65)) * a)
        if r > 200 and b > 145 else 0
        for r, g, b, a in original.getdata()
    ])
    alpha = alpha.crop(alpha.getbbox())
    alpha.thumbnail((440, 300), Image.Resampling.LANCZOS)
    letters = Image.new("L", (SIZE, SIZE))
    position = ((SIZE - alpha.width) // 2, (SIZE - alpha.height) // 2)
    letters.paste(alpha, position)
    left, top, right, bottom = letters.getbbox()

    def wave_mask(level, phase, amplitude):
        points = [(0, SIZE), (0, level)]
        for x in range(0, SIZE + 2, 2):
            y = level + amplitude * math.sin(x / 66 + phase)
            y += amplitude * 0.32 * math.sin(x / 33 - phase * 0.7)
            points.append((x, y))
        points.append((SIZE, SIZE))
        wave = Image.new("L", (SIZE, SIZE))
        ImageDraw.Draw(wave).polygon(points, fill=255)
        return ImageChops.multiply(letters, wave.filter(ImageFilter.GaussianBlur(0.45)))

    def frame(progress, index):
        canvas = Image.new("RGB", (SIZE, SIZE), TERRA)
        if progress <= 0:
            return canvas
        if progress >= 1:
            canvas.paste(CREAM, (0, 0, SIZE, SIZE), letters)
            return canvas
        # A translucent leading swell precedes the off-white fill.
        level = bottom + 30 - progress * (bottom - top + 70)
        phase = index * 0.20
        amplitude = 12 * math.sin(math.pi * progress) + 3
        for offset, speed, color in [
            (-15, 0.8, (209, 128, 110)),
            (-6, 1.15, (233, 188, 172)),
            (5, 1.0, CREAM),
        ]:
            mask = wave_mask(level + offset, phase * speed, amplitude)
            canvas.paste(color, (0, 0, SIZE, SIZE), mask)
        return canvas

    images = [frame(0, 0)]
    durations = [280]
    for index in range(1, 55):
        images.append(frame(index / 54, index))
        durations.append(30)
    durations[-1] = 1300

    # Quantize every frame against one palette for consistent animation colors.
    swatches = Image.new("RGB", (SIZE * 4, SIZE))
    for i, progress in enumerate((0.25, 0.5, 0.75, 1)):
        swatches.paste(frame(progress, 25), (SIZE * i, 0))
    palette = swatches.quantize(colors=128, method=Image.Quantize.MEDIANCUT)
    indexed = [im.quantize(palette=palette, dither=Image.Dither.NONE) for im in images]
    for name, loop in [("arenaone-wave-splash.gif", False), ("arenaone-wave-splash-loop.gif", True)]:
        options = {"loop": 0} if loop else {}
        indexed[0].save(
            ROOT / name, save_all=True, append_images=indexed[1:],
            duration=durations, disposal=2, optimize=False, **options,
        )
        with Image.open(ROOT / name) as result:
            assert result.n_frames > 40
            result.seek(0)
            assert ImageChops.difference(result.convert("RGB"), Image.new("RGB", (SIZE, SIZE), TERRA)).getbbox() is None
            result.seek(result.n_frames - 1)
            assert ImageChops.difference(result.convert("RGB"), Image.new("RGB", (SIZE, SIZE), TERRA)).getbbox()
            print(name, result.n_frames, "frames", (ROOT / name).stat().st_size, "bytes")

    # A contact sheet makes the reveal progression easy to review.
    contact = Image.new("RGB", (320 * 4, 320), TERRA)
    for i, progress in enumerate((0, 0.35, 0.65, 1)):
        contact.paste(frame(progress, round(progress * 54)).resize((320, 320), Image.Resampling.LANCZOS), (320 * i, 0))
    contact.save(ROOT / "arenaone-wave-progression.png")


if __name__ == "__main__":
    build()

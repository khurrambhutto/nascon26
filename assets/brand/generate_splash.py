"""Encode splash GIFs from the original ArenaOne logo without redrawing it."""
from pathlib import Path

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parent
SIZE = (640, 640)
PAPER = (255, 247, 238)


def build():
    original = Image.open(ROOT / "arenaone-ao-app-icon-v4.png").convert("RGBA")
    logo = original.crop(original.getbbox())
    logo.thumbnail((448, 448), Image.Resampling.LANCZOS)
    position = ((SIZE[0] - logo.width) // 2, (SIZE[1] - logo.height) // 2)

    def frame(opacity):
        canvas = Image.new("RGB", SIZE, PAPER)
        alpha = logo.getchannel("A").point(lambda value: round(value * opacity))
        canvas.paste(logo, position, alpha)
        return canvas

    # One shared palette avoids color flicker between frames.
    samples = Image.new("RGB", (SIZE[0] * 3, SIZE[1]), PAPER)
    for index, opacity in enumerate((0.25, 0.6, 1.0)):
        samples.paste(frame(opacity), (SIZE[0] * index, 0))
    palette = samples.quantize(colors=256, method=Image.Quantize.MEDIANCUT)

    def indexed(opacity):
        return frame(opacity).quantize(palette=palette, dither=Image.Dither.NONE)

    def ease(t):
        return t * t * (3 - 2 * t)

    reveal = [indexed(ease(index / 19)) for index in range(20)]
    durations = [40] * 19 + [1240]
    reveal[0].save(
        ROOT / "arenaone-splash.gif",
        save_all=True,
        append_images=reveal[1:],
        duration=durations,
        disposal=2,
        optimize=False,
    )
    # The main GIF plays once and keeps its final logo frame visible.
    # The alternate loop is useful for previews and loading screens.
    fade = [indexed(1 - ease(index / 12)) for index in range(1, 13)]
    looping = reveal + fade
    loop_durations = durations + [40] * 11 + [400]
    looping[0].save(
        ROOT / "arenaone-splash-loop.gif",
        save_all=True,
        append_images=looping[1:],
        duration=loop_durations,
        loop=0,
        disposal=2,
        optimize=False,
    )

    for name in ("arenaone-splash.gif", "arenaone-splash-loop.gif"):
        with Image.open(ROOT / name) as animation:
            duration = 0
            for index in range(animation.n_frames):
                animation.seek(index)
                assert animation.size == SIZE
                duration += animation.info.get("duration", 0)
            animation.seek(animation.n_frames - 1)
            if name == "arenaone-splash.gif":
                blank = Image.new("RGB", SIZE, PAPER)
                assert ImageChops.difference(animation.convert("RGB"), blank).getbbox()
                assert "loop" not in animation.info
            print(f"{name}: {animation.n_frames} frames, {duration} ms, {SIZE[0]}x{SIZE[1]}")


if __name__ == "__main__":
    build()

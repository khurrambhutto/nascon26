# ArenaOne wave splash

- `arenaone-wave-splash.gif`: plays once, revealing the A and O through layered moving waves, then holds the completed logo.
- `arenaone-wave-splash-loop.gif`: repeats the same reveal for previews.
- `arenaone-wave-progression.png`: static progression preview.

Both GIFs are 640 × 640 pixels. Background: terracotta `#B84432`; final letterforms: off-white `#FFF7EE`. Letter shapes come from `arenaone-ao-app-icon-v4.png`.

The reproducible encoder is `generate_wave_splash.py` and requires Pillow. Android needs an animated-image decoder to play the GIF; a static image painter will show only one frame. The app now plays the non-looping GIF at startup using native ImageDecoder on Android 28+, with a Movie fallback for Android 26/27. It opens sign-in or the saved session after the animation finishes.

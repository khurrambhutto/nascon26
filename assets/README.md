# ArenaOne demo assets

Original demo artwork generated with the built-in GPT Image tool for ArenaOne's coral, mint, and cream palette. Dimensions and checksums for each transparent RGBA PNG are recorded in `manifest.json`.

| File | Intended use |
|---|---|
| `brand/arenaone-app-icon.png` | ArenaOne app icon concept; generation prompt in `brand/prompt.txt` |
| `clubs/lions-fc.png` | Lions FC crest |
| `clubs/eagles-fc.png` | Eagles FC crest |
| `clubs/wolves-fc.png` | Wolves FC crest |
| `clubs/bears-fc.png` | Bears FC crest |
| `illustrations/lions-victory.png` | Celebration or success illustration |
| `products/lions-home-jersey.png` | Fan-shop product image |

Preserve transparency and scale proportionally. Use club names as accessible labels when the image conveys team identity; decorative images can use empty alternative text. For Android, these PNGs can be copied into `app/src/main/res/drawable-nodpi/` using underscore filenames, for example `lions_fc.png`.

`manifest.json` records dimensions and SHA-256 checksums. `prompts.json` contains the generation prompts.

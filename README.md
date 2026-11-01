# New Oasis - themes and games

The theme and mini-game store of New Oasis (a fork of InniClassic) for the Innioasis Y1, published with GitHub Pages
from this `gh-pages` branch: https://milescarter93.github.io/inniclassic/

- `index.html` – the store page (Spanish / English).
- `catalog.json` – what the Y1 reads (Settings → Display → Theme → Get More Themes).
- `themes/` – theme zips, the same format as the `Y1_Themes` folder (a `config.json` at the root; its
  `"name"` must equal the catalog entry's `"name"`).
- `games/` – the mini-games playable in the browser.

## Adding or updating a theme

1. Put the zip in `themes/` and add (or edit) its entry in `catalog.json`. Raise `"version"` when you
   replace a theme, so Y1s that installed it from the store offer the update. `"minApp"` is the lowest
   app versionCode that can show it properly.
2. Run `python tools/update_catalog.py` to fill in each theme's `size` and `sha256` (the Y1 checks both).
3. Commit and push this branch.

Free, non-commercial fan project (same license as the original project). Not affiliated with Innioasis, Apple or Anthropic. "Claudoasis" is an unofficial theme inspired by Claude, not affiliated with Anthropic.

## Publishing a New Oasis update

`output-metadata.json` is what the Y1 checks in Settings → System → Software Update. To offer a new version:
set `versionCode` / `versionName` to the new build's, `outputFile` to the APK's full HTTPS address (for
example the asset of a GitHub release) and `sha256` to the APK's SHA-256. The Y1 only installs an APK whose
SHA-256 matches; with `outputFile` empty nothing is offered.

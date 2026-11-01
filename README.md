# InniClassic Store

The theme and mini-game store of the InniClassic fork for the Innioasis Y1, published with GitHub Pages
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

Unofficial fan project. "Claudoasis" is an unofficial theme inspired by Claude, not affiliated with Anthropic.

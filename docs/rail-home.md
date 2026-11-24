# The rail home

A second home screen for the launcher, chosen per TV with one theme key. The classic dock and grid stay the
default and are untouched.

- **Left:** a frosted rail with the greeting, a thin clock, the date, the weather from the theme's
  `status_provider` (now, the alert or rain badge, five days with range bars and rain chances) and three rows:
  Inputs (the first connected HDMI input by its name), Wi-Fi (network and signal) and Settings.
- **Top right:** the favourites (`dock_apps`) in a carousel. The focused app sits large in the centre; the row
  slides under it. Its name, a line under it and position dots below. Behind it, a picture for that app, faded
  toward the rail and the grid.
- **Below:** every app, 6 across, alphabetical, favourites included and marked with a dot.
- **Adaptive accent:** AM/PM, the counter, the dots, the focus rings and the glow take the focused app's colour.

## Turning it on

```json
{ "home_layout": "rail" }
```

in the theme's `config.json`, pushed with `tools/theme.sh` / `theme.ps1`. `"classic"` (or no key) goes back.

`MainActivity` stays the package's only HOME activity and hands every Home press to `RailActivity` without an
animation. `RailActivity` is deliberately not a HOME activity: with two in one package, Android 14 drops the
preferred Home on every update and shows the "Select a Home app" chooser, and `cmd package set-home-activity`
always picks the first one.

## Carousel pictures

For each favourite the first source that has a picture wins:

1. **The theme:** `heroes/<package>.jpg` (or `.png`, `.webp`) in the theme folder, pushed with the theme.
2. **The art helper** (`launcher/weather`, [`docs/specs/art.md`](specs/art.md)): the app's newest Continue
   Watching picture, else its first preview-channel picture. The line under the name reads
   "Continue watching · <title>" or "Featured · <title>".
3. **The app's banner**, enlarged into a soft field of its colours.

Only the focused favourite's picture and its two neighbours are decoded; up to four stay cached.

## Keys

| Where | Key |
|---|---|
| Carousel | Left / Right slide; Left on the first favourite goes to the rail; Down goes to the grid; OK opens |
| Grid | Up from the top row returns to the carousel; Left from the first column goes to the rail |
| Rail | Up / Down between rows; Right returns to the carousel |
| Home | Back to the first favourite, grid scrolled to the top |

## Not in the rail home (yet)

The classic home's dock reordering, app library, module cards, wallpapers, festival skins, race panel, cinematic
shelf and idle clock. The remote-key services (`InputKeyService`, `HomeKeyService`) work the same with either.

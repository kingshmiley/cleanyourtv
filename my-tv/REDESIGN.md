# Home screen redesign: plan

Agreed and built 24 November 2026 (CHANGELOG section 9, docs/rail-home.md). Mockups are in `shots/mockups/redesign/` (git-ignored, local only):
`c-hulu.png`, `c-youtube.png` (layout), `h-snp-photo.png`, `h-hulu-auto.png` (hero art).

## Target

- **Layout:** a floating frosted rail on the left (greeting, thin clock, date, current weather, 5-day list with
  range bars and rain chances, Inputs / Wi-Fi / Settings rows with status), apps on the right.
- **Favorites carousel** at the top right: the focused favorite large in the centre, neighbours shrinking and
  dimming to each side; stops at the ends (built: Left on the first goes to the rail). Name, a line and dots under it. Left/Right move,
  Down goes to All apps.
- **All apps** below: 6 across, alphabetical, favorites included and marked with a small accent dot (make it
  bigger or a star; the mockup dot is too small at TV distance).
- **Tiles:** concept B's shape, refined: barely rounded corners, a ring a few px off the tile, a short pill
  under the focused one.
- **Accent: adaptive.** Ring, glow, PM, counter and dots take the focused app's colour (the launcher already
  samples it for the ambient glow).
- **Hero art** behind the carousel, fading toward the rail and the grid. Source per favorite, first that exists:
  1. a custom image from the theme (SNP 360: the PNC Park photo; others can be added later),
  2. the app's Continue Watching / preview artwork (`docs/watch-next.md`: `READ_TV_LISTINGS`, granted once with
     `pm grant`; only rows the app marks searchable),
  3. an automatic colour field made from the app's banner.
  Decode only the focused image and its neighbours (a hero is ~30x a banner's pixels).

## What the TV provides (checked 24 November 2026)

A throwaway probe app holding `READ_TV_LISTINGS` (installed, run, uninstalled) found 20 Watch Next rows and
310 preview rows. For the favorites:

| App | Continue Watching | Preview rows | Hero source |
|---|---|---|---|
| Hulu | 3 | 17 | Continue Watching art |
| Peacock | 2 | 10 | Continue Watching art |
| Disney+ | 10 | 111 | Continue Watching art |
| YouTube | 0 | 28 | preview (recommendation) art |
| Apple TV | 0 | 10 | preview art |
| SNP 360 | 0 | 0 | PNC Park photo (theme) |

Netflix, Paramount+ and Max also publish Continue Watching. **Every image is an `https://` URL**, so the
launcher (no network permission, on purpose) cannot load them itself: an art helper with `INTERNET` must
download, cache and serve them, the same split as the weather module. The shell cannot read these rows
(`content query` returns nothing without `READ_TV_LISTINGS`).

## Before building
- Find a higher-resolution copy of the PNC Park photo if possible (the current one is 929x605).

## Build order (each step leaves a working Home)

1. Rail: move clock, weather and the buttons into it; grid to the right at 4-6 columns.
2. Tile style and adaptive accent.
3. Favorites carousel (fixed-centre focus, wrap, dots) above All apps.
4. Hero art: theme images, then Continue Watching, then the automatic fallback.

# Status provider, spec 1

A status provider is another app on the TV that hands Home a short live status: one line under the clock,
an optional badge beside it, an optional alert below, and up to five small day cards under the top buttons.
The launcher reads it and draws it; it never goes online itself and holds no network permission. The first
provider is the weather module in [`launcher/weather`](../../launcher/weather/); the same slot can carry
other live information later.

## Turning it on

Two keys, in `config.xml` or a theme's `config.json`:

| Key | Meaning |
|---|---|
| `status_provider` | The provider's authority, e.g. `io.github.kingshmiley.tvweather.status`. Empty: off, nothing is read. Must be a plain dotted name. |
| `status_query` | Passed to the provider as the URI's query string, as is, e.g. `lat=40.4406&lon=-79.9959`. |

## The query

```
content://<status_provider>/status?<status_query>
```

Projection `kind, icon, title, value, detail, chance`, all strings; `chance` is optional (a provider without it still works). Home asks when it resumes, every five minutes while it
stays on screen, and whenever the provider notifies `content://<status_provider>/status` (the launcher registers an
observer on it while resumed). Home never asks while an app is in front.

The provider should answer from a cache at once and refresh in the background when its data is stale, then call
`notifyChange` on that URI. It should return no rows rather than old data: Home hides whatever is missing.

## Rows

| `kind` | Shown as | Columns used | Limits |
|---|---|---|---|
| `now` | Under the clock: icon, `value` large, `title` with `detail` below it | icon, title, value, detail | first one only |
| `badge` | An amber pill under `title` and `detail` | title | first only; dropped without a `now` row |
| `alert` | An amber bar under the line | title | first only |
| `day` | A card under the top buttons, stacked: `title`, icon, `value`, `detail` dimmed, and `chance` with a raindrop | icon, title, value, detail, chance | five at most; `chance` must be `0%` to `100%`, brighter blue from 30% |

`icon` is one of `sun`, `moon`, `partly`, `partly_night`, `cloud`, `rain`, `storm`, `snow`, `fog`, drawn by the
launcher (WeatherIcon). Anything else draws no icon. Unknown kinds are ignored.

Every value is another app's text, so the launcher cleans it before it reaches a view (StatusRows): control and
formatting characters are dropped, whitespace collapsed, and text cut to 40 characters (72 for an alert, 8 for a
value, 4 for a day name) with an ellipsis.

## Visibility

On Android 11 and newer the launcher can only read a provider in a package it can see. It sees apps with a
`LEANBACK_LAUNCHER` or `LAUNCHER` activity, so a provider app needs one (the weather module's is its full forecast
screen). Hide its tile with `hidden_apps` if it is not wanted in the grid.

## Layout

Both parts are overlays like the race-weekend panel: they never move the dock or change tile sizes. The line sits
113dp from the top (temperature at 48sp, conditions and detail stacked beside it), the cards 118dp (75dp wide), sized for a 960x540dp screen (1080p at 2x). Not combined with the race panel,
`show_stats` or the Shamsi date, which use the same space.

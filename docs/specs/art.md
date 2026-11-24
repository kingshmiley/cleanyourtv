# Art provider, spec 1

Pictures for the rail home's carousel ([`docs/rail-home.md`](../rail-home.md)), served by the art helper in
[`launcher/weather`](../../launcher/weather/) (`io.github.kingshmiley.tvweather`). The launcher holds no network
permission; the TV provider stores only `https://` addresses for these pictures, so the helper downloads them.

## Permissions

- The helper holds `android.permission.READ_TV_LISTINGS` (runtime; grant once with
  `pm grant io.github.kingshmiley.tvweather android.permission.READ_TV_LISTINGS`). With it, a query returns every
  Watch Next and preview row an app marked searchable. Selections are refused without `ACCESS_ALL_EPG_DATA`, so
  the whole table is read and filtered in code (see `docs/watch-next.md`).
- The rows are what the owner has been watching, so the provider is guarded by the signature permission
  `io.github.kingshmiley.tvweather.permission.READ_ART`: only an app signed with the same key reads it. Install
  the helper **before** the launcher, or the launcher's request for that permission is not granted.

## Queries

```
content://io.github.kingshmiley.tvweather.art/app/<package>
```

One row or none: `kind` (`continue` for Watch Next, `preview` for a preview channel), `title` (programme title,
with the episode title when it adds something), `image` (`content://io.github.kingshmiley.tvweather.art/img/<sha1>`,
or empty while the picture downloads). Watch Next wins, newest `last_engagement_time_utc_millis` first; else the
first preview programme with a picture. The cursor's notification URI is `…/app/<package>`; it is notified when
the download lands.

```
content://io.github.kingshmiley.tvweather.art/img/<sha1>
```

opens the cached picture read-only. Pictures are capped at 6 MB; the cache keeps the 40 most recently used.

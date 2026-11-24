# Change log: TCL Smart TV Pro (G08_4K_US), Google TV 14

Every change made to this TV, in order. The debloat list is [`debloat.txt`](debloat.txt), the launcher
config is [`theme/config.json`](theme/config.json). All commands below were run from the repo root on
Windows, with the TV at `10.0.0.124`.

## TV

| | |
|---|---|
| Model | TCL "Smart TV Pro", device `G08`, product `G08_4K_US` |
| SoC | MediaTek `tcl9618` |
| Android | 14 (SDK 34), Google TV |
| Firmware | `tcl9618-user 14 UTT2.250416.001 AU14 release-keys` |
| Stock home | `com.google.android.apps.tv.launcherx/.home.HomeActivity` |
| Firmware updater | Google Play Services (`com.google.android.gms/.update.SystemUpdatePanoActivity`); left alone |
| Display | Physical 3840x2160 with a factory 1920x1080 override, density 320; not touched |

Starting point: [`examples/tcl-g08-us-google-tv`](../examples/tcl-g08-us-google-tv/) (same product and
board, firmware AU16), checked package by package against this set.

## 21 October 2026

### 1. Backup (before any change)

`backup/20261021-155859/` (git-ignored, stays on this computer): full, system, third-party and disabled
package lists, `getprop`, all `settings` (global, secure, system), the Home component, display size and
density, and the owner of the saved Wi-Fi network (`com.google.android.tungsten.setupwraith` at that point).

`tools/disable.ps1` wrote the restore record on its first run, before it disabled anything:
`%USERPROFILE%\.cleanyourtv\recovery-10.0.0.124-1fcc7af0.json`. Keep it; `restore.ps1` replays it.

### 2. Debloat, in batches, checked after each

| Batch | Packages | Check afterwards |
|---|---|---|
| 1 | TCL TV+, message box, e-sticker, hotel menu, store demo, `tclhome_passive`, dashboard (7) | Home opens (screenshot) |
| 2 | TCL log kit, user center, Google feedback, feedback consent, AdServices, on-device personalisation, federated compute (7) | Home opens |
| 3 | On-TV manual, hear-aid, T-Solo, print spooler, calendar sync, one-time initialiser, TCL browser, Channel+, Tubi, Play Games, Instagram, Google Assistant voice (`katniss`) (12) | Home, YouTube, Play Store and the input menu (PS5 on HDMI 1 listed) open |
| 4 | `launcherx` (Google TV home), `setupwraith` (setup wizard) (2) | Only after the new launcher was installed and seen working, and after Wi-Fi was forgotten and re-joined so the saved network belongs to `com.android.tv.settings` |

All with `pm disable-user --user 0` through `tools\disable.ps1 -TvHost 10.0.0.124 my-tv\debloat.txt`
(the list grew one batch at a time and the script was re-run; already-disabled entries are no-ops).

**Reverted:** `com.tcl.ttvs` was disabled in batch 2 and re-enabled minutes later with
`pm enable --user 0 com.tcl.ttvs`. It is Samba TV viewing tracking and TCL analytics, but it also hosts the
Alexa core service that `com.tcl.smartalexa` binds to (`com.tcl.ttvs.alexa.*` actions), and the owner keeps
Alexa. It is commented out in the list.

**Kept on purpose:** Chromecast (`mediashell`), AirPlay (`com.tcl.airplay2`, `com.mediatek.Airplay*`),
Alexa (`com.tcl.smartalexa`, `com.tcl.ttvs`), every streaming app except Tubi and Instagram, the TV/input
stack (`com.tcl.tv`, `tvinput`, `suspension`), `com.google.android.tv.remote.service`, the keyboard, Play
Store, Play Services, and `providers.config` / `initsetup` / `factory.view` (dropped Wi-Fi on the QM6K).

### 3. Launcher

- Built with `launcher\build-and-install.ps1 -TvHost 10.0.0.124 -StockLauncher none -InputKeyService -HomeKeyService`
  (JDK 17 Temurin, SDK platform 34, build-tools 34.0.0). The stock launcher was left on at this point.
- The script's last steps were repeated by hand after the TV went to sleep mid-run: `set-home-activity`,
  `am start`, location grant (for the Wi-Fi name), `compile -m speed`. `InputKeyService` and
  `HomeKeyService` were already enabled in `enabled_accessibility_services`.
- Config pushed with `tools\theme.ps1 -TvHost 10.0.0.124 my-tv\theme`: dock Hulu, Peacock, YouTube,
  SportsNet Pittsburgh; app library on; Live TV `com.tcl.tv`; TCL gear button (keycode 83) opens the quick
  settings panel; Netflix / Prime Video / YouTube remote buttons mapped (keycodes from the G08 C89B list).
- After batch 4: `cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity`. Home resolves to
  `com.gathin.tvhome/.MainActivity`, and `mFocusedApp` is the new launcher after pressing Home from Settings.

### 4. Reboot test

Rebooted with `reboot`. Afterwards all 28 listed packages were still disabled (`pm list packages -d`
compared with the list), the TV came up on the new launcher (`mFocusedApp` = `com.gathin.tvhome`), and
network ADB on 5555 came back by itself.

### 5. Alexa and Samba TV off, ad-blocking DNS on (owner's request)

- `com.tcl.smartalexa` (Alexa) and `com.tcl.ttvs` (Samba TV tracking, TCL analytics, Alexa core) disabled
  with `tools\disable.ps1` (batch 5). Both were enabled before; the restore record re-enables them.
- Private DNS: `settings put global private_dns_specifier dns.adguard-dns.com` and
  `settings put global private_dns_mode hostname`. Both settings were `null` before (Android's default,
  automatic). Checked: `www.google.com` resolves, `doubleclick.net` and `ads.samba.tv` resolve to
  `127.0.0.1`, YouTube loads. It does not remove YouTube, Hulu or Peacock ads (served with the video).
  **Undo:** `tools\venv\Scripts\python.exe -I tools\tv.py "settings put global private_dns_mode opportunistic"`
  (`tools\restore.ps1` does not touch DNS).

## 22 October 2026

### 6. TCL TV button opens Hulu

The remote's TCL TV button reads as keycode 4184 (scancode 240 / usage `0x000c0150` on the Bluetooth
remote, scancode 345 over IR), found with `logcat` (`TclKeyLayoutMap`) while pressing it. Added
`"4184:com.hulu.livingroomplus"` to `app_keys` in `theme/config.json` and pushed it with
`tools\theme.ps1 -TvHost 10.0.0.124 my-tv\theme`. Tested with the real remote: TCL TV opens Hulu; Netflix,
YouTube and Disney+ buttons still work; Hulu and Peacock play with the AdGuard DNS on. The mic button
(`KEY_SEARCH`) is left unmapped for now.

### 7. Weather on Home (status provider)

New in this fork: a launcher "status line" that reads another app's content provider
([docs/specs/status.md](../docs/specs/status.md)), and a weather module that provides it
([launcher/weather](../launcher/weather/), `io.github.kingshmiley.tvweather`, internet permission only, data from
api.weather.gov for the ZIP code of the house; the coordinates stay out of the repo: `theme/config.json` is git-ignored and `theme/config.example.json` shows the same keys with Pittsburgh). The launcher itself stays without network
permission.

- Built with `gradlew :app:assembleRelease :weather:assembleRelease`; unit tests pass (`StatusRowsTest` 7,
  `NwsTest` 8, all 141 launcher tests).
- Installed `weather-release.apk` and the updated launcher with `tools\b64push.py` + `pm install -r`.
  The launcher reinstall drops its accessibility services, so they were set again:
  `settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService:com.gathin.tvhome/.HomeKeyService`,
  then `compile -m speed` and `set-home-activity` as before.
- `theme/config.json` gained `status_provider` and `status_query`; pushed with `tools\theme.ps1`.
- Checked: the provider returns the now row and five days (`content query`), Home shows them
  (shots/10, 11; day-card text centred in a second build).

**Undo:** remove the two `status_*` keys from `theme/config.json` and push it again, then
`tools\venv\Scripts\python.exe -I tools\tv.py "pm uninstall io.github.kingshmiley.tvweather"`.
- Fix after the owner's report that the weather stayed on screen behind the tiles when scrolling into the
  grid: the status line and day cards now fade out over the first 40dp of grid scroll and back in at the top
  (`fadeStatusWithScroll`). Third launcher install, accessibility services set again; checked with screenshots
  at the dock, one and two rows down, and back.

### 8. Bigger weather, rain chances

- Owner asked for more of the free space to go to the weather, same content first (mockups 9 and 10 in
  `shots/mockups`, not committed). Launcher: temperature at 48sp with conditions and high/low stacked beside it,
  the badge under them, the alert below; day cards 75dp wide with the high, low and chance stacked.
- Day cards show the chance of precipitation: a new optional `chance` column in the status provider
  (docs/specs/status.md), the higher of the day and night periods, brighter blue from 30%.
- Weather icons: a phrase like "Patchy Fog then Mostly Sunny" now draws the part after "then" unless rain,
  snow or storms appear anywhere in it (it drew fog for three sunny days).
- Tests: `NwsTest` 9, `StatusRowsTest` 8, all passing. Both apps reinstalled; the weather app's cache was cleared
  with `pm clear io.github.kingshmiley.tvweather` so the new column showed at once; launcher accessibility
  services set again. Checked with screenshots (shots/16, 17).

## 24 November 2026

### 9. Rail home redesign (agreed design: `my-tv/REDESIGN.md`)

- Checked the TV first with a throwaway probe app holding `READ_TV_LISTINGS` (installed, run, uninstalled):
  20 Continue Watching rows (Hulu, Peacock, Disney+, Netflix, Paramount+, Max) and 310 preview rows, every
  picture an `https://` address.
- New in the fork: `RailActivity` with `RailCarousel`, `HeroView` and `RangeBar` ([docs/rail-home.md](../docs/rail-home.md));
  an art provider in the weather helper ([docs/specs/art.md](../docs/specs/art.md)) behind a signature
  permission; a `heroes/` theme folder; theme scripts restart Home through the HOME intent instead of
  `.MainActivity`. 151 unit tests pass.
- Installed the helper first (`pm grant io.github.kingshmiley.tvweather android.permission.READ_TV_LISTINGS`), then
  the launcher, so the launcher's `READ_ART` request is granted.
- Theme: `"home_layout": "rail"`, six favourites (Apple TV and Disney+ added to the four dock apps),
  `"app_labels": ["com.sportsnetpittsburgh.app=SNP 360"]`, and `heroes/com.sportsnetpittsburgh.app.jpg`
  (the PNC Park photo; git-ignored, not ours to share).
- Lessons from the TV: the shell may not enable or disable a component here (`Shell cannot change component
  state`), and two HOME activities in one package make Android 14 show the "Select a Home app" chooser after an
  update while `set-home-activity` always picks the first. So `RailActivity` is not a HOME activity:
  `MainActivity` stays the only one and hands each Home press over without an animation. After a reinstall
  the key services need switching off and on to bind again (`enabled_accessibility_services` emptied,
  `accessibility_enabled 0`, then set back).
- Checked with screenshots (shots/38–44): Hulu shows its Continue Watching picture, YouTube a
  featured video, SNP 360 the PNC Park photo; cards clip at the column edge; focus moves carousel → grid →
  rail; Home returns to the rail.

**Undo:** set `"home_layout": "classic"` in `theme/config.json` and push the theme again.
- Owner feedback: cards cut by a hard line beside the rail looked like they slid under it. The carousel now fades
  its cards out over the outer 90dp on both sides (a DST_OUT gradient over the drawn cards); the focus ring is
  drawn after the fade. Checked with shots/45.

### 10. Continue Watching, input cards, recent-apps carousel

- Carousel cards about 25% larger. A Continue Watching row between the carousel and All apps (the art helper's
  `/continue` query: newest first, one entry per show, OK resumes through the row's own intent, only if it opens
  the app that published it). While the row has focus, the carousel fades out and the top shows the show's art,
  title and episode.
- Connected HDMI inputs appear as carousel cards named by HDMI-CEC ("PlayStation 5"), with logo cards and
  backgrounds from the theme's `heroes/logo-<slug>.png` and `heroes/input-<slug>.jpg` (git-ignored).
- The carousel is now recently used apps (Android usage history, granted with
  `appops set com.gathin.tvhome GET_USAGE_STATS allow`) plus connected inputs, newest first; Home focuses the
  first card. Both rows fade at their edges instead of sliding under the rail.

### 11. Favorites / All apps, long-press menu, input card looks

- The grid is split into Favorites (connected inputs included, each keeping its place) and All apps (no
  favourites; new apps at the end), each reorderable. Hold OK on a tile for Add to / Remove from favorites,
  Move, Hide and App info; hidden apps gather in a "Hidden apps" entry. Saved on the TV (`rail_order` prefs);
  the theme's `dock_apps` only seeds Favorites once.
- Carousel: label and counter removed; side cards partly see-through (0.67 / 0.47 / 0.31).
- PlayStation 5 card: radial grey behind the retro logo; background `heroes/input-playstation-5.jpg` is the
  owner's blue PS image, moved up so its logo hides behind the card. Steam Deck: monochrome Steam logo.
  Theme images stay git-ignored.

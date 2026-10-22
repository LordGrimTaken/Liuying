# Android audit

Scope: all Android code under `app/` checked against `/home/claude/liuying-spec`. The app has never run on a device (no adb/emulator in the build environment), so every finding comes from reading the code and from JVM unit tests. Anything marked "needs device" has not been exercised.

Severity: **H** wrong behaviour or data loss in a normal flow, **M** wrong in an edge case or spec gap users will notice, **L** polish.

## Fixed

| Sev | Area | Finding | Fix |
|---|---|---|---|
| H | Sync | `syncSettings` overwrote and un-dirtied a key the user edited while the round was in flight; the edit was lost or never pushed. | `SettingsSyncLogic.settle()` is applied inside one atomic `settings.update`: a key whose live value differs from the snapshot keeps its newer value and stays dirty; only pushed, unedited keys leave the dirty set. |
| H | Sync | `syncSoon` cancelled an in-flight round (and its half-done pushes). | `rounding`/`rerun` flags: a change during a round schedules one more round after it. |
| M | Sync | A dirty `homeOrder` replaced the server order, dropping catalogs only the other device knew. | `mergeOrder`: local order first, remote-only keys appended; an empty local order (layout reset) stays empty. |
| M | Account | Only the code `unauthenticated` counted as signed out; other 401 bodies kept the session. | Any 401 except `invalid_credentials` is unauthenticated. Requests carry `Cache-Control: no-store, no-cache`. |
| H | Player | After process death Android recreates `PlayerActivity` from its intent, but the in-memory `PlaybackRef` registry was empty, so every progress report was silently dropped. | `PlayerDependencies.onPlaybackStarting` re-registers the ref (`ensureRegistered`) from the `PlaybackRequest`. Needs device. |
| M | Player | Progress reports were independent coroutines: a late 10 s tick could overwrite the final position, and the repository's read-modify-write could interleave. | One FIFO queue (`Channel`, single consumer). |
| M | Player | `orientation`, `keepScreenOn`, `pauseOnHeadphoneUnplug`, `nextEpisodeCountdownSec`, `askBeforeNext` were stored but never applied (landscape, screen-on, noisy-handling and a 10 s countdown were hard-coded). | Mapped into `PlayerSettings` and used by the activity/controller. `askBeforeNext` turns the overlay into an offer that never auto-starts. Needs device. |
| M | Player | Subtitle bold, colour, background, background opacity, edge style and edge colour changed in the player were never saved. | New `AppSettings` fields, DataStore keys and mapper round trip (unit tested). Older installs fall back to the outline flag. |
| L | Player | Subtitle language default was the device language only. | Device language, then English. |
| M | Player | Touch targets: pill buttons about 36dp, round glyphs 44dp, seek bar 36dp. | 48dp targets. |
| M | Network | The player used the shared client with the 50 MB disk cache, so full-file `200` video responses were written to the cache. The updater used the same client and followed https to http redirects. | `playerHttp` (no cache, longer read timeout) and `downloadHttp` (no cache, `followSslRedirects(false)`). |
| M | Updater | The "Later" button and Back did nothing in the "Ready to install" dialog. | Ready becomes Available (not prompting); the file stays cached. |
| M | Updater | After the user allowed "install unknown apps", nothing resumed the install. | `resumeInstallIfPending()` from `MainActivity.onResume`. Needs device. |
| L | Updater | Auto-check throttle 6 h (spec 12 h); no skip-version; no free-space check. | 12 h; "Skip this version" (manual checks still offer it); needs 2x the APK size free before downloading. |
| M | Manifest | `allowBackup` with no rules: the sync session cookie (`liuying_account` prefs) and the settings store (metadata API key) could be backed up. | `fullBackupContent` and `dataExtractionRules` exclude them. |
| L | Manifest | `configChanges` lacked uiMode/density/fontScale/layoutDirection, so dark-mode or font-size changes restarted the player. | Added to both activities. |
| M | Links | No deep links. | `stremio://` and `https://.../manifest.json` open Liuying on the You tab with the add-source preview started (`singleTask` + `onNewIntent`). |
| M | Today | `TodayViewModel` used `WhileSubscribed(5s)`: after 5 s on Details the whole pipeline restarted and the rows fell back to skeletons, losing scroll position. | `SharingStarted.Lazily`. |
| L | Details | Episode list key was only `videoId` (can repeat in sloppy addon data, crashing LazyColumn). | Key is `season:episode:videoId`. |
| L | Dock | Fixed 68dp bar clipped labels at large system font scale. | Dock text scale capped at 1.0. |
| M | Details | Tapping an unreleased episode opened the streams sheet. | Toast "This episode has not aired yet." |
| M | Streams | `bingeGroup` was parsed but unused; nothing remembered the last stream. | The chosen stream's binge group is stored in `ProgressEntry.streamHint`; when the next episode is opened, a stream from the same group is started automatically even in "show list" mode, otherwise the list is shown. Needs device. |

## Features added (were missing from the spec)

- **See all / Browse grid** (`ui/browse`): uses the existing `CatalogPager` (skip paging), genre chips, fixed-option extras, loading/empty/error/retry states, end marker, refresh. Entry from the "See all" button on a Today row.
- **Source detail** (`ui/sources`): description, resources, last refresh, last error, per-catalog "On Today" (the web-compatible hidden flag, so it also syncs) and "In Browse" switches, catalogs that need unsupported input, Configure (opens the source's `/configure` in the browser), copy and share link, refresh, enable.
- **Paste** button in the add-source flow.
- **Pull to refresh** and an "all sources failed" banner on Today.
- **Data and storage** section: cache size, clear cache, clear watch history (rows are zeroed and stamped now, so the clear syncs instead of being undone by the next pull), copy debug info (hosts only, no URLs or credentials), reset app with a typed `RESET`.
- **Shelf**: 3 columns at 360dp, poster fills its cell, undo snackbar on remove.

## Verified correct (no change)

- `MIGRATION_1_2` SQL against the exported `1.json` and `2.json` (tables `plugin_repos` and `plugins`, plus the index) matches exactly.
- FileProvider authority `${applicationId}.fileprovider` equals the runtime `${packageName}.fileprovider`; `file_paths.xml` `updates/` matches `cacheDir/updates`.
- `AddonClient` error mapping, manifest parsing, `ProgressRules`, `IsoDate`, stream quality parser.
- Cleartext traffic is allowed by design (http sources are allowed after an explicit warning in the add flow).
- `onUserLeaveHint` PiP, Origin header and cookie handling in `AccountApi`.
- Release build has `isMinifyEnabled = false` on main.

## Not fixed (open)

| Sev | Area | Finding |
|---|---|---|
| M | Settings | `metadataApiKey` is stored in plain DataStore. It is excluded from backup now, but not encrypted. A Keystore wrapper is a separate change (no new dependency was added). |
| M | Player | No notification/foreground service for background audio and no `POST_NOTIFICATIONS` request; a `MediaSession` exists. No in-player stream switch, previous/next buttons, pinch zoom, or 16:9/4:3 aspect. |
| M | Player | Subtitle files are read as UTF-8 only (no BOM/UTF-16/windows-1252 detection). |
| M | Theme | Dark only; `AppSettings.theme` is stored but unused (Light, System, Pure black missing). |
| M | Details | No cast row, trailer, "Choose stream manually", "Copy id", stream long-press sheet or external-player hand-off. |
| L | Shelf | No status filters or multi-select. |
| L | Today | No poster long-press sheet. |
| L | Sync | Dismissed "continue watching" cards are not synced. A library item of type `other` is limited by the server's type mapping. |
| L | Domain | `markWatched` bumps `updatedAt`, so a dismissed card can reappear; `EpisodeLogic.playTarget` uses the 90 % default instead of the user's threshold. |
| L | Net | `AddonClient` reads `body.string()` without a size cap for chunked responses (other agent's file, left alone). |
| L | Search | Debounce is 350 ms (spec about 400 ms); Enter does not bypass it. |
| L | Updater | No store-install detection, no mobile-data-only setting, no unmetered-only auto check. |
| L | Backup | The Room database is still backed up (sources, shelf, progress), which is intended. |

## Tests added

`SettingsSyncLogicTest` (order merge, settle), `PlayerSettingsMapperTest` (subtitle style round trip, playback settings, language default, countdown length), `UpdaterTest.autoCheckThrottle` (12 h).

## Process notes

- Gradle runs share `/tmp/gradle.lock`. `flock /tmp/gradle.lock scripts/release-android.sh` wraps a script that itself takes the same lock, which deadlocks (the outer flock keeps the lock, the inner waits for it). That left every later build queued indefinitely. Run the script without the outer `flock`.

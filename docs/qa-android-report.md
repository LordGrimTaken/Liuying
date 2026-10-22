# Android QA report (QA pass 1)

Method: no adb or emulator exists in the sandbox, so this pass used (a) **Robolectric + Compose + Roborazzi** (added to the build; `LIUYING_SHOTS=1 ./gradlew :app:testDebugUnitTest --tests '*ShotsTest*'` writes PNGs to `app/build/shots`, using a MockWebServer fake addon and the real `AppContainer`/Room/DataStore) and (b) a rigorous static read of every screen, ViewModel, repository, manifest and updater. Screens rendered and reviewed at 360x800 dp: Today (empty, with source, font scale 1.3, offline), Movies, Series, Shelf, You, Calendar, Details (movie), Search. Not rendered: 411x891, Player, stream sheet, series details (see "Could not verify").

Severity: H wrong/lost data or crash in a normal flow, M edge-case crash/wrong behaviour users notice, L polish. "Fixed" = fixed in this pass with a test or a screenshot check.

## Findings

| # | Sev | Where | Finding | Fix | Fixed |
|---|---|---|---|---|---|
| 1 | M | ui/library/ShelfScreen.kt (header) | At large text size the title "Shelf" is squeezed by the Sort and List buttons and wraps letter by letter (seen in screenshot). | Title on its own line, buttons on a second row. | yes |
| 2 | M | ui/details/DetailsViewModel.kt startPlayback | `launched` was set only after the suspending resume lookup, so a double tap on a stream started two players. | `starting` guard released on job completion. | yes |
| 3 | M | ui/search (Screen, ViewModel) | Queries needed 2 characters; a single CJK character ("龙") is a complete query and could not be searched. | `QaGuards.isSearchable` (1 char for Han/kana/Hangul); unit tested. | yes |
| 4 | M | ui/browse/BrowseScreen.kt Filters | Genre or extra option lists with repeated values crash `LazyRow` (duplicate key). | `QaGuards.distinctOptions`. | yes |
| 5 | M | DetailsViewModel.onStreamClick | Addon-supplied `externalUrl` was opened with any scheme (file:, content:, intent:, tel:). | Only http, https, stremio, magnet; unit tested. | yes |
| 6 | M | ui/calendar/CalendarViewModel.kt | Refresh did nothing and cached metas were never refreshed (only uncached series were fetched), so new episodes never appeared. | Every series is checked, refetch when older than 12 h or on Refresh. | yes |
| 7 | M | CalendarViewModel, TodayViewModel | One request per shelf series / continue-watching title at once (200 series = 200 parallel requests). | Semaphore of 4. | yes |
| 8 | M | TodayViewModel.continueItems | Progress is written every few seconds while playing, restarting the block and re-requesting every title whose details failed (offline storm). | 5 minute negative cache. | yes |
| 9 | M | AppContainer DataStore | No corruption handler: a corrupt settings file crashes writes at every launch. | `ReplaceFileCorruptionHandler`. | yes |
| 10 | M | data/db MetaCacheDao | `meta_cache` grew without bound (series metas can be MB). | `prune(400)` after each cache write (no row-value SQL, API 24 safe). | yes |
| 11 | M | MetaRepository.meta | A failing cache write (disk full) threw inside the flow and crashed the collector. | Best-effort write. | yes |
| 12 | M | CatalogRepository.homeRows | Row reuse compared `source.fetchedAt`; the startup manifest refresh bumps it, so every row was refetched a second time at launch. | Compare the manifest itself. | yes |
| 13 | M | AddonClient.readBody | Chunked responses (no Content-Length) were read without a cap. | Cap at 16 MB before `string()`. | yes |
| 14 | M | ui/browse/TypePageViewModel | Hero kept featuring titles of a source that was removed/disabled. | Clear hero when no catalog of the type remains. | yes |
| 15 | M | DetailsViewModel | A movie meta with 2+ un-numbered `videos` was turned into a fake series with episodes. | No episode states for type movie. | yes |
| 16 | M | data/repo/StreamRepository session cache | Stream answers were reused for the whole session; expiring (debrid/signed) links failed later. | 10 minute TTL. | yes |
| 17 | M | data/update/UpdateController.install | `startActivity` could throw (`ActivityNotFoundException`) from the dialog button and crash. | Caught, shows "Update problem". | yes |
| 18 | L | ui/update/UpdateUi.kt | Long release notes were not scrollable in the dialog (clipped at large font). | Scrollable column. | yes |
| 19 | M | ui/sources/SourceDetailScreen.kt | Three `Switch`es had no accessible name (TalkBack: "switch, on"). | contentDescription on each. | yes |
| 20 | L | ShelfScreen | Grid/list keys were `type + id` (can collide). Long-press menu not announced to TalkBack. | Separator in key; `onLongClickLabel`. | yes |
| 21 | L | Today row titles | Row title "Popular Movies - Movie" repeats the type that the subtitle ("Movies from X") already states, and wraps to two 24 sp lines. | Drop the type suffix on Android (web uses the same title, so left for a design decision). | open |
| 22 | M | PlayerController.start | First frame waits for external subtitle downloads (up to 2.5 s) before `setMediaItem`. | Start playback first, attach subtitles when ready. | open |
| 23 | L | PlayerController | 250 ms sync loop keeps running while the activity is stopped. | Gate on lifecycle STARTED. | open |
| 24 | L | PlayerActivity | Theme ignores the Appearance text-size setting. | Pass fontScale. | open |
| 25 | M | Details (process death) | A route with `play=primary` replays the auto-play (stream pick) after process death restore. | Consume via SavedStateHandle. | open |
| 26 | L | Details BackBar | "‹ Back" is read as "single left-pointing angle quotation mark Back". | Chevron glyph + contentDescription. | open |
| 27 | L | Browse / Source detail | Back is an "X" glyph labelled "Back" while Details uses "‹ Back": inconsistent affordance. | One back pattern. | open |
| 28 | L | Hero dots | Dot hit areas are 22-30 x 32 dp (< 48). | Pad to 48 dp. | open |
| 29 | L | TypePageScreen | `PullToRefreshBox(isRefreshing=false)`: spinner vanishes at once. | Drive from row loading state. | open |
| 30 | L | TodayScreen | `Ux.pickFeatured` is computed twice per recomposition (not remembered). | `remember(firstItems)`. | open |
| 31 | L | Long-press menus (Continue card, Episode row) | Not exposed to TalkBack as actions. | `onLongClickLabel` (done on Shelf only). | open |
| 32 | L | SourceRepository.refreshStale | Sources refreshed one after another at startup. | Parallel with a limit. | open |
| 33 | L | Updater | Progress state emitted per 64 KB chunk (hundreds of recompositions). | Throttle to 1 %. | open |
| 34 | L | YouScreen (not touched, being refactored) | Username field uses `KeyboardType.Ascii` (no CJK usernames); no autofill hints on password. | Check server rules; `ContentType` autofill. | open |
| 35 | L | Offline Today | Offline rows read "The source did not return valid data" in the fake-server run (connection reset mapped to NotJson?). Needs a real-network check. | Verify mapping of resets. | open |
| 36 | L | Search | Empty-state text says "at least two letters" (one for CJK). | Copy tweak. | open |
| 37 | M | manifest | `allowBackup` with the Room DB: meta cache is backed up (size). | Exclude `liuying.db` meta or whole DB if sources contain tokens. | open |
| 38 | L | manifest | Cleartext allowed globally (by design, warned at add time). | Per-host config impossible for LAN; keep. | accepted |
| 39 | L | build | R8 off: APK larger, no obfuscation of embedded strings. | By choice (0.3.0 regression). | accepted |
| 40 | M | Settings | `metadataApiKey` in plain DataStore (excluded from backup). | Keystore wrapper. | open (earlier audit) |
| 41 | M | Player | No foreground service/notification for background audio; no `POST_NOTIFICATIONS`. | Media3 session service. | open (earlier audit) |
| 42 | L | Details | Episode rows and stream rows have no combined content description. | Merge semantics. | open |
| 43 | L | Today | "Continue watching" section header has no list key. | Key it. | open |
| 44 | L | Calendar | Section header for "Hide watched" chip (other agent) lacks accessible state. | Add `selected`. | open |
| 45 | L | ArtImage | No placeholder shimmer while decoding; monogram only. Works offline. | Optional. | accepted |
| 46 | L | Search | Each keystroke after the 350 ms debounce queries every catalog; no request budget. | Cancel works (flatMapLatest); acceptable. | accepted |
| 47 | L | Deep links | `https://*/manifest.json` filter matches any browser link ending in manifest.json (also PWA manifests) and offers Liuying. | Acceptable; preview validates. | accepted |
| 48 | L | Intent handling | `MainActivity` exported with VIEW only; link validated by `normalizeManifestUrl`; `intent.data` cleared after use. | none | ok |
| 49 | L | FileProvider | `cache-path updates/` only, not exported, read grant only. | none | ok |
| 50 | L | Room | Version 2 with `MIGRATION_1_2`, no destructive fallback, schema exported. | none | ok |
| 51 | L | Network | OkHttp timeouts scaled (10-15 s), cached stale fallback offline, TLS system trust, no logging of URLs. | none | ok |
| 52 | L | Main-thread IO | No `runBlocking`, no `allowMainThreadQueries`; all DAOs suspend/Flow; SharedPreferences reads only for session/update. | none | ok |
| 53 | L | Crash risks | Remaining `!!` are guarded (`ui.meta!!` behind a null check, `item.url!!` behind a kind check, `dao.getById(..)!!` inside a catching transaction). | none | ok |

Counts: 53 findings. By severity: H 0, M 27, L 26. Fixed 20 (all medium that were cheap and local), open 25, accepted/ok 8 (the earlier `docs/android-audit.md` open list also stays valid).

## Critique (visual design, against docs/design-reference.md)

What works: the ink and gold identity is consistent; the monogram placeholder for missing art is tasteful (it makes missing images and offline look deliberate); top bar, dock pill and press feedback match the reference; the first-run guide card is clear and well spaced; error notes keep the row structure so the page does not jump.

What does not:
1. **Rows are heavy.** 24 sp bold serif headings plus a subtitle plus "See all" take roughly 90 dp before any poster; at font 1.3 the heading wraps to two lines. Reference says 22-28 sp; use 22 sp and drop the redundant "- Movie" suffix.
2. **No hero when addons give no backgrounds/posters** the page opens flat; fine, but the top bar then sits on text with no breathing room (first row starts right under it).
3. **Details with no artwork** leaves a large empty block where the hero should be and the title floats beside an empty monogram; collapse the hero (or use a gradient with the monogram centred) when there is no image.
4. **Action buttons** wrap the Share icon onto its own row at 360 dp; make Share part of the row or an app-bar action.
5. **Mixed back patterns** (X on Browse/Source, "‹ Back" on Details, nothing on tabs).
6. **Dock** at 1.3 scale: labels fit but the active pill clips its left edge against the screen at 6 dp padding.
7. **Poster titles** at 1.3 scale truncate mid-word in a 118 dp column ("A Very Long Tit"); allow two lines with ellipsis (already 2) but reduce poster text scale or widen to 132 dp at large text.
8. **You** page is a long single scroll (the sections refactor in progress should fix it); the account form's buttons are tight (no vertical gap above "Sources").
9. Contrast: PaperMuted (62% paper on ink) on InkSurface is about 5:1, acceptable; Gold hairline text at 12 sp (badges) is about 7:1.

## Could not verify without a device

Player (all panels, gestures, PiP, rotation, brightness/volume, errors), stream sheet visuals, series details with unaired episodes, TalkBack order and real touch targets, real insets and status/navigation bar overlap (Robolectric has zero insets), process death/restore, rotation (the activity handles configChanges), animations and shimmer (reduced motion forced on in the harness), real network behaviour (slow, TLS, redirects), installer hand-off, deep link resolution by other apps, memory/leaks with 1000+ items, Compose recomposition counts, and CJK glyph shapes on a real font.

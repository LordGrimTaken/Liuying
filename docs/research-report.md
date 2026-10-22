# Liuying user research report (2026-10-22)

Companion to `docs/backlog-100.md` (137 items, 40 marked Wave 1).

## Method and limits

Sources: public articles, vendor forums, a 2026 industry survey, platform guidelines (Android, WCAG 2.2), accessibility writing on subtitles, and public mirrors of Reddit. GitHub issue lists and reddit.com itself refused automated fetches (robots rules), so open-source issue trackers (Stremio, Findroid, Streamyfin, Cloudstream, Jellyfin clients) were reached only through forum threads, mirrors and secondary write-ups. Counts such as "most upvoted" are therefore not available; ranking below is by how often and how strongly a theme appears across the sources actually read. Treat it as directional, not statistical. Status of Liuying features was checked by grepping `app/`, `site/watch/` and `server/`; the Android app has not run on a device (see `docs/android-audit.md`), so "done" on Android means "present in code".

Scope guard: nothing here involves DRM circumvention, pirated content, or third-party script execution. Liuying hosts nothing; the user supplies sources.

## Executive summary

1. The biggest measurable driver of dissatisfaction is navigation and resume, not catalogue size. In a 2026 CTAM/Hub survey of 3,000 US viewers, 36% (43% of under-25s) cancelled a service over a bad app experience; burying Continue Watching and the watch list was the most damaging issue; pinning them would make 48% and 43% more likely to keep the service ([TheWrap](https://www.thewrap.com/industry-news/business/frustrating-app-experience-subscription-canceled-hub-ctam-survey/), [NewscastStudio](https://www.newscaststudio.com/2026/07/07/more-than-a-third-of-viewers-canceled-streaming-services-over-poor-ux/)). Liuying already puts both first-class.
2. For Stremio-style apps the top complaint is buffering and playback failure; second is addon breakage and confusing setup; third is safety and privacy of third-party addons ([RedCurate](https://redcurate.com/blog/stremio-reddit)). Liuying should make failure recovery fast (suggest another stream, mid-playback switch) and explain sources plainly.
3. Users repeatedly ask for control over "watched" state, accurate resume, and sync that does not lose progress ([Hypercritical](https://hypercritical.co/2022/02/17/streaming-app-sentiments), [Stremio feature request](https://github.com/Stremio/stremio-features/issues/1805)). Liuying's sync logic is strong; it lacks a visible history page, sync status and Trakt.
4. Redesigns that remove user choices or bury content provoke backlash (Plex Roku UI: forced Discover/Live TV entries, hidden libraries, lost Continue Watching) ([Plex forum](https://forums.plex.tv/t/new-ui-is-an-awful-experience/931048?page=23), [Classic Mode thread](https://forums.plex.tv/t/classic-mode-or-use-old-ui-option/931849)). Liuying's per-catalog hide/order is the right stance; keep it.
5. Accessibility is a differentiator: subtitle styling, high-contrast presets, saved presets, decoupled subtitle/audio language ([Smashing](https://smashingmagazine.com/2023/01/closed-captions-subtitles-ux)); WCAG 2.2 target size, focus not obscured, dragging alternatives ([Deque](https://dequeuniversity.com/resources/wcag-2.2)).
6. Biggest functional gaps in Liuying: casting, skip intro/long-skip, previous/next in player, subtitle encoding detection (important for CJK files), Android TV layout, new-episode notifications, Trakt, profiles.

## Top pain points (ranked)

| # | Pain point | Evidence | Liuying today |
|---|---|---|---|
| 1 | Buffering, stalls, failed streams | "Buffering is the single most discussed Stremio complaint" ([RedCurate](https://redcurate.com/blog/stremio-reddit)); r/Stremio titles: "Loading issues", "stuttering", "Sounding cutting?", "Rewind bug" ([mirror](https://reddit.sudovanilla.org/r/Stremio)) | Retry policy and Try another stream exist; no stall prompt, no mid-playback switch (L048, L045) |
| 2 | Continue Watching / watch list buried, resume unreliable | [TheWrap](https://www.thewrap.com/industry-news/business/frustrating-app-experience-subscription-canceled-hub-ctam-survey/); [Hypercritical](https://hypercritical.co/2022/02/17/streaming-app-sentiments): Hulu moved it down and users hated it; resuming minutes early | Pinned first; progress queue is FIFO; no resume-rewind setting (L037) |
| 3 | Watched state wrong and not editable; progress not synced | [Hypercritical](https://hypercritical.co/2022/02/17/streaming-app-sentiments); external players do not update Continue Watching ([stremio-features #1805](https://github.com/Stremio/stremio-features/issues/1805)) | Mark watched/unwatched done; no history page (L023); dismissed cards not synced (L033) |
| 4 | Addon setup confusing, addons break after updates, no vetting | [RedCurate](https://redcurate.com/blog/stremio-reddit); r/Stremio "addons show as not installed" ([mirror](https://reddit.sudovanilla.org/r/Stremio)) | Preview-before-add and last-error shown; no source test, health stats, first-run guide (L078, L081) |
| 5 | Privacy: addon operators see your IP | [RedCurate](https://redcurate.com/blog/stremio-reddit) | No disclosure in add flow (L083) |
| 6 | Subtitles: wrong encoding, not shown on TV, hard to read | r/Stremio "Embedded subtitles don't work" ([mirror](https://reddit.sudovanilla.org/r/Stremio)); [Smashing](https://smashingmagazine.com/2023/01/closed-captions-subtitles-ux) | Style panel is rich; UTF-8 only (L069) |
| 7 | Redesign removes options, forces sections | [Plex forum](https://forums.plex.tv/t/new-ui-is-an-awful-experience/931048?page=23) | Not applicable; keep per-row hide |
| 8 | Skip intro missing or unreliable on Android TV | [Jellyfin forum](https://forum.jellyfin.org/t-intro-skipping-isn-t-working-on-jellyfin-for-android-tv) | Next-episode overlay only; no skip chip (L042, L043) |
| 9 | Autoplay of trailers and next episodes | [Tech Advisor](https://www.techadvisor.com/article/730080/how-to-stop-netflix-autoplay-previews-and-next-episode.html): "a wall of sound and motion", per-profile controls | Trailer autoplay off by default; autoplay next is a setting |
| 10 | Search order poor (exact title not first) | [Jellyfin feature requests](https://forum.jellyfin.org/f-feature-requests?page=11) | Debounced grouped search; no exact-first ranking (L006) |
| 11 | Discovery overload, recommendations not for me | [StreamTV Insider](https://www.streamtvinsider.com/technology/what-viewers-say-about-tv-app-user-experience): discovery is top pain; 59% of 13-24s | Rows only from the user's own sources; no Surprise me or shelf-based picks (L010, L011) |
| 12 | Weak customisation, ads, clutter | [Plex Classic Mode](https://forums.plex.tv/t/classic-mode-or-use-old-ui-option/931849) (ads, own streaming pushed); [Kodi skin request](https://forum.kodi.tv/printthread.php?tid=312699) (hard-coded overlay) | No ads; accent, poster size, hero toggle, row hide/order |
| 13 | Heavy player drains devices | [stremio-features #1805](https://github.com/Stremio/stremio-features/issues/1805) cites overheating | Native Media3 on Android; web uses hls.js; stats overlay absent (L049) |

## Most requested features

1. Chromecast/casting (Jellyfin and Stremio client threads, e.g. [Jellyfin client thread](https://forum.jellyfin.org/t-i-created-a-new-jellyfin-client-for-ios-and-android-supports-downloads-and-chromecas)): missing (L060).
2. Skip intro / recap / credits ([Jellyfin forum](https://forum.jellyfin.org/t-intro-skipping-isn-t-working-on-jellyfin-for-android-tv)): missing (L042, L043).
3. Trakt/Simkl sync and cross-app Continue Watching ([stremio-features #1805](https://github.com/Stremio/stremio-features/issues/1805), [Moviebase comparison](https://moviebase.app/resources/trakt-vs-simkl)): placeholder only (L028, L029).
4. Better stream sorting, e.g. resolution then size ([TorBox request](https://torbox.featurebase.app/p/stremio-addon-sort-by-resolution-then-size), 21 upvotes): partial (L064).
5. Parental controls and profiles: PIN-protected profiles, title locks, autoplay off for kids ([Netflix](https://broadcastprome.com/news/netflix-rolls-out-parental-control-features-pin-protected-profiles), [RedCurate](https://redcurate.com/blog/stremio-reddit) on missing granular controls): missing (L119, L120, profiles L040 area).
6. Android TV / remote layout (Plex, Jellyfin TV threads; [Android TV guidance](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv)): missing (L110).
7. Downloads/offline (Streamyfin and Findroid positioning, Jellyfin client threads): L-effort, deferred; only for plain user-authorised files.
8. Subtitle quality and control: presets, high contrast, saved styles ([Smashing](https://smashingmagazine.com/2023/01/closed-captions-subtitles-ux)); extracted subtitle saving ([Jellyfin FR](https://forum.jellyfin.org/f-feature-requests?page=11)).
9. Lists and collections beyond one watchlist ([Jellyfin FR](https://forum.jellyfin.org/f-feature-requests?page=11)); pinned watch list ([TheWrap](https://www.thewrap.com/industry-news/business/frustrating-app-experience-subscription-canceled-hub-ctam-survey/)).
10. New-episode notifications and a calendar ([Moviebase](https://moviebase.app/resources/trakt-vs-simkl) notes Simkl's airing schedule).

## Design and UX lessons

- Put the thing people came for first: Continue Watching, then the list ([TheWrap](https://www.thewrap.com/industry-news/business/frustrating-app-experience-subscription-canceled-hub-ctam-survey/)). Done.
- Never ship a redesign that removes customisation; offer a way back ([Plex](https://forums.plex.tv/t/classic-mode-or-use-old-ui-option/931849)).
- Player layout priorities from VLC's mobile design: wide seek bar, play/pause, lock, track selector, aspect, extras panel; gestures: tap for UI, swipe seek, left/right swipe for brightness/volume ([VideoLAN](https://wiki.videolan.org/Mobile_Design_Player)). Liuying matches most; lock is missing (L051).
- Empty states are a pause, not a dead end: explain what, why, next step; no-results states echo the query and suggest fixes ([UXPin](https://www.uxpin.com/studio/blog/ux-best-practices-designing-the-overlooked-empty-states/)). Liuying has empty states; error copy should follow the same pattern (L050).
- Offline-first: local DB is the source of truth, queue writes, refresh stale data after 5 to 30 min, surface conflicts instead of silently choosing ([ProAndroidDev](https://proandroiddev.com/the-complete-guide-to-offline-first-architecture-in-android-490d5e9d27ea)). Liuying is local-first; add visible sync status (L032) and the web catalog cache (L089).
- Metadata polish sells media apps: Infuse is praised for minimal UI, automatic artwork and cast, quick subtitles ([Macworld](https://www.macworld.com/article/229592/infuse-5-review-slick-jack-of-all-trades-for-ios-apple-tv-media-playback.html)).
- TV: D-pad is the primary input; separate axes (categories vertical, items horizontal), shallow hierarchy, no on-screen Back, Back never loops ([Android](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv)).
- PiP must be instant and uninterrupted ([Android PiP guideline](https://developer.android.com/distribute/aep/aep-req-picture-in-picture)). Liuying has it.

## Accessibility

- Subtitles: allow font and size choice incl. dyslexia-friendly fonts, colour and opacity, high-contrast presets, saved presets, movable position, decoupled audio/subtitle language, group and alphabetise languages without flags, opt-in default that is remembered ([Smashing](https://smashingmagazine.com/2023/01/closed-captions-subtitles-ux)). Liuying covers size, colour, edge, background, position, delay and language preference; presets, high-contrast preset and font choice are missing (L070, L071).
- WCAG 2.2: targets at least 24 px (44 recommended), focus not hidden by sticky bars, dragging alternatives, accessible authentication (password managers allowed), redundant entry ([Deque](https://dequeuniversity.com/resources/wcag-2.2)). Liuying: 48 dp targets done; autocomplete attributes present; sticky top bar vs focus (L103) and drag reorder alternatives to confirm.
- Reduced motion and font scale are respected. Add system theme follow and a high-contrast mode (L099, L101); a TalkBack pass (L102).
- Colour-only status (sync dot) needs a text/shape equivalent (L104).

## What competitors do well

| Product | Strength worth borrowing | Source |
|---|---|---|
| Netflix | Per-profile autoplay controls, PIN profiles, title locks | [Tech Advisor](https://www.techadvisor.com/article/730080/how-to-stop-netflix-autoplay-previews-and-next-episode.html), [Broadcast Pro ME](https://broadcastprome.com/news/netflix-rolls-out-parental-control-features-pin-protected-profiles) |
| Infuse | Minimal UI, auto metadata, fast subtitle fetch, wide format support | [Macworld](https://www.macworld.com/article/229592/infuse-5-review-slick-jack-of-all-trades-for-ios-apple-tv-media-playback.html) |
| Jellyfin clients | Intro skipping, downloads and casting in third-party clients, SyncPlay | [Jellyfin forum](https://forum.jellyfin.org/t-intro-skipping-isn-t-working-on-jellyfin-for-android-tv) |
| Simkl / Trakt | Airing calendar, scrobbling, progress bars per season | [Moviebase](https://moviebase.app/resources/trakt-vs-simkl) |
| Kodi skins | Deep customisation (and requests for adjustable overlays) | [Kodi forum](https://forum.kodi.tv/printthread.php?tid=312699) |
| VLC mobile | Gesture grammar, lock, extra panel | [VideoLAN](https://wiki.videolan.org/Mobile_Design_Player) |
| Plex (cautionary) | Strong libraries, but UI forced changes and ads drive users away | [Plex forum](https://forums.plex.tv/t/classic-mode-or-use-old-ui-option/931849) |

## What Liuying already does

From the audits and code: pinned Continue Watching and Up Next; Shelf with sort and undo; hero with trailers off by default; per-catalog hide and order (synced); genre tabs and See all paging; grouped progressive search with recents; Details with season/episode watched controls and unaired guard; streams grouped by source with progressive loading, auto-select with cancel, binge-group memory, hide non-playable; native player with audio/subtitle tracks, subtitle style/delay/position, double-tap seek, hold 2x, brightness/volume gestures, aspect modes, PiP, resume ask/auto, next-episode countdown, headphone-unplug pause; backup/restore on web; calendar; username/password accounts with sessions, password change, deletion, delta-safe settings sync, sync dot; guest-first with dismissible nudge; reduce motion, font scale, 48 dp targets; typed-confirm reset; hosts-only debug copy; stremio:// deep links; in-app updater.

## Gaps (summary)

Playback: skip/long-skip, prev/next, mid-playback stream switch, stall prompt, lock, sleep timer, stats overlay, subtitle encodings, casting. Library: history page, ratings, notes, stats, lists, Trakt/Simkl, CSV export/import. Discovery: exact-first search, Surprise me, related titles. Platform: Android TV, light/system theme on Android, notifications, profiles, parental PIN, downloads. Onboarding: first-run guide, clipboard link detection, source test, IP disclosure. Server: recovery code, data export, delta sync.

## Recommended order

Wave 1 (40 items in the backlog) concentrates on: resilience when streams fail (L045, L048, L050), resume and watched accuracy (L023, L037, L033), subtitle correctness and accessibility (L069, L070, L099, L102, L103), setup clarity and privacy (L078, L079, L081, L083), small high-use player controls (L042, L044, L047, L051, L052, L055), and search/library polish (L004 to L010). Defer L-effort items (casting, TV, profiles, Trakt, downloads) to a later wave.

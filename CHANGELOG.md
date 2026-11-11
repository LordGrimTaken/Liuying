# Changelog

Notable changes to Liuying, newest first. Versions match `liuying.versionName` in `gradle.properties`;
the version code increments with every release.

## 0.7.0 — 2026-11-11

First build signed with the new release key. **If you have 0.6.1 or older, uninstall it once** before installing
this version; later updates install over it normally.

- A series reaches **Completed** on the shelf once its whole episode list is watched, and "Mark watched" /
  "Mark unwatched" work on series too, not just films.
- The Movies and Series tabs now show watched badges, offer the poster watched action and honour **Hide watched**.
- A selected poster says "Selected" instead of claiming it was "Seen".
- The Today hero no longer features something you have already watched.
- Resume follows your completion threshold: with it at 95 %/98 % a title watched to 93 % continues instead of
  restarting.
- Up Next never offers an episode that has not aired.
- Metadata from several sources merges (episode lists and credits union instead of the first source winning).
- Streams tagged only "SD" are parsed as SD.
- Sync and the watch-party poll/host writes retry with backoff instead of hammering every 2 s.
- Export: the JSON carries a schema marker.

## Unreleased

- Watch party: a host creates a room and shares a link; guests follow play, pause and seek by polling the
  room every two seconds. Available in the web player and the Android app; no media is relayed.
  API and storage: `docs/watch-party.md`.

## 0.6.1 — 2026-10-23

- Plugins: resilient DNS with retry, cache and DNS-over-HTTPS fallback.
- Plugin runner fixes: JSON rejection, non-ASCII handling, answer detection.
- Added `cheerio`, `crypto-js`, `Buffer`, `TextDecoder` and `AbortController` shims for existing plugins.
- Grouped, readable plugin failure UI with a "Retry failed plugins" action.

## 0.6.0 — 2026-10-23

- Sandboxed plugin runner: QuickJS engine, guarded `fetch`, stream wiring, per-repository consent,
  "Test plugin", and a plugin contract documented in `docs/plugins.md`.

## 0.5.3 — 2026-10-22

- Fix: a tab could become unreachable after opening Calendar from it.

## 0.5.2 — 2026-10-22

- Metadata API key stored encrypted through the Android keystore.
- Background audio with a MediaSessionService.
- CJK usernames.

## 0.5.1 — 2026-10-22

- Android QA pass: playback, shelf, calendar and search fixes.

## 0.5.0 — 2026-10-22

- Web settings rework: accordion sections with summaries, per-source Today layout groups.
- Android settings parity.
- User research report and the 137-item prioritised backlog (`docs/research-report.md`, `docs/backlog-100.md`).
- New logo and brand assets (`docs/logo-v2`).

## 0.4.0 — 2026-10-22

- Library removals propagate across devices; refresh on focus without spurious re-renders.
- Playback resilience: retry counters reset, bounded HLS media recovery, throttled progress sync.
- Server: client IP resolution that skips Cloudflare and private proxy hops for rate limiting.
- Web and server audits (`docs/web-server-audit.md`).
- Fix: adding sources in a release build, with clearer errors.

## 0.3.1 — 2026-10-22

- Android UX parity with the web player.
- Server: change password, delete account, session management, per-user login limiter,
  `DATABASE_URL` redaction, frame deny.
- Web PWA offline shell.

## 0.3.0 — 2026-10-22

- In-app updater: manifest check, checksum-verified download, system installer hand-off.
- Download page and `/downloads` serving with the correct APK MIME type and a no-cache manifest.
- Release script producing the signed APK plus `latest.json`.

## 0.2.0 — 2026-10-22

- Account backend and sign-in, sign-up and account pages.
- Player contract and backend tests.
- Release signing from an external keystore; R8 minification.

## 0.1.0 — 2026-10-22

- Initial commit: website and README.
- Native Android scaffold: Compose, ink dock, theme, launcher icon.

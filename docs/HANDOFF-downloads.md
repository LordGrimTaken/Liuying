# Downloads — what is left (as of 2026-11-10)

Three slices are done and tested; the feature is **not** user-reachable yet.

| Slice | File | State |
|---|---|---|
| Queue rules (two at a time, cap + eviction, pause/resume/failure) | `domain/DownloadPlan.kt` | done, 10 tests |
| Files, index.json, safe names, delete, extension | `data/download/DownloadStore.kt` | done, 8 tests |
| One transfer with Range resume, short-body check | `data/download/DownloadTransfer.kt` | done, 6 tests |

## What remains

1. **Queue worker** — a coroutine that owns the list, calls `DownloadLogic.plan`, runs at most two
   `DownloadTransfer.run` calls, saves via `DownloadStore.save`, and reloads on start so a killed process
   resumes. Where: `data/download/DownloadManager.kt`.
2. **Download action** — the stream row menu and the Details page. It must refuse non-`Direct` streams and any
   URL that is not http(s), and say why.
3. **Downloads section** — a list with progress, pause/resume/delete, and `DownloadLogic.summary` as the header.
   Where: `ui/downloads/`, plus an entry on the You screen.
4. **Player preference** — check `DownloadStore.localFileFor` before resolving a stream, and show an "Offline"
   chip when the local file is used.
5. **Storage cap setting** — `AppSettings` has no download cap yet; the design takes `capBytes` in
   `DownloadLogic.plan` and `DownloadStore.freeBytes()` for the pre-flight check.

## Deliberately not started

Background downloads while the app is closed (WorkManager + notifications), multiple quality variants, external
storage, and any DRM-protected source. See `DESIGN-downloads.md`.

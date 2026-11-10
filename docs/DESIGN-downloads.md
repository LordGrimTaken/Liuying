# Offline downloads — design note (L122)

Status: design + the pure queue logic. No storage, player or UI work yet; this note exists so that part is
built once, in the right order, instead of half-shipped.

## Scope

Download **plain http(s) files the user is authorised to keep**. Concretely:

- the stream the app would play directly (`StreamKind.Direct`), not torrents, not YouTube, not external links;
- one file per episode or movie, no post-processing, no transcoding;
- no DRM handling and no "decryption" of any kind.

Everything else keeps working exactly as now when a download is impossible: torrent-only titles keep needing a
source, and every failure says why.

## What the user sees

1. A **Download** action on a stream row (long-press or the row menu) and on the Details page for the next
   episode.
2. A **Downloads** section in You (and a badge while something is running): queued / downloading with progress /
   paused / failed (with the reason and a retry) / done.
3. Playing a downloaded title uses the local file first, and shows a small "Offline" chip so the user knows why
   it plays instantly.

## Storage

- Directory: `filesDir/downloads/<type>/<titleId>/<videoId>.<ext>`, extension from the URL or the parser's
  filename (`.mkv`, `.mp4`, …). Anything outside this tree is never touched.
- A sidecar `index.json` in the same tree records title, type, videoId, size, state and timestamps, so a
  half-finished file survives a process death.
- `.part` suffix while incomplete; renamed only after the byte count matches `Content-Length` (or the transfer
  finished when the length is unknown).
- A free-space check before starting: refuse with "Not enough space" rather than failing mid-way.

## Queue rules (implemented, pure, tested)

- At most **two** transfers at once; the rest wait in order of request.
- Pause keeps the partial file; resume continues with an HTTP `Range` request and falls back to restarting when
  the server ignores it (or returns a different length).
- Delete removes the file, the index row and, when empty, the title directory.
- **Eviction:** when the configured cap is reached, finished downloads are removed oldest-first until the new
  item fits; active downloads are never evicted. If even an empty cache cannot fit the item, the request is
  refused.
- Failures keep the partial file and the reason; a retry re-queues the same item.

## Out of scope (deliberately)

- Background download while the app is closed (needs WorkManager scheduling and a notification channel).
- Multiple quality variants of the same title.
- Moving downloads to external storage.
- Any DRM-protected source.

## Files

| Piece | Where |
|---|---|
| Queue, progress, eviction — pure | `domain/DownloadPlan.kt` (+ `DownloadPlanTest`) |
| Files, Range requests, index | `data/download/` — not written yet |
| UI | `ui/downloads/` + a You section — not written yet |

The pure part is first on purpose: it is the part that can be wrong in a way tests can see, and it is what the
storage layer will call.

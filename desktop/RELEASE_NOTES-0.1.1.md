## Liuying Desktop 0.1.1

The web player as a desktop app, plus a way out when the built-in player refuses a file.

### Download

| Platform | File |
|---|---|
| Windows 10+ (x64) | `Liuying.Setup.0.1.1.exe` |
| macOS Apple Silicon (arm64) | `Liuying-0.1.1-arm64.dmg` |
| macOS Intel (x64) | `Liuying-0.1.1.dmg` |
| macOS ZIP fallback | `Liuying-0.1.1-arm64-mac.zip` / `Liuying-0.1.1-mac.zip` |

**Both platforms are unsigned.** Windows SmartScreen shows "Windows protected your PC" → *More info →
Run anyway*. macOS Gatekeeper refuses the first launch: right-click the app → **Open**, or run
`xattr -dr com.apple.quarantine /Applications/Liuying.app`.

### New in 0.1.1

- **“Open in player” button inside the app** — sits next to *Watch party* in the video player and hands the
  current stream to mpv or VLC. It only appears in the desktop build; a browser cannot launch local apps.
- **macOS and Linux hand-off** — mpv from Homebrew (`/opt/homebrew/bin`, `/usr/local/bin`),
  `/Applications/VLC.app`, IINA, and `vlc`/`mpv` on Linux.
- **Web player: “Copy link” for risky streams** — rows the browser may refuse (MKV, Dolby, header-locked
  URLs) now offer the link, so it can be pasted into VLC/mpv anywhere.

### What it does

- Loads https://www.liuyingapp.com/watch/ — one UI, no fork to maintain.
- Relaxes CORS and runs a local proxy on `127.0.0.1:47821`, so addons without CORS headers work.
- Watch party works exactly as on the web.

### Requirements

- Windows 10+ x64, or macOS 11+ (arm64/x64).
- Optional: [mpv](https://mpv.io) or [VLC](https://www.videolan.org) for the external-player button.

### Not in this build

No auto-update, no code signing or notarisation, no torrent engine (Torrentio/Comet still need a debrid
account to produce direct HTTPS streams).

### Verify it yourself

```powershell
# Windows
$env:LIUYING_SMOKE='1'
& "$env:LOCALAPPDATA\Programs\Liuying\Liuying.exe" --no-sandbox --disable-gpu
```

```bash
# macOS
LIUYING_SMOKE=1 /Applications/Liuying.app/Contents/MacOS/Liuying --disable-gpu
```

Prints one JSON line: loaded page, whether the desktop bridge is present (`"bridge":"function"`) and the
proxy result, then exits.

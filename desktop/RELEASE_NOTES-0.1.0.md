## Liuying Desktop 0.1.0

The same web player as a desktop app, for the two things a browser can't do: addons that send no CORS
headers, and playback of containers/codecs Chromium refuses (MKV, AC3/DTS) by handing the stream to
mpv or VLC.

### Download

| Platform | File | Size |
|---|---|---|
| Windows 10+ (x64) | `Liuying.Setup.0.1.0.exe` | 78 MB |
| macOS Apple Silicon (arm64) | `Liuying-0.1.0-arm64.dmg` | 94 MB |
| macOS Intel (x64) | `Liuying-0.1.0.dmg` | 98 MB |
| macOS ZIP (if you prefer not to use a DMG) | `Liuying-0.1.0-arm64-mac.zip` / `Liuying-0.1.0-mac.zip` | 91 / 95 MB |

**Both builds are unsigned.** Windows SmartScreen shows "Windows protected your PC" → *More info → Run
anyway*. macOS Gatekeeper will refuse the first launch: right-click the app → **Open**, or run
`xattr -dr com.apple.quarantine /Applications/Liuying.app` once.

The macOS builds are produced in CI on a macOS runner (`.github/workflows/desktop-macos.yml`); a macOS
bundle cannot be produced on Windows.

### What it does

- Loads https://www.liuyingapp.com/watch/ — one UI, no fork to maintain.
- Relaxes `Access-Control-Allow-Origin` and runs a local proxy on `127.0.0.1:47821`, so addons without
  CORS headers work.
- **Play → External player** sends the selected stream to mpv or VLC, for files the embedded player can't
  decode.

### Requirements

- Windows 10+ x64, or macOS 11+ (arm64 or x64).
- Optional: [mpv](https://mpv.io) or VLC for the external-player menu.

### Not in this build

No auto-update, no code signing or notarisation, no torrent engine (Torrentio/Comet links still need a
debrid account to become direct HTTPS streams).

### Verify it yourself

Windows:

```powershell
$env:LIUYING_SMOKE='1'
& "$env:LOCALAPPDATA\Programs\Liuying\Liuying.exe" --no-sandbox --disable-gpu
```

macOS:

```bash
LIUYING_SMOKE=1 /Applications/Liuying.app/Contents/MacOS/Liuying --disable-gpu
```

Either prints one JSON line with the loaded page and the proxy result, then exits.

## Liuying Desktop 0.1.2

Adds an update check, so you no longer have to watch the releases page.

### Download

| Platform | File |
|---|---|
| Windows 10+ (x64) | `Liuying.Setup.0.1.2.exe` |
| macOS Apple Silicon (arm64) | `Liuying-0.1.2-arm64.dmg` |
| macOS Intel (x64) | `Liuying-0.1.2.dmg` |
| macOS ZIP fallback | `Liuying-0.1.2-arm64-mac.zip` / `Liuying-0.1.2-mac.zip` |

### New in 0.1.2

- **Update check.** On launch (at most once every 12 hours) and from **Liuying → Check for updates…**,
  the app asks GitHub Releases whether a newer `desktop-v*` build exists. If one does, it names the
  version and offers to open the release page.
- **It never installs anything by itself.** You download and run the installer, exactly as before. That is
  deliberate: the builds are unsigned, and macOS refuses to apply a silent update to an unsigned bundle.
- Versions are compared numerically, so `0.1.10` correctly counts as newer than `0.1.2`.

### Also included since 0.1.0

- **“Open in player”** in the video player hands the current stream to mpv or VLC.
- macOS and Linux player detection (Homebrew mpv, `/Applications/VLC.app`, IINA, `vlc`/`mpv`).
- CORS-free addons through the local proxy on `127.0.0.1:47821`.

### Requirements

- Windows 10+ x64, or macOS 11+ (arm64/x64).
- Optional: [mpv](https://mpv.io) or [VLC](https://www.videolan.org) for the external-player button.

### Unsigned builds

Windows SmartScreen shows “Windows protected your PC” → *More info → Run anyway*. macOS Gatekeeper
refuses the first launch: right-click the app → **Open**, or
`xattr -dr com.apple.quarantine /Applications/Liuying.app`.

### Verify it yourself

```powershell
# Windows — update check only, no window
$env:LIUYING_UPDATE_CHECK='1'
& "$env:LOCALAPPDATA\Programs\Liuying\Liuying.exe" --no-sandbox --disable-gpu
```

```bash
# macOS
LIUYING_UPDATE_CHECK=1 /Applications/Liuying.app/Contents/MacOS/Liuying --disable-gpu
```

Prints one JSON line with `current`, `latest` and `available`, then exits.

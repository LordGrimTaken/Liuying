## Liuying Desktop 0.1.3

Fixes the reason some addons silently failed in the desktop app: their servers send no CORS headers, so
the page could not read them. Now every addon request goes through the app's local proxy.

### Download

| Platform | File |
|---|---|
| Windows 10+ (x64) | `Liuying.Setup.0.1.3.exe` |
| macOS Apple Silicon (arm64) | `Liuying-0.1.3-arm64.dmg` |
| macOS Intel (x64) | `Liuying-0.1.3.dmg` |
| macOS ZIP fallback | `Liuying-0.1.3-arm64-mac.zip` / `Liuying-0.1.3-mac.zip` |

### What changed

- **Addon requests are proxied.** The page now calls `http://127.0.0.1:47821/?url=…` for every addon
  request, and the main process does the fetch, so a missing `Access-Control-Allow-Origin` no longer
  matters.
- **The desktop session allows that proxy in CSP.** The public site keeps its strict
  `connect-src 'self' https:` policy; only the app's own session adds the loopback origin.
- Verified inside the packaged app: a CORS-less addon loads through the proxy (`ok`) while the same
  direct fetch is refused (`BLOCKED: Failed to fetch`).
- Carries everything from 0.1.2: update check, “Open in player”, macOS/Linux player detection.

### Requirements

- Windows 10+ x64, or macOS 11+ (arm64/x64).
- Optional: [mpv](https://mpv.io) or [VLC](https://www.videolan.org).

### Unsigned builds

Windows SmartScreen → *More info → Run anyway*. macOS → right-click the app → **Open**, or
`xattr -dr com.apple.quarantine /Applications/Liuying.app`.

### Verify it yourself

```powershell
# Windows: prove the proxy path works
$env:LIUYING_CORS_PROBE='1'
& "$env:LOCALAPPDATA\Programs\Liuying\Liuying.exe" --no-sandbox --disable-gpu
# {"corsProbe":true,"proxy":"ok","direct":"BLOCKED: Failed to fetch","proxyFlag":true}

# update check only
$env:LIUYING_UPDATE_CHECK='1'
```

```bash
# macOS
LIUYING_CORS_PROBE=1 /Applications/Liuying.app/Contents/MacOS/Liuying --disable-gpu
```

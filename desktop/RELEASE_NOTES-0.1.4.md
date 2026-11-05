## Liuying Desktop 0.1.4

Fixes the bug that made **every** source fail with "this source did not answer" after signing in.

### What was wrong

The shell added its own `Access-Control-Allow-Origin: *` to every response, including responses that
already carried that header. Two copies of the header make a browser reject the response outright — so
Torrentio, Comet, PenguPlay and the rest all failed inside the app while working fine in a browser tab.

### What changed

- The header is only added when the addon sent none.
- Addon requests still go through the local proxy first (0.1.3 behaviour) and now fall back to a direct
  request if the proxy itself errors.
- **Liuying → About Liuying** shows the running version, so you can confirm what you have.

### Verified in the packaged build

```
proxy  Torrentio (200)   direct  Torrentio (200)
proxy  Comet | ElfHosted (200)   direct  Comet | ElfHosted (200)
```

Both paths answer for the real sources. Earlier builds returned `BLOCKED` on the direct path.

### Download

| Platform | File |
|---|---|
| Windows 10+ (x64) | `Liuying.Setup.0.1.4.exe` |
| macOS Apple Silicon (arm64) | `Liuying-0.1.4-arm64.dmg` |
| macOS Intel (x64) | `Liuying-0.1.4.dmg` |
| macOS ZIP fallback | `Liuying-0.1.4-arm64-mac.zip` / `Liuying-0.1.4-mac.zip` |

### Upgrade from 0.1.x

Open **Liuying → Check for updates…** — it will offer this release. Install over the old copy; your
sign-in and sources are kept. Then press **Refresh** on the Sources page.

### Unsigned builds

Windows SmartScreen → *More info → Run anyway*. macOS → right-click the app → **Open**, or
`xattr -dr com.apple.quarantine /Applications/Liuying.app`.

### Verify it yourself

```powershell
$env:LIUYING_CORS_PROBE='1'
& "$env:LOCALAPPDATA\Programs\Liuying\Liuying.exe" --no-sandbox --disable-gpu
```

```bash
LIUYING_CORS_PROBE=1 /Applications/Liuying.app/Contents/MacOS/Liuying --disable-gpu
```

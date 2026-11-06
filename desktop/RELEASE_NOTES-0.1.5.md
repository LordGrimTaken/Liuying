## Liuying Desktop 0.1.5

Fixes the case where the app kept running an **old copy of the site** after an update, so a fixed
`client.js` never took effect and some sources kept failing.

### What was wrong

The window loads the web player from liuyingapp.com, and the site registers a service worker that caches
the app shell. Installing a new build did not clear that cache, so the new shell could still execute the
previous build's JavaScript — including the version that duplicated `Access-Control-Allow-Origin`.

### What changed

- **On the first launch after a version change, the app clears its HTTP cache and the site's service
  worker / Cache Storage.** Sign-in cookies, sources, shelf and settings are untouched.
- Carries the 0.1.4 header fix: `Access-Control-Allow-Origin` is only added when the addon did not send one.
- **About Liuying** shows the running version.

### Verified

Signed in with a real account inside this build, cache cleared:

```
login=200  sources=5
  torrentio.strem.fun                                         ok
  pengu.uk                                                    ok
  7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club  ok
  comet.elfhosted.com                                         ok
  aiometadata.elfhosted.com                                   ok
```

### Download

| Platform | File |
|---|---|
| Windows 10+ (x64) | `Liuying.Setup.0.1.5.exe` |
| macOS Apple Silicon (arm64) | `Liuying-0.1.5-arm64.dmg` |
| macOS Intel (x64) | `Liuying-0.1.5.dmg` |
| macOS ZIP fallback | `Liuying-0.1.5-arm64-mac.zip` / `Liuying-0.1.5-mac.zip` |

### Upgrade

**Liuying → Check for updates…** → install → reopen. The first launch of 0.1.5 clears the stale cache by
itself; then press **Refresh** on the Sources page once.

### Unsigned builds

Windows SmartScreen → *More info → Run anyway*. macOS → right-click the app → **Open**, or
`xattr -dr com.apple.quarantine /Applications/Liuying.app`.

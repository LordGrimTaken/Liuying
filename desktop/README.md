# Liuying Desktop (prototype)

The same web player, wrapped in Electron. It exists for the two things a browser cannot do:

1. **Reach addons that send no CORS headers.** The renderer relaxes `Access-Control-Allow-Origin` on
   responses, and `main.js` also runs a small local proxy (`http://127.0.0.1:47821/?url=<addon-url>`) that
   fetches server-side. `thepiratebay-plus.strem.fun` and similar addons work here and not in the browser.
2. **Hand a stream to a native player.** Menu → *External player* sends the current video URL to `mpv` or
   VLC, which decodes MKV/AC3/DTS that Chromium refuses.

It loads `https://www.liuyingapp.com/watch/` unchanged — there is no second UI to maintain. Point it
elsewhere with `LIUYING_APP_URL` (for example the local Worker on `http://127.0.0.1:8787/watch/`).

## Run

```powershell
cd desktop
npm install
npm start
```

## Self-check (headless, no window)

```powershell
$env:LIUYING_SMOKE='1'
.\node_modules\electron\dist\electron.exe . --no-sandbox --disable-gpu
```

It loads the site in a hidden window, fetches the Cinemeta manifest through the proxy and prints one JSON
line, then exits. Verified output:

```json
{"smoke":true,"title":"Liuying Web Player","url":"https://www.liuyingapp.com/watch/","proxy":{"status":200,"cors":"*","name":"Cinemeta"}}
```

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `LIUYING_APP_URL` | `https://www.liuyingapp.com/watch/` | Which site to load |
| `LIUYING_PROXY_PORT` | `47821` | Local proxy port |
| `LIUYING_ADDON_HOSTS` | *(any https host)* | Comma-separated allowlist for the proxy |
| `LIUYING_SMOKE` | — | `1` runs the headless self-check |

Windows sets `ELECTRON_RUN_AS_NODE=1` in some shells; if Electron starts as plain Node and throws
`Cannot read properties of undefined (reading 'whenReady')`, clear that variable first.

## What this is not yet

- No installer, no code signing, no auto-update.
- The web app is not told the proxy exists; addons still go out directly and rely on the relaxed response
  headers. Routing addon fetches through the proxy from inside the page is the next step (a
  `window.LIUYING_DESKTOP` flag plus a branch in `site/watch/addon/client.js`).
- No torrent engine. Torrentio/Comet links still need a debrid account to become direct HTTPS URLs.

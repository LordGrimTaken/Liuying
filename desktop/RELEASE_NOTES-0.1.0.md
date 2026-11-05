## Liuying Desktop 0.1.0

The same web player as a Windows app, for the two things a browser can't do: addons that send no CORS
headers, and playback of containers/codecs Chromium refuses (MKV, AC3/DTS) by handing the stream to
mpv or VLC.

### Install

Download `Liuying Setup 0.1.0.exe` and run it. It installs per-user, no admin rights needed, and adds a
Start-menu shortcut.

**The installer is unsigned.** Windows SmartScreen will show "Windows protected your PC" — choose
*More info → Run anyway*. Nothing is wrong with the file; it simply has no code-signing certificate.

### What it does

- Loads https://www.liuyingapp.com/watch/ — one UI, no fork to maintain.
- Relaxes `Access-Control-Allow-Origin` and runs a local proxy on `127.0.0.1:47821`, so addons without
  CORS headers work.
- **Play → External player** sends the selected stream to mpv or VLC, for files the embedded player can't
  decode.

### Requirements

- Windows 10 or later, x64.
- Optional: [mpv](https://mpv.io) or VLC for the external-player menu.

### Not in this build

No auto-update, no code signing, no torrent engine (Torrentio/Comet links still need a debrid account to
become direct HTTPS streams).

### Verify it yourself

```powershell
$env:LIUYING_SMOKE='1'
& "$env:LOCALAPPDATA\Programs\Liuying\Liuying.exe" --no-sandbox --disable-gpu
```

It loads the site in a hidden window and prints one JSON line, then exits.

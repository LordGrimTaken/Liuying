// Liuying desktop shell.
//
// Why this exists: in a browser, an addon only works if its server sends CORS headers, and the page can only
// play what Chromium decodes (no MKV/AC3/DTS, no torrent hashes). In Electron the renderer is still Chromium,
// so this shell does two things the browser cannot:
//
//   1. Serves addon requests through a local proxy in the main process, so a CORS-less addon works here.
//   2. Hands an unsupported stream to a native player (mpv/VLC) on request.
//
// It loads the production web player unchanged; there is no second UI to maintain.
const { app, BrowserWindow, Menu, ipcMain, shell, dialog } = require('electron');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');

const APP_URL = process.env.LIUYING_APP_URL || 'https://www.liuyingapp.com/watch/';
const PROXY_PORT = Number(process.env.LIUYING_PROXY_PORT || 47821);
const ALLOWED_HOSTS = (process.env.LIUYING_ADDON_HOSTS || '').split(',').map((s) => s.trim()).filter(Boolean);

let win = null;
let proxy = null;

/** A tiny forwarding proxy: GET http://127.0.0.1:47821/?url=<addon-url>. It only speaks to https addon hosts. */
function startProxy() {
  proxy = http.createServer(async (req, res) => {
    try {
      const target = new URL(new URL(req.url, `http://127.0.0.1:${PROXY_PORT}`).searchParams.get('url') || '');
      if (target.protocol !== 'https:') { res.writeHead(400).end('only https targets'); return; }
      if (ALLOWED_HOSTS.length && !ALLOWED_HOSTS.includes(target.host)) { res.writeHead(403).end('host not allowed'); return; }
      const upstream = await fetch(target, {
        headers: { accept: req.headers.accept || 'application/json', 'user-agent': 'LiuyingDesktop/0.1' },
        redirect: 'follow',
      });
      const body = Buffer.from(await upstream.arrayBuffer());
      res.writeHead(upstream.status, {
        'content-type': upstream.headers.get('content-type') || 'application/octet-stream',
        'access-control-allow-origin': '*',
        'cache-control': 'no-store',
      });
      res.end(body);
    } catch (e) {
      res.writeHead(502, { 'content-type': 'application/json' }).end(JSON.stringify({ error: String(e && e.message || e) }));
    }
  });
  proxy.listen(PROXY_PORT, '127.0.0.1');
}

/** Try a native player for containers/codecs Chromium cannot decode. Returns the player used, or null. */
function openExternal(streamUrl) {
  const win = [
    { cmd: 'mpv', args: [streamUrl] },
    { cmd: 'C:\\Program Files\\VideoLAN\\VLC\\vlc.exe', args: [streamUrl] },
    { cmd: 'C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe', args: [streamUrl] },
  ];
  const mac = [
    { cmd: '/opt/homebrew/bin/mpv', args: [streamUrl] },
    { cmd: '/usr/local/bin/mpv', args: [streamUrl] },
    { cmd: '/Applications/VLC.app/Contents/MacOS/VLC', args: [streamUrl] },
    { cmd: 'open', args: ['-a', 'VLC', streamUrl] },
    { cmd: 'open', args: ['-a', 'IINA', streamUrl] },
  ];
  const linux = [
    { cmd: 'mpv', args: [streamUrl] },
    { cmd: 'vlc', args: [streamUrl] },
  ];
  const candidates = process.platform === 'darwin' ? mac : process.platform === 'linux' ? linux : win;
  for (const { cmd, args } of candidates) {
    try {
      const child = spawn(cmd, args, { detached: true, stdio: 'ignore' });
      child.unref();
      return require('node:path').basename(cmd);
    } catch { /* try the next one */ }
  }
  return null;
}

function createWindow({ show = true } = {}) {
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    show,
    backgroundColor: '#14110f',
    title: 'Liuying',
    autoHideMenuBar: true,
    webPreferences: {
      // The renderer talks to the local proxy only; it never needs Node.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  // Addon hosts are reached through the proxy; the page itself stays same-origin with the real site.
  win.webContents.session.webRequest.onHeadersReceived((details, callback) => {
    callback({ responseHeaders: { ...details.responseHeaders, 'Access-Control-Allow-Origin': ['*'] } });
  });

  win.loadURL(APP_URL);
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.on('closed', () => { win = null; });
}

function buildMenu() {
  const template = [
    { label: 'Liuying', submenu: [{ role: 'reload' }, { role: 'toggleDevTools' }, { type: 'separator' }, { role: 'quit' }] },
    { label: 'Play', submenu: [{ role: 'togglefullscreen' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'resetZoom' }] },
    {
      label: 'External player',
      click: async () => {
        const url = await win?.webContents.executeJavaScript(
          `(window.__liuyingPlayback && window.__liuyingPlayback.url) || document.querySelector('video')?.currentSrc || document.querySelector('video')?.src || ''`);
        if (!url) {
          dialog.showMessageBox({ message: 'No stream selected yet.', detail: 'Open a title, pick a stream, then try again.' });
          return;
        }
        const used = openExternal(url);
        if (!used) dialog.showMessageBox({ message: 'No external player found.', detail: 'Install mpv or VLC, or set the path in desktop/main.js.' });
        else dialog.showMessageBox({ message: 'Opened in ' + used, detail: url.slice(0, 120) });
      },
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(() => {
  startProxy();
  buildMenu();
  ipcMain.handle('open-external', (_event, url) => {
    if (typeof url !== 'string' || !/^https?:/i.test(url)) return null;
    return openExternal(url);
  });
  if (process.env.LIUYING_SMOKE === '1') {
    // Headless self-check: load the site in a hidden window, print what rendered, exit.
    createWindow({ show: false });
    win.webContents.once('did-finish-load', async () => {
      try {
        const title = await win.webContents.executeJavaScript('document.title');
        const bridge = await win.webContents.executeJavaScript('typeof window.liuyingDesktop?.openExternal');
        const proxyOk = await new Promise((res) => {
          http.get(`http://127.0.0.1:${PROXY_PORT}/?url=https%3A%2F%2Fv3-cinemeta.strem.io%2Fmanifest.json`, (r) => {
            let body = '';
            r.on('data', (c) => { body += c; });
            r.on('end', () => res({ status: r.statusCode, cors: r.headers['access-control-allow-origin'], name: (() => { try { return JSON.parse(body).name; } catch { return null; } })() }));
          }).on('error', (e) => res({ error: String(e.message) }));
        });
        console.log(JSON.stringify({ smoke: true, title, url: win.webContents.getURL(), bridge, proxy: proxyOk }));
        app.exit(0);
      } catch (e) { console.error(String(e)); app.exit(3); }
    });
    setTimeout(() => { console.error('smoke timeout'); app.exit(2); }, 25000);
    return;
  }
  createWindow();
  ipcMain.handle('proxy-info', () => ({ port: PROXY_PORT }));
  app.on('activate', () => { if (!win) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
app.on('will-quit', () => { try { proxy?.close(); } catch { /* already gone */ } });

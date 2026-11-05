// Bridges the page to the shell: only what the player needs, nothing else.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('liuyingDesktop', {
  platform: process.platform,
  // Prefix an addon URL with this to fetch it through the main process (no CORS involved).
  proxy: 'http://127.0.0.1:47821/?url=',
  // Hand a stream URL to mpv/VLC. Resolves to the player name, or null when none was found.
  openExternal: (url) => ipcRenderer.invoke('open-external', String(url || '')),
});

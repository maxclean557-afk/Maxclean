'use strict';
// The bridge between the browser's own interface (src/ui) and the main process. Only the
// interface gets it; web pages never do.
const { contextBridge, ipcRenderer } = require('electron');

const send = (ch, ...a) => ipcRenderer.send(ch, ...a);
const on = (ch, fn) => ipcRenderer.on(ch, (e, ...a) => fn(...a));

contextBridge.exposeInMainWorld('mc', {
  ready: () => send('ui-ready'),
  layout: (top) => send('layout', top),
  navigate: (text) => send('navigate', text),
  newTab: () => send('new-tab'),
  switchTab: (id) => send('switch-tab', id),
  closeTab: (id) => send('close-tab', id),
  back: () => send('back'),
  forward: () => send('forward'),
  reload: () => send('reload'),
  home: () => send('home'),
  openUrl: (u) => send('open-url', u),
  toggleTor: () => send('toggle-tor'),
  retry: () => send('retry'),
  wipeExit: () => send('wipe-exit'),
  overlay: (open) => ipcRenderer.invoke('overlay', open),
  setSettings: (s) => send('set-settings', s),
  windowCmd: (w) => send('window', w),
  github: () => send('open-external-github'),
  on,
});

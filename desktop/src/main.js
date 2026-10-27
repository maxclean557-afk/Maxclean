'use strict';
// Maxclean for Windows. One window: the interface (src/ui) on top, web pages below it in their
// own views. Every page lives in one in-memory session: nothing is written to disk, and closing
// a tab wipes cookies, cache and storage. All traffic goes through Tor (or the user's proxies),
// and until that route is up, everything goes to a dead proxy, so nothing leaks directly.

const { app, BrowserWindow, WebContentsView, session, ipcMain, protocol, nativeTheme, shell } = require('electron');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { Settings } = require('./settings');
const { Tor } = require('./tor');
const { ProxyChain, parseChain, parseHop } = require('./chain');
const { Blocker } = require('./blocker');
const engines = require('./engines');

// ---------------------------------------------------------------- privacy before start

const SETTINGS_DIR = path.join(app.getPath('appData'), 'Maxclean');
const PROFILE_ROOT = path.join(os.tmpdir(), 'maxclean-session');
// The single-instance lock lives in a fixed folder (the per-run profile is random).
app.setPath('userData', path.join(SETTINGS_DIR, 'lock'));
const PRIMARY = app.requestSingleInstanceLock();
if (!PRIMARY) app.exit(0);
// Remove profiles left behind if the app was killed last time.
if (PRIMARY) try { fs.rmSync(PROFILE_ROOT, { recursive: true, force: true }); } catch (e) { /* in use: next time */ }
const PROFILE = path.join(PROFILE_ROOT, crypto.randomBytes(8).toString('hex'));
app.setPath('userData', PROFILE);
app.setPath('sessionData', PROFILE);
app.setPath('crashDumps', path.join(PROFILE, 'crash'));

// Chromium features that talk to Google or write to disk on their own.
for (const sw of ['disable-background-networking', 'disable-component-update', 'disable-domain-reliability',
  'disable-breakpad', 'no-pings', 'disable-sync', 'no-default-browser-check', 'disable-client-side-phishing-detection',
  'disable-gpu-shader-disk-cache', 'disk-cache-size=1', 'media-cache-size=1']) {
  app.commandLine.appendSwitch(sw);
}
app.commandLine.appendSwitch('disable-features',
  'AutofillServerCommunication,MediaRouter,OptimizationHints,InterestFeedContentSuggestions,Translate,SpareRendererForSitePerProcess');
app.commandLine.appendSwitch('force-webrtc-ip-handling-policy', 'disable_non_proxied_udp');

protocol.registerSchemesAsPrivileged([
  { scheme: 'maxclean', privileges: { standard: true, secure: true, supportFetchAPI: true } },
]);

const settings = new Settings(SETTINGS_DIR);
const RES = app.isPackaged ? process.resourcesPath : path.join(__dirname, '..');
const tor = new Tor(path.join(RES, 'tor'), path.join(SETTINGS_DIR, 'tor'));
const blocker = new Blocker(path.join(__dirname, 'assets', 'filters'));
const FP_SEED = crypto.randomBytes(4).readUInt32BE(0);
const FP_JS = fs.readFileSync(path.join(__dirname, 'assets', 'fingerprint.js'), 'utf8').replace(/^(\/\/[^\n]*\n)+/, '');
const COSMETIC = fs.readFileSync(path.join(__dirname, 'assets', 'filters', 'cosmetic.css'), 'utf8');
const COOKIE_CSS = fs.readFileSync(path.join(__dirname, 'assets', 'filters', 'cookie_banners.css'), 'utf8');
const BLACKHOLE = 'socks5://127.0.0.1:9';

let win = null;
let ses = null;
let tabs = [];
let current = null;
let topInset = 96;
let overlayOpen = false;
let route = { ready: false, text: '', failed: false, progress: 0 };
let chainIn = null;
let chainOut = null;
let netSig = '';
let blockedOnPage = 0;

// ---------------------------------------------------------------- session

const CHROME_MAJOR = process.versions.chrome.split('.')[0];
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/'
  + CHROME_MAJOR + '.0.0.0 Safari/537.36';

/**
 * The interface window's own session must never touch the network: no spell-check dictionary
 * downloads, nothing outside the app's files.
 */
function lockDefaultSession() {
  const d = session.defaultSession;
  d.setSpellCheckerEnabled(false);
  d.setSpellCheckerLanguages([]);
  d.setSpellCheckerDictionaryDownloadURL('http://127.0.0.1:9/');
  d.setProxy({ proxyRules: BLACKHOLE });
  d.setPermissionRequestHandler((wc, perm, cb) => cb(false));
  d.webRequest.onBeforeRequest((details, cb) => {
    cb({ cancel: !/^(file|devtools|data|blob):/i.test(details.url) });
  });
}

function setupSession() {
  lockDefaultSession();
  // No "persist:" prefix: the session lives in memory only.
  ses = session.fromPartition('maxclean-' + crypto.randomBytes(6).toString('hex'), { cache: false });
  ses.setUserAgent(USER_AGENT, 'ru-RU,ru;q=0.9,en;q=0.8');
  ses.setSpellCheckerEnabled(false);
  ses.setPermissionRequestHandler((wc, perm, cb) => cb(false));
  ses.setPermissionCheckHandler(() => false);
  ses.setProxy({ proxyRules: BLACKHOLE });
  ses.on('will-download', (e) => {
    e.preventDefault();
    toUi('toast', 'Загрузки отключены: файл оставил бы след на компьютере');
  });
  ses.webRequest.onBeforeRequest((details, cb) => {
    const isMain = details.resourceType === 'mainFrame';
    const verdict = blocker.decide(details.url, isMain);
    if (verdict === 'upgrade') return cb({ redirectURL: details.url.replace(/^http:/i, 'https:') });
    if (verdict === 'block') {
      if (!isMain) {
        blockedOnPage++;
        toUi('blocked', blockedOnPage);
      }
      return cb({ cancel: true });
    }
    cb({});
  });
  ses.webRequest.onBeforeSendHeaders((details, cb) => {
    const h = details.requestHeaders;
    for (const k of Object.keys(h)) {
      // Electron would name itself in these; a plain Chrome says nothing of the kind.
      if (/^sec-ch-ua/i.test(k)) delete h[k];
    }
    h['Sec-CH-UA'] = '"Chromium";v="' + CHROME_MAJOR + '", "Google Chrome";v="' + CHROME_MAJOR + '", "Not_A Brand";v="99"';
    h['Sec-CH-UA-Mobile'] = '?0';
    h['Sec-CH-UA-Platform'] = '"Windows"';
    if (settings.get('gpc')) {
      h['Sec-GPC'] = '1';
      h.DNT = '1';
    }
    cb({ requestHeaders: h });
  });
  ses.protocol.handle('maxclean', (req) => serveInternal(req));
}

function serveInternal(req) {
  const u = new URL(req.url);
  if (u.hostname === 'home' && u.pathname.endsWith('.ttf')) {
    return new Response(fs.readFileSync(path.join(__dirname, 'ui', 'Manrope.ttf')), { headers: { 'content-type': 'font/ttf' } });
  }
  return new Response(homeHtml(), { headers: { 'content-type': 'text/html; charset=utf-8' } });
}

// ---------------------------------------------------------------- start page

const ACCENTS = { mint: '#2BD4A4', violet: '#9B6BFF', ocean: '#3D8BFF', amber: '#FF9F1C', rose: '#FF5C8A' };

function palette() {
  const theme = settings.get('theme');
  const dark = theme === 'dark' || (theme === 'system' && nativeTheme.shouldUseDarkColors);
  return {
    dark,
    bg: dark ? '#0E1116' : '#F6F7F9',
    text: dark ? '#E8ECF1' : '#14171C',
    muted: dark ? '#8A94A3' : '#5E6673',
    accent: ACCENTS[settings.get('accent')] || ACCENTS.mint,
    tor: '#9B6BFF',
    glass: dark ? 'rgba(255,255,255,.07)' : 'rgba(255,255,255,.62)',
    line: dark ? 'rgba(255,255,255,.12)' : 'rgba(20,23,28,.09)',
  };
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function homeHtml() {
  const p = palette();
  const torOn = settings.get('tor');
  let tiles = '';
  for (const line of settings.get('quickLinks').split(/\r?\n/).slice(0, 8)) {
    const bar = line.indexOf('|');
    if (bar <= 0) continue;
    const name = line.slice(0, bar).trim();
    let url = line.slice(bar + 1).trim();
    if (!url.includes('://')) url = 'https://' + url;
    if (!/^https?:\/\//.test(url) || !name) continue;
    tiles += "<a class='tile' href='" + esc(url) + "'><b>" + esc(name[0].toUpperCase()) + '</b><span>' + esc(name) + '</span></a>';
  }
  const torText = !torOn ? 'Tor выключен' : route.ready ? 'Tor включён · 3 узла' + (settings.get('bridges') !== 'none' ? ' · мосты' : '') : 'Подключаюсь к Tor…';
  const now = new Date();
  return fs.readFileSync(path.join(__dirname, 'ui', 'home.html'), 'utf8')
    .replace('{{FONT}}', "'maxclean://home/Manrope.ttf'")
    .replace('{{BG}}', p.bg).replace('{{TEXT}}', p.text).replace('{{MUTED}}', p.muted)
    .replace('{{ACCENT}}', p.accent).replace('{{TOR}}', p.tor)
    .replace('{{GLASS}}', p.glass).replace('{{LINE}}', p.line)
    .replace('{{TORCLASS}}', torOn && route.ready ? 'on' : '')
    .replace('{{TORTEXT}}', esc(torText))
    .replace('{{TORON}}', torOn && route.ready ? 'true' : 'false')
    .replace('{{TIME}}', now.toTimeString().slice(0, 5))
    .replace('{{DATE}}', now.toLocaleDateString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' }))
    .replace('{{TILES}}', tiles);
}

// ---------------------------------------------------------------- page script

const PROFILES = {
  generic: null,
  windows: { model: '', android: '', w: 1920, h: 1080, dpr: 1, cores: 8, mem: 8 },
  laptop: { model: '', android: '', w: 1536, h: 864, dpr: 1.25, cores: 8, mem: 8 },
};
const PLACES = {
  moscow: [55.7558, 37.6173], amsterdam: [52.3676, 4.9041], berlin: [52.52, 13.405],
  newyork: [40.7128, -74.006], tokyo: [35.6762, 139.6503],
};

function pageScript() {
  let js = '(function(){';
  if (settings.get('disableWebrtc')) {
    js += "try{['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','RTCSessionDescription','RTCIceCandidate']"
      + '.forEach(function(k){try{Object.defineProperty(window,k,{value:undefined,writable:false,configurable:false});}catch(e){}});}catch(e){}';
  }
  if (settings.get('fingerprint')) {
    const profile = PROFILES[settings.get('deviceProfile')] || null;
    js += 'try{(' + FP_JS + ')(' + FP_SEED + ',' + JSON.stringify(profile) + ',0);}catch(e){}';
  }
  if (settings.get('gpc')) {
    js += "try{Object.defineProperty(navigator,'globalPrivacyControl',{value:true});}catch(e){}";
  }
  let place = PLACES[settings.get('fakeGeo')];
  if (settings.get('fakeGeo') === 'custom') {
    const n = settings.get('fakeGeoCustom').replace(/\s/g, '').split(',').map(Number);
    if (n.length === 2 && Math.abs(n[0]) <= 90 && Math.abs(n[1]) <= 180) place = n;
  }
  if (place) {
    js += 'try{var p=function(){return{coords:{latitude:' + place[0] + ',longitude:' + place[1]
      + ',accuracy:25,altitude:null,altitudeAccuracy:null,heading:null,speed:null},timestamp:Date.now()};};'
      + 'var g={getCurrentPosition:function(ok){setTimeout(function(){ok(p());},400);},watchPosition:function(ok){setTimeout(function(){ok(p());},400);return 1;},clearWatch:function(){}};'
      + "Object.defineProperty(Navigator.prototype,'geolocation',{get:function(){return g;},configurable:true});}catch(e){}";
  }
  return js + '})();';
}

function pageCss() {
  let css = '';
  if (settings.get('hideAdBlocks')) css += COSMETIC + '\n';
  if (settings.get('hideCookieBanners')) css += COOKIE_CSS + '\n';
  return css;
}

ipcMain.on('page-script', (e) => { e.returnValue = pageScript(); });

// ---------------------------------------------------------------- tabs

function toUi(channel, ...args) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}

function tabInfo(t) {
  const wc = t.view.webContents;
  return {
    id: t.id, title: t.title, url: t.home ? '' : wc.getURL(), loading: wc.isLoading(),
    canBack: wc.navigationHistory.canGoBack(), canForward: wc.navigationHistory.canGoForward(),
  };
}

function sendTabs() {
  toUi('tabs', tabs.map(tabInfo), current ? current.id : null);
}

function layout() {
  if (!win) return;
  const [w, h] = win.getContentSize();
  for (const t of tabs) {
    const show = t === current && !overlayOpen;
    t.view.setVisible(show);
    if (show) t.view.setBounds({ x: 0, y: topInset, width: w, height: Math.max(0, h - topInset) });
  }
}

let nextId = 1;

function newTab(url) {
  const view = new WebContentsView({
    webPreferences: {
      session: ses,
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
      nodeIntegrationInSubFrames: true,
      preload: path.join(__dirname, 'preload-page.js'),
      spellcheck: false,
      javascript: settings.get('javascript'),
      safeDialogs: true,
      backgroundThrottling: true,
    },
  });
  view.setBackgroundColor(palette().bg);
  const t = { id: nextId++, view, title: 'Новая вкладка', home: !url };
  const wc = view.webContents;
  wc.setWebRTCIPHandlingPolicy('disable_non_proxied_udp');
  wc.on('page-title-updated', (e, title) => { t.title = title || 'Без названия'; sendTabs(); });
  wc.on('did-start-loading', () => { blockedOnPage = t === current ? 0 : blockedOnPage; sendTabs(); });
  wc.on('did-stop-loading', sendTabs);
  wc.on('did-navigate', () => { t.home = wc.getURL().startsWith('maxclean://'); sendTabs(); });
  wc.on('did-navigate-in-page', sendTabs);
  wc.on('dom-ready', () => {
    const css = pageCss();
    if (css && !t.home) wc.insertCSS(css).catch(() => {});
  });
  wc.on('will-navigate', (e, target) => {
    if (target.startsWith('maxclean://search')) {
      e.preventDefault();
      const q = new URL(target).searchParams.get('q');
      if (q) navigate(t, q);
    } else if (!/^(https?|maxclean|about|data|blob):/i.test(target)) {
      e.preventDefault();
      toUi('toast', 'Внешняя ссылка заблокирована');
    }
  });
  wc.setWindowOpenHandler((d) => {
    if (settings.get('blockPopups') && d.disposition === 'new-window' && d.features) {
      toUi('toast', 'Всплывающее окно заблокировано');
      return { action: 'deny' };
    }
    const nt = newTab(d.url);
    if (d.disposition !== 'background-tab') switchTo(nt);
    return { action: 'deny' };
  });
  wc.on('before-input-event', (e, input) => handleKeys(e, input));
  win.contentView.addChildView(view);
  tabs.push(t);
  load(t, url || 'maxclean://home/');
  return t;
}

function load(t, url) {
  if (!route.ready && !url.startsWith('maxclean://')) {
    // Kill switch: nothing leaves the computer until Tor (or the proxy) is ready.
    t.pending = url;
    t.home = false;
    sendTabs();
    return;
  }
  t.view.webContents.loadURL(url).catch(() => {});
}

function navigate(t, input) {
  input = input.trim();
  if (!input) return;
  let url;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(input)) url = input;
  else if (!/\s/.test(input) && /^[\w-]+(\.[\w-]+)+(:\d+)?(\/.*)?$/.test(input)) {
    url = (input.split(/[/:]/)[0].endsWith('.onion') ? 'http://' : 'https://') + input;
  } else {
    url = engines.build(settings.get('engine'), input, settings.get('tor'), settings.get('customEngine'));
  }
  load(t, url);
}

function switchTo(t) {
  current = t;
  blockedOnPage = 0;
  layout();
  sendTabs();
}

function wipeSession() {
  return Promise.all([
    ses.clearStorageData(), ses.clearCache(), ses.clearAuthCache(), ses.clearHostResolverCache(),
  ]).catch(() => {});
}

function closeTab(t) {
  const i = tabs.indexOf(t);
  if (i < 0) return;
  tabs.splice(i, 1);
  win.contentView.removeChildView(t.view);
  t.view.webContents.close();
  wipeSession();
  toUi('toast', 'Вкладка закрыта, её данные стёрты');
  if (!tabs.length) newTab(null);
  switchTo(tabs[Math.min(i, tabs.length - 1)]);
}

function handleKeys(e, input) {
  if (input.type !== 'keyDown') return;
  const ctrl = input.control || input.meta;
  const k = input.key.toLowerCase();
  if (ctrl && k === 't') { e.preventDefault(); switchTo(newTab(null)); toUi('focus-url'); }
  else if (ctrl && k === 'w') { e.preventDefault(); if (current) closeTab(current); }
  else if (ctrl && (k === 'l' || k === 'k')) { e.preventDefault(); toUi('focus-url'); }
  else if (ctrl && k === 'tab' && tabs.length > 1) {
    e.preventDefault();
    const i = tabs.indexOf(current);
    switchTo(tabs[(i + (input.shift ? tabs.length - 1 : 1)) % tabs.length]);
  } else if (k === 'f5' || (ctrl && k === 'r')) { e.preventDefault(); if (current) current.view.webContents.reload(); }
  else if (input.alt && k === 'arrowleft') { e.preventDefault(); if (current) current.view.webContents.navigationHistory.goBack(); }
  else if (input.alt && k === 'arrowright') { e.preventDefault(); if (current) current.view.webContents.navigationHistory.goForward(); }
}

// ---------------------------------------------------------------- Tor and proxies

function setRoute(r) {
  route = Object.assign(route, r);
  toUi('route', route, settings.get('tor'));
}

async function applyNetwork() {
  netSig = settings.networkSignature();
  tor.stop();
  if (chainIn) chainIn.stop();
  if (chainOut) chainOut.stop();
  chainIn = chainOut = null;
  setRoute({ ready: false, failed: false, progress: 0, text: '' });
  await ses.setProxy({ proxyRules: BLACKHOLE });
  await wipeSession(); // a new network identity: nothing carries over

  const before = parseChain(settings.get('proxyChain'));
  const after = parseHop(settings.get('proxyAfter'));

  if (!settings.get('tor')) {
    const hops = before.concat(after ? [after] : []);
    if (!hops.length) {
      await ses.setProxy({ mode: 'direct' });
      return routeReady('Tor выключен — прямое соединение');
    }
    try {
      chainOut = new ProxyChain(hops);
      const port = await chainOut.start();
      await ses.setProxy({ proxyRules: 'socks5://127.0.0.1:' + port, proxyBypassRules: '<-loopback>' });
      return routeReady('Tor выключен — через ' + (hops.length === 1 ? 'ваш прокси' : hops.length + ' прокси подряд'));
    } catch (e) {
      return setRoute({ failed: true, text: 'Прокси не запустился: ' + e.message });
    }
  }

  let bridges = [];
  const mode = settings.get('bridges');
  if (mode === 'custom') {
    bridges = settings.get('bridgeLines').split(/\r?\n/).map(s => s.trim().replace(/^Bridge\s+/, ''))
      .filter(s => s && !s.startsWith('#'));
    if (!bridges.length) return setRoute({ failed: true, text: 'Вы выбрали свои мосты, но не вставили ни одного' });
  } else if (mode !== 'none') {
    bridges = tor.builtInBridges(mode);
  }
  let upstream = '';
  if (before.length) {
    chainIn = new ProxyChain(before);
    upstream = '127.0.0.1:' + await chainIn.start();
  }
  const how = (bridges.length ? ' через мосты (' + mode + ')' : '') + (before.length ? ' через ' + before.length + ' прокси' : '');
  setRoute({ text: 'Подключаюсь к сети Tor' + how + '…' });
  const sig = netSig;
  tor.removeAllListeners();
  let lastMove = Date.now();
  const slow = setInterval(() => {
    if (sig !== netSig || route.ready || route.failed) return clearInterval(slow);
    if (Date.now() - lastMove > 45000) {
      clearInterval(slow);
      setRoute({ text: bridges.length
        ? 'Tor подключается дольше обычного. Попробуйте другие мосты: ⋮ → Настройки → Tor'
        : 'Похоже, ваша сеть блокирует Tor. Включите мосты Snowflake: ⋮ → Настройки → Tor' });
    }
  }, 5000);
  tor.on('progress', (p) => { if (sig === netSig) { lastMove = Date.now(); setRoute({ progress: p }); } });
  tor.on('failed', (msg) => { if (sig === netSig) setRoute({ failed: true, ready: false, text: msg }); });
  tor.on('ready', async (port) => {
    if (sig !== netSig) return;
    let rule = 'socks5://127.0.0.1:' + port;
    if (after) {
      chainOut = new ProxyChain([parseHop('socks5://127.0.0.1:' + port), after]);
      rule = 'socks5://127.0.0.1:' + await chainOut.start();
    }
    // socks5: host names go to Tor, so DNS goes through Tor and .onion works.
    await ses.setProxy({ proxyRules: rule, proxyBypassRules: '<-loopback>' });
    routeReady('Tor подключён: провайдер не видит, куда вы ходите');
  });
  tor.start(bridges, upstream).catch(e => setRoute({ failed: true, text: e.message }));
}

function routeReady(text) {
  setRoute({ ready: true, failed: false, progress: 100, text });
  for (const t of tabs) {
    if (t.pending) {
      const u = t.pending;
      t.pending = null;
      t.view.webContents.loadURL(u).catch(() => {});
    } else if (t.home) {
      t.view.webContents.reload();
    }
  }
}

// ---------------------------------------------------------------- interface messages

ipcMain.on('ui-ready', () => { sendTabs(); toUi('route', route, settings.get('tor')); toUi('settings', settings.all(), palette()); });
ipcMain.on('layout', (e, top) => { topInset = Math.round(top); layout(); });
ipcMain.on('navigate', (e, text) => { if (current) navigate(current, text); });
ipcMain.on('new-tab', () => switchTo(newTab(null)));
ipcMain.on('switch-tab', (e, id) => { const t = tabs.find(x => x.id === id); if (t) switchTo(t); });
ipcMain.on('close-tab', (e, id) => { const t = tabs.find(x => x.id === id); if (t) closeTab(t); });
ipcMain.on('back', () => current && current.view.webContents.navigationHistory.goBack());
ipcMain.on('forward', () => current && current.view.webContents.navigationHistory.goForward());
ipcMain.on('reload', () => current && current.view.webContents.reload());
ipcMain.on('home', () => current && load(current, 'maxclean://home/'));
ipcMain.on('open-url', (e, url) => switchTo(newTab(url)));
ipcMain.on('toggle-tor', () => { settings.set({ tor: !settings.get('tor') }); toUi('settings', settings.all(), palette()); applyNetwork(); });
ipcMain.on('retry', () => applyNetwork());
ipcMain.on('wipe-exit', async () => { for (const t of tabs) t.view.webContents.close(); await wipeSession(); app.quit(); });
ipcMain.handle('overlay', async (e, open) => {
  // While a panel is open the page view is hidden; the interface shows its picture, blurred.
  let shot = null;
  if (open && current) {
    try { shot = (await current.view.webContents.capturePage()).resize({ width: 960 }).toDataURL(); } catch (err) { shot = null; }
  }
  overlayOpen = open;
  layout();
  return shot;
});
ipcMain.on('set-settings', (e, changes) => {
  settings.set(changes);
  blocker.refresh(settings.all());
  toUi('settings', settings.all(), palette());
  if (settings.networkSignature() !== netSig) applyNetwork();
  for (const t of tabs) t.view.webContents.setBackgroundThrottling(true);
});
ipcMain.on('window', (e, what) => {
  if (what === 'min') win.minimize();
  else if (what === 'max') (win.isMaximized() ? win.unmaximize() : win.maximize());
  else if (what === 'close') win.close();
});
ipcMain.on('open-external-github', () => shell.openExternal('https://github.com/maxclean557-afk/Maxclean'));

// ---------------------------------------------------------------- app

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

app.whenReady().then(() => {
  setupSession();
  blocker.refresh(settings.all());
  const p = palette();
  win = new BrowserWindow({
    width: 1360, height: 860, minWidth: 720, minHeight: 480,
    title: 'Maxclean',
    backgroundColor: p.bg,
    icon: path.join(__dirname, 'ui', 'icon.png'),
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#00000000', symbolColor: p.text, height: 44 },
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload-ui.js'),
      sandbox: true,
      contextIsolation: true,
      spellcheck: false,
    },
  });
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  win.once('ready-to-show', () => win.show());
  win.on('resize', layout);
  win.webContents.on('before-input-event', (e, input) => handleKeys(e, input));
  switchTo(newTab(null));
  applyNetwork();
  if (process.env.MAXCLEAN_SELFTEST) selfTest(process.env.MAXCLEAN_SELFTEST);
});

// Developer check, only when MAXCLEAN_SELFTEST=<folder> is set: waits for Tor, opens
// check.torproject.org, saves pictures of the interface and the page, prints the verdict, quits.
function selfTest(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const log = (...a) => console.log('[selftest]', ...a);
  const shoot = async (name) => {
    try {
      fs.writeFileSync(path.join(dir, name + '-ui.png'), (await win.webContents.capturePage()).toPNG());
      if (current) fs.writeFileSync(path.join(dir, name + '-page.png'), (await current.view.webContents.capturePage()).toPNG());
    } catch (e) { log('shot failed', e.message); }
  };
  const started = Date.now();
  setTimeout(() => shoot('1-start'), 4000);
  const timer = setInterval(async () => {
    log('route', JSON.stringify(route));
    if (route.failed || Date.now() - started > 240000) {
      clearInterval(timer);
      await shoot('9-failed');
      app.quit();
    } else if (route.ready) {
      clearInterval(timer);
      await new Promise(r => setTimeout(r, 2500));
      await shoot('2-home');
      const wc = current.view.webContents;
      const onLoad = async () => {
        if (!wc.getURL().startsWith('https://check.torproject.org')) return;
        wc.removeListener('did-finish-load', onLoad);
        await new Promise(r => setTimeout(r, 1500));
        const text = await wc.executeJavaScript('document.body.innerText.slice(0, 300)').catch(e => 'ERR ' + e.message);
        log('check.torproject.org says:', JSON.stringify(text));
        await shoot('3-check');
        app.quit();
      };
      wc.on('did-finish-load', onLoad);
      load(current, 'https://check.torproject.org/');
    }
  }, 3000);
}

app.on('window-all-closed', () => app.quit());
app.on('will-quit', () => {
  tor.stop();
  if (chainIn) chainIn.stop();
  if (chainOut) chainOut.stop();
});
app.on('quit', () => {
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* files still held */ }
  // Windows keeps some files open until the last process exits: a small helper removes the
  // whole session folder a few seconds after Maxclean has closed.
  if (fs.existsSync(PROFILE)) {
    const code = "const fs=require('fs');let n=0;const t=setInterval(()=>{try{" +
      "fs.rmSync(process.argv[1],{recursive:true,force:true});try{fs.rmdirSync(process.argv[2])}catch(e){}" +
      "clearInterval(t)}catch(e){if(++n>30)clearInterval(t)}},1000)";
    try {
      require('child_process').spawn(process.execPath, ['-e', code, PROFILE, PROFILE_ROOT],
        { detached: true, stdio: 'ignore', windowsHide: true, env: { ELECTRON_RUN_AS_NODE: '1' } }).unref();
    } catch (e) { /* removed at the next start */ }
  }
});

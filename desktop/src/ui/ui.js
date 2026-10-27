'use strict';
// The browser's own interface: tabs, address bar, Tor status, menu, settings and intro.
// It talks to the main process only through window.mc (preload-ui.js).

const $ = (id) => document.getElementById(id);
let tabs = [];
let currentId = null;
let settings = {};
let route = { ready: false, text: '', failed: false, progress: 0 };
let torOn = true;
let stripTimer = null;

const ENGINES = ['Maxclean (анонимный)', 'DuckDuckGo', 'Google', 'Яндекс', 'Bing', 'Yahoo', 'Brave Search',
  'Startpage', 'Ecosia', 'Qwant', 'Mojeek', 'Mail.ru', 'Baidu', 'Свой (из настроек)'];
const SHORT = ['MC', 'DDG', 'G', 'Я', 'B', 'Y!', 'Br', 'SP', 'Ec', 'Qw', 'Mj', '@', '百', '★'];
const ACCENTS = { mint: ['Мята', '#2BD4A4'], violet: ['Фиалка', '#9B6BFF'], ocean: ['Океан', '#3D8BFF'], amber: ['Янтарь', '#FF9F1C'], rose: ['Роза', '#FF5C8A'] };

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------------------------------------------------------------- tabs and toolbar

function renderTabs() {
  const box = $('tabs');
  const ids = new Set(tabs.map(t => t.id));
  for (const el of [...box.children]) if (!ids.has(Number(el.dataset.id))) el.remove();
  tabs.forEach((t, i) => {
    let el = box.querySelector('[data-id="' + t.id + '"]');
    if (!el) {
      el = document.createElement('div');
      el.className = 'tab';
      el.dataset.id = t.id;
      el.innerHTML = '<span class="t"></span><button class="x" title="Закрыть и стереть (Ctrl+W)">×</button>';
      el.addEventListener('mousedown', (e) => {
        if (e.button === 1) { window.mc.closeTab(t.id); e.preventDefault(); }
        else if (!e.target.classList.contains('x')) window.mc.switchTab(t.id);
      });
      el.querySelector('.x').addEventListener('click', () => window.mc.closeTab(t.id));
    }
    if (box.children[i] !== el) box.insertBefore(el, box.children[i] || null);
    el.classList.toggle('active', t.id === currentId);
    el.querySelector('.t').textContent = t.title;
    el.title = t.title;
    let spin = el.querySelector('.spin');
    if (t.loading && !spin) { spin = document.createElement('span'); spin.className = 'spin'; el.prepend(spin); }
    if (!t.loading && spin) spin.remove();
  });
  const cur = tabs.find(t => t.id === currentId);
  if (cur) {
    if (document.activeElement !== $('url')) $('url').value = cur.url;
    $('back').disabled = !cur.canBack;
    $('forward').disabled = !cur.canForward;
  }
}

function renderRoute() {
  const pill = $('tor');
  pill.classList.toggle('on', torOn && route.ready);
  pill.classList.toggle('bad', route.failed);
  pill.classList.toggle('connecting', torOn && !route.ready && !route.failed);
  $('tortext').textContent = !torOn ? (route.ready ? 'Без Tor' : 'Прокси…') : route.failed ? 'Ошибка' : route.ready ? 'Tor' : (route.progress || 0) + '%';
  $('ringfg').style.strokeDashoffset = String(94.25 * (1 - (torOn ? (route.ready ? 1 : (route.progress || 0) / 100) : 0)));
  pill.title = route.text || 'Tor';
  if (route.failed) showStrip(route.text, true, 'Повторить', () => window.mc.retry());
  else if (!route.ready && route.text) showStrip(route.text + (route.progress ? ' ' + route.progress + '%' : ''), false);
  else if (route.ready && route.text) showStrip(route.text, false, null, null, 2500);
}

function showStrip(text, bad, action, fn, hideAfter) {
  const s = $('strip');
  s.innerHTML = '<span>' + esc(text) + '</span>' + (action ? '<button>' + esc(action) + '</button>' : '');
  if (action) s.querySelector('button').onclick = fn;
  s.classList.toggle('bad', !!bad);
  s.classList.add('show');
  clearTimeout(stripTimer);
  if (hideAfter) stripTimer = setTimeout(() => { s.classList.remove('show'); reportLayout(); }, hideAfter);
  reportLayout();
}

function reportLayout() {
  // The page view sits right under the interface; wait for the strip's slide to finish.
  const send = () => window.mc.layout($('chrome').getBoundingClientRect().height);
  send();
  setTimeout(send, 280);
}

function applyLook() {
  const light = settings.theme === 'light' || (settings.theme === 'system' && matchMedia('(prefers-color-scheme: light)').matches);
  document.documentElement.classList.toggle('light', light);
  const a = (ACCENTS[settings.accent] || ACCENTS.mint)[1];
  document.documentElement.style.setProperty('--accent', a);
  document.body.classList.toggle('noanim', !settings.animations);
  $('engine').textContent = SHORT[settings.engine] || 'MC';
}

// ---------------------------------------------------------------- overlays

let closeOverlayFn = null;

async function openOverlay(build, cls) {
  const shot = await window.mc.overlay(true);
  const ov = $('overlay');
  $('backdrop').style.backgroundImage = shot ? 'url(' + shot + ')' : 'none';
  const panel = $('panel');
  panel.className = 'panel ' + (cls || '');
  panel.innerHTML = '';
  build(panel);
  ov.hidden = false;
  closeOverlayFn = closeOverlay;
}

function closeOverlay() {
  $('overlay').hidden = true;
  closeOverlayFn = null;
  window.mc.overlay(false);
}

$('backdrop').addEventListener('click', closeOverlay);
$('overlay').addEventListener('click', (e) => { if (e.target === $('overlay')) closeOverlay(); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && closeOverlayFn) closeOverlayFn();
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 't') window.mc.newTab();
});

function el(tag, attrs, html) {
  const e = document.createElement(tag);
  Object.assign(e, attrs || {});
  if (html !== undefined) e.innerHTML = html;
  return e;
}

function showMenu() {
  openOverlay((p) => {
    const bigs = el('div', { className: 'bigs' });
    const items = [
      ['＋', 'Вкладка', () => window.mc.newTab(), false],
      ['🧅', torOn ? 'Tor вкл' : 'Tor выкл', () => window.mc.toggleTor(), torOn],
      ['🔎', 'Поиск', () => setTimeout(showEngines, 50), false],
      ['🔥', 'Стереть', () => window.mc.wipeExit(), false],
    ];
    for (const [g, label, fn, active] of items) {
      const b = el('button', { className: 'big' + (active ? ' active' : '') }, '<span>' + g + '</span>' + label);
      b.onclick = () => { closeOverlay(); fn(); };
      bigs.append(b);
    }
    p.append(bigs);
    p.append(el('div', { style: 'height:10px' }));
    const rows = [
      ['🔍', 'Проверка утечек', showLeakTests],
      ['⚙', 'Настройки', showSettings],
      ['⌨', 'Горячие клавиши', showKeys],
      ['</>', 'Открытый код на GitHub', () => window.mc.github()],
    ];
    for (const [g, label, fn] of rows) {
      const r = el('button', { className: 'row' }, '<span>' + g + '</span>' + label);
      r.onclick = () => { closeOverlay(); setTimeout(fn, 60); };
      p.append(r);
    }
    p.append(el('div', { className: 'foot' }, 'Maxclean 1.3 Full для Windows · ничего не сохраняется'));
  });
}

function showEngines() {
  openOverlay((p) => {
    p.append(el('h2', {}, 'Искать через'));
    const list = el('div', { className: 'list' });
    ENGINES.forEach((name, i) => {
      const r = el('button', { className: 'row' + (settings.engine === i ? ' on' : '') }, esc(name) + '<small>' + SHORT[i] + '</small>');
      r.onclick = () => { window.mc.setSettings({ engine: i }); closeOverlay(); };
      list.append(r);
    });
    p.append(list);
  });
}

function showLeakTests() {
  openOverlay((p) => {
    p.append(el('h2', {}, 'Проверка утечек'));
    p.append(el('p', { className: 'hint' }, 'Независимые сайты покажут, что видят сайты: ваш IP, DNS-серверы и отпечаток. Через Tor там будет не ваш адрес.'));
    for (const [n, u] of [['Tor и IP-адрес', 'https://check.torproject.org/'], ['DNS-утечки', 'https://www.dnsleaktest.com/'],
      ['IP, WebRTC и DNS вместе', 'https://browserleaks.com/ip'], ['Отпечаток браузера', 'https://coveryourtracks.eff.org/']]) {
      const r = el('button', { className: 'row' }, esc(n) + '<small>↗</small>');
      r.onclick = () => { closeOverlay(); window.mc.openUrl(u); };
      p.append(r);
    }
  });
}

function showKeys() {
  openOverlay((p) => {
    p.append(el('h2', {}, 'Горячие клавиши'));
    for (const [k, d] of [['Ctrl+T', 'Новая вкладка'], ['Ctrl+W', 'Закрыть вкладку и стереть её данные'], ['Ctrl+L', 'К адресной строке'],
      ['Ctrl+Tab', 'Следующая вкладка'], ['F5', 'Обновить'], ['Alt+← / →', 'Назад / вперёд'], ['Esc', 'Закрыть панель']]) {
      p.append(el('div', { className: 'row' }, '<b>' + k + '</b><small>' + d + '</small>'));
    }
  });
}

// ---------------------------------------------------------------- settings

function toggle(p, key, label, hint) {
  const row = el('div', { className: 'setting' }, '<div class="txt"><b>' + esc(label) + '</b><i>' + esc(hint) + '</i></div><span class="switch"></span>');
  const sw = row.querySelector('.switch');
  sw.classList.toggle('on', !!settings[key]);
  row.onclick = () => { settings[key] = !settings[key]; sw.classList.toggle('on', settings[key]); window.mc.setSettings({ [key]: settings[key] }); };
  p.append(row);
}

function chips(p, key, options) {
  const box = el('div', { className: 'choice' });
  for (const [value, label] of options) {
    const c = el('button', { className: 'chip' + (settings[key] === value ? ' on' : '') }, label);
    c.onclick = () => {
      settings[key] = value;
      box.querySelectorAll('.chip').forEach(x => x.classList.remove('on'));
      c.classList.add('on');
      window.mc.setSettings({ [key]: value });
    };
    box.append(c);
  }
  p.append(box);
}

function textField(p, key, multi, placeholder) {
  const t = el(multi ? 'textarea' : 'input', { rows: multi ? 3 : undefined, placeholder: placeholder || '' });
  if (!multi) t.type = 'text';
  t.value = settings[key] || '';
  t.onchange = () => { settings[key] = t.value; window.mc.setSettings({ [key]: t.value }); };
  p.append(t);
}

function showSettings() {
  openOverlay((p) => {
    p.append(el('h2', {}, 'Настройки'));
    p.append(el('h3', {}, '🎨 Оформление'));
    chips(p, 'theme', [['dark', 'Тёмная'], ['light', 'Светлая'], ['system', 'Как в Windows']]);
    chips(p, 'accent', Object.entries(ACCENTS).map(([k, [n, c]]) => [k, '<span class="dotc" style="background:' + c + '"></span>' + n]));
    toggle(p, 'animations', 'Анимации', 'Плавные вкладки, панели и стартовая страница');

    p.append(el('h3', {}, '🧅 Tor'));
    toggle(p, 'tor', 'Шифровать трафик через Tor', 'Пока Tor подключается, ничего не грузится — защита от утечки');
    p.append(el('p', { className: 'hint' }, 'Мосты — если Tor заблокирован в вашей стране или сети.'));
    chips(p, 'bridges', [['none', 'Без мостов'], ['snowflake', 'Snowflake'], ['obfs4', 'obfs4'], ['custom', 'Свои']]);
    textField(p, 'bridgeLines', true, 'obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0 — с bridges.torproject.org или @GetBridgesBot');

    p.append(el('h3', {}, '🔀 Прокси: xray, sing-box, v2rayN'));
    p.append(el('p', { className: 'hint' }, 'Цепочка перед Tor, по одному в строке (до 5). Без Tor через неё идёт браузер. Первый прокси видит ваш настоящий IP: анонимность даёт Tor, прокси обходят блокировки.'));
    textField(p, 'proxyChain', true, 'socks5://127.0.0.1:10808\nhttp://логин:пароль@1.2.3.4:8080');
    p.append(el('p', { className: 'hint' }, 'Прокси после Tor — для сайтов, которые не пускают Tor. Он видит, что вы открываете: анонимности меньше.'));
    textField(p, 'proxyAfter', false, 'socks5://адрес:порт');

    p.append(el('h3', {}, '🛡 Блокировщик'));
    toggle(p, 'blockAds', 'Рекламные сети', 'Google Ads, Яндекс.Директ, Criteo, Taboola…');
    toggle(p, 'blockTrackers', 'Трекеры и аналитика', 'Google Analytics, Метрика, Hotjar…');
    toggle(p, 'blockSocial', 'Пиксели соцсетей', 'Facebook, VK, TikTok, Twitter');
    toggle(p, 'blockMiners', 'Крипто-майнеры', 'Скрипты, которые майнят на вашем компьютере');
    toggle(p, 'blockPopups', 'Всплывающие окна', 'Окна, открытые скриптом');
    toggle(p, 'hideAdBlocks', 'Прятать пустые рекламные блоки', 'Без дыр на странице');
    toggle(p, 'hideCookieBanners', 'Прятать баннеры про cookies', 'Сайт считает, что вы не согласились');

    p.append(el('h3', {}, '🔒 Приватность'));
    toggle(p, 'fingerprint', 'Защита от отпечатка', 'Общие параметры железа, шум в canvas, скрытый видеочип');
    p.append(el('p', { className: 'hint' }, 'Профиль компьютера: все значения согласованы между собой.'));
    chips(p, 'deviceProfile', [['generic', 'Обычный'], ['windows', 'ПК 1920×1080'], ['laptop', 'Ноутбук 1536×864']]);
    toggle(p, 'disableWebrtc', 'Отключить WebRTC', 'Иначе сайт может узнать ваш IP даже через Tor');
    toggle(p, 'httpsOnly', 'Только HTTPS', 'http:// открывается как https://, незашифрованное блокируется');
    toggle(p, 'gpc', 'Сигнал «Не отслеживать» (GPC)', 'Законный запрет продавать ваши данные');
    toggle(p, 'javascript', 'JavaScript', 'Применится к новым вкладкам');
    p.append(el('p', { className: 'hint' }, 'Местоположение для сайтов, которые его спрашивают:'));
    chips(p, 'fakeGeo', [['off', 'Не сообщать'], ['moscow', 'Москва'], ['amsterdam', 'Амстердам'], ['berlin', 'Берлин'], ['newyork', 'Нью-Йорк'], ['tokyo', 'Токио'], ['custom', 'Свои']]);
    textField(p, 'fakeGeoCustom', false, 'широта, долгота — например 41.0082, 28.9784');

    p.append(el('h3', {}, '⭐ Стартовая страница'));
    p.append(el('p', { className: 'hint' }, 'Плитки: «Название | адрес», по одному в строке, до 8.'));
    textField(p, 'quickLinks', true, 'Новости | https://example.com');
    p.append(el('h3', {}, '🚫 Свой список блокировки'));
    textField(p, 'userBlocklist', true, 'example-ads.com — по одному домену в строке');
    p.append(el('h3', {}, '🔎 Свой поисковик'));
    textField(p, 'customEngine', false, 'https://searx.be/search?q=%s');
    p.append(el('div', { className: 'foot' }, 'Maxclean не имеет аккаунтов и не отправляет статистику. Это не VPN: другие программы на компьютере ходят в сеть напрямую. <a id="gh">Код на GitHub</a>'));
    p.querySelector('#gh').onclick = () => window.mc.github();
  }, 'wide');
}

// ---------------------------------------------------------------- intro

function showIntro() {
  const pages = [
    ['🧅', 'Tor внутри', 'Трафик идёт через три случайных узла сети Tor. Провайдер не видит, какие сайты вы открываете, а сайты не видят ваш адрес.'],
    ['🧹', 'Ничего не остаётся', 'Всё живёт в памяти. Закрыли вкладку — cookies, кэш и данные стёрты. Без аккаунтов и статистики.'],
    ['🛡', 'Без рекламы и слежки', 'Реклама, трекеры и майнеры блокируются ещё до загрузки. Всё настраивается в меню ⋮ → Настройки.'],
  ];
  let i = 0;
  openOverlay((p) => {
    const draw = () => {
      p.innerHTML = '';
      p.classList.add('intro');
      p.append(el('div', { className: 'intro-glyph' }, pages[i][0]));
      p.append(el('h2', {}, pages[i][1]));
      p.append(el('p', {}, pages[i][2]));
      p.append(el('div', { className: 'dots' }, pages.map((x, k) => '<i class="' + (k === i ? 'on' : '') + '"></i>').join('')));
      const b = el('button', { className: 'primary' }, i === pages.length - 1 ? 'Начать' : 'Далее');
      b.onclick = () => {
        if (i < pages.length - 1) { i++; draw(); return; }
        window.mc.setSettings({ onboarded: true });
        closeOverlay();
      };
      p.append(b);
    };
    draw();
  }, 'center');
}

// ---------------------------------------------------------------- wiring

$('newtab').onclick = () => window.mc.newTab();
$('back').onclick = () => window.mc.back();
$('forward').onclick = () => window.mc.forward();
$('reload').onclick = () => window.mc.reload();
$('home').onclick = () => window.mc.home();
$('menu').onclick = showMenu;
$('engine').onclick = (e) => { e.preventDefault(); showEngines(); };
$('tor').onclick = () => (route.failed ? window.mc.retry() : window.mc.toggleTor());
$('url').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { window.mc.navigate($('url').value); $('url').blur(); }
});
$('url').addEventListener('focus', () => setTimeout(() => $('url').select(), 0));

window.mc.on('tabs', (list, cur) => { tabs = list; currentId = cur; renderTabs(); });
window.mc.on('route', (r, on) => { route = r; torOn = on; renderRoute(); });
window.mc.on('blocked', (n) => { const b = $('blocked'); b.textContent = n; b.classList.toggle('has', n > 0); });
window.mc.on('toast', (text) => showStrip(text, false, null, null, 2600));
window.mc.on('focus-url', () => { $('url').focus(); });
let firstSettings = true;
window.mc.on('settings', (s) => {
  settings = s;
  torOn = s.tor;
  applyLook();
  renderRoute();
  if (firstSettings) {
    firstSettings = false;
    if (!s.onboarded) setTimeout(showIntro, 600);
  }
});
new ResizeObserver(reportLayout).observe($('chrome'));
window.mc.ready();
reportLayout();

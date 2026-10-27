'use strict';
// Search engines for the menu next to the address bar ("%s" is the query). Same list as Android.

const LIST = [
  ['Maxclean (анонимный)', null, 'MC'],
  ['DuckDuckGo', 'https://duckduckgo.com/?q=%s', 'DDG'],
  ['Google', 'https://www.google.com/search?q=%s', 'G'],
  ['Яндекс', 'https://yandex.ru/search/?text=%s', 'Я'],
  ['Bing', 'https://www.bing.com/search?q=%s', 'B'],
  ['Yahoo', 'https://search.yahoo.com/search?p=%s', 'Y!'],
  ['Brave Search', 'https://search.brave.com/search?q=%s', 'Br'],
  ['Startpage', 'https://www.startpage.com/do/search?q=%s', 'SP'],
  ['Ecosia', 'https://www.ecosia.org/search?q=%s', 'Ec'],
  ['Qwant', 'https://www.qwant.com/?q=%s', 'Qw'],
  ['Mojeek', 'https://www.mojeek.com/search?q=%s', 'Mj'],
  ['Mail.ru', 'https://go.mail.ru/search?q=%s', '@'],
  ['Baidu', 'https://www.baidu.com/s?wd=%s', '百'],
  ['Свой (из настроек)', null, '★'],
];

// DuckDuckGo without JavaScript; through Tor its official .onion address.
function maxclean(tor) {
  return tor
    ? 'https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=%s'
    : 'https://html.duckduckgo.com/html/?q=%s';
}

function build(engine, query, tor, custom) {
  let tpl;
  if (engine === LIST.length - 1) tpl = custom && custom.includes('%s') ? custom : maxclean(tor);
  else tpl = (LIST[engine] && LIST[engine][1]) || maxclean(tor);
  return tpl.replace('%s', encodeURIComponent(query));
}

module.exports = { LIST, build };

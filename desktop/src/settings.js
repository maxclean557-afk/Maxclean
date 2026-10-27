'use strict';
// User settings only. Nothing about browsing (history, cookies, sites) is ever stored here.
// The file lives in %APPDATA%\Maxclean\settings.json, apart from the throwaway browsing profile.

const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  tor: true,
  bridges: 'none',            // none | snowflake | obfs4 | custom
  bridgeLines: '',
  proxyChain: '',             // proxies in front of Tor, one per line, up to 5
  proxyAfter: '',             // one proxy after Tor
  blockAds: true,
  blockTrackers: true,
  blockSocial: true,
  blockMiners: true,
  blockPopups: true,
  hideAdBlocks: true,
  hideCookieBanners: true,
  javascript: true,
  cookies: true,
  thirdPartyCookies: false,
  fingerprint: true,
  deviceProfile: 'generic',   // generic | windows | mac
  fakeGeo: 'off',
  fakeGeoCustom: '',
  httpsOnly: true,
  gpc: true,
  disableWebrtc: true,
  engine: 0,
  customEngine: '',
  theme: 'dark',              // dark | light | system
  accent: 'mint',
  animations: true,
  quickLinks: 'DuckDuckGo | https://duckduckgo.com\nВикипедия | https://ru.wikipedia.org\nПроверка Tor | https://check.torproject.org\nMaxclean | https://github.com/maxclean557-afk/Maxclean',
  userBlocklist: '',
  onboarded: false,
};

class Settings {
  constructor(dir) {
    this.file = path.join(dir, 'settings.json');
    fs.mkdirSync(dir, { recursive: true });
    let saved = {};
    try {
      saved = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (e) {
      // First start or a broken file: defaults.
    }
    this.values = Object.assign({}, DEFAULTS, saved);
  }

  get(key) {
    return this.values[key];
  }

  all() {
    return Object.assign({}, this.values);
  }

  set(changes) {
    for (const k of Object.keys(changes)) {
      if (k in DEFAULTS && typeof changes[k] === typeof DEFAULTS[k]) this.values[k] = changes[k];
    }
    fs.writeFileSync(this.file, JSON.stringify(this.values, null, 2));
  }

  /** Everything that decides how traffic leaves the computer. */
  networkSignature() {
    const v = this.values;
    return [v.tor, v.bridges, v.bridgeLines, v.proxyChain, v.proxyAfter].join('|');
  }
}

module.exports = { Settings, DEFAULTS };

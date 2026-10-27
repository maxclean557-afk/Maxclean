'use strict';
// Blocks requests to ad, tracker, social and miner domains (same lists as the Android app),
// the user's own block list, and plain-HTTP pieces of pages in HTTPS-only mode.

const fs = require('fs');
const path = require('path');

const CATEGORIES = [
  ['blockAds', 'ads.txt'],
  ['blockTrackers', 'trackers.txt'],
  ['blockSocial', 'social.txt'],
  ['blockMiners', 'miners.txt'],
];

function load(file) {
  const hosts = new Set();
  const paths = [];
  for (let line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    line = line.trim().toLowerCase();
    if (!line || line.startsWith('#')) continue;
    const slash = line.indexOf('/');
    if (slash > 0) paths.push([line.slice(0, slash), line.slice(slash)]);
    else hosts.add(line);
  }
  return { hosts, paths };
}

function matchesHost(set, host) {
  let h = host;
  for (;;) {
    if (set.has(h)) return true;
    const dot = h.indexOf('.');
    if (dot < 0 || dot === h.length - 1) return false;
    h = h.slice(dot + 1);
  }
}

class Blocker {
  constructor(filterDir) {
    this.lists = CATEGORIES.map(([key, file]) => ({ key, ...load(path.join(filterDir, file)) }));
    this.user = new Set();
    this.settings = {};
    this.blocked = 0;
  }

  refresh(settings) {
    this.settings = settings;
    this.user = new Set(settings.userBlocklist.split(/\r?\n/).map(s => s.trim().toLowerCase())
      .filter(s => s && !s.startsWith('#')));
  }

  /** 'block', 'upgrade' (to https) or null. */
  decide(url, isMainFrame) {
    let u;
    try {
      u = new URL(url);
    } catch (e) {
      return null;
    }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    const host = u.hostname.toLowerCase();
    if (u.protocol === 'http:' && this.settings.httpsOnly && !host.endsWith('.onion')
        && host !== '127.0.0.1' && host !== 'localhost') {
      return isMainFrame ? 'upgrade' : 'block';
    }
    if (matchesHost(this.user, host)) return 'block';
    const p = u.pathname.toLowerCase();
    for (const list of this.lists) {
      if (!this.settings[list.key]) continue;
      if (matchesHost(list.hosts, host)) return 'block';
      for (const [h, prefix] of list.paths) {
        if ((host === h || host.endsWith('.' + h)) && p.startsWith(prefix)) return 'block';
      }
    }
    return null;
  }
}

module.exports = { Blocker };

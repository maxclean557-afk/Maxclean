'use strict';
// Runs the official tor.exe (Tor Expert Bundle, signature-checked at build time) and reports
// its progress. Bridges use lyrebird.exe from the same bundle (obfs4, Snowflake, WebTunnel…).
// Tor's own folder keeps only the public list of relays, never anything about browsing.

const { spawn } = require('child_process');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { EventEmitter } = require('events');

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

/** A torrc string value: forward slashes and quotes, so spaces in paths are safe. */
function q(p) {
  return '"' + p.replace(/\\/g, '/').replace(/"/g, '\\"') + '"';
}

class Tor extends EventEmitter {
  /**
   * @param binDir  folder with tor.exe, lyrebird.exe, geoip files and pt_config.json
   * @param dataDir Tor's own folder (relay list cache)
   */
  constructor(binDir, dataDir) {
    super();
    this.binDir = binDir;
    this.dataDir = dataDir;
    this.proc = null;
    this.port = 0;
    this.progress = 0;
  }

  /** Built-in bridges published by the Tor Project, from pt_config.json. */
  builtInBridges(kind) {
    try {
      const cfg = JSON.parse(fs.readFileSync(path.join(this.binDir, 'pt_config.json'), 'utf8'));
      return (cfg.bridges && cfg.bridges[kind]) || [];
    } catch (e) {
      return [];
    }
  }

  /**
   * Starts Tor. `bridges`: bridge lines (may be empty). `upstream`: "host:port" of a local
   * SOCKS5 proxy Tor must go through (our proxy chain), or "".
   * Emits 'progress' (percent, summary), 'ready' (socks port), 'failed' (message).
   */
  async start(bridges, upstream) {
    this.stop();
    fs.mkdirSync(this.dataDir, { recursive: true });
    this.port = await freePort();
    this.progress = 0;
    const lines = [
      'DataDirectory ' + q(this.dataDir),
      'GeoIPFile ' + q(path.join(this.binDir, 'geoip')),
      'GeoIPv6File ' + q(path.join(this.binDir, 'geoip6')),
      'SocksPort 127.0.0.1:' + this.port,
      'ClientOnly 1',
      'AvoidDiskWrites 1',
      'Log notice stdout',
      '__OwningControllerProcess ' + process.pid,
    ];
    if (bridges.length) {
      lines.push('UseBridges 1');
      // Relative to Tor's working folder, so spaces in the install path never matter.
      lines.push('ClientTransportPlugin meek_lite,obfs2,obfs3,obfs4,scramblesuit,webtunnel exec lyrebird.exe');
      lines.push('ClientTransportPlugin snowflake exec lyrebird.exe');
      for (const b of bridges) lines.push('Bridge ' + b);
    }
    // Tor hands this proxy to the bridge transports too.
    if (upstream) lines.push('Socks5Proxy ' + upstream);
    const torrc = path.join(this.dataDir, 'torrc');
    fs.writeFileSync(torrc, lines.join('\n') + '\n');
    const defaults = path.join(this.dataDir, 'torrc-defaults');
    fs.writeFileSync(defaults, '');

    const proc = spawn(path.join(this.binDir, 'tor.exe'), ['-f', torrc, '--defaults-torrc', defaults], {
      cwd: this.binDir,
      windowsHide: true,
    });
    this.proc = proc;
    let tail = '';
    proc.stdout.on('data', d => {
      if (proc !== this.proc) return;
      tail = (tail + d.toString()).slice(-4000);
      const re = /Bootstrapped (\d+)%[^:]*: ([^\r\n]*)/g;
      let m;
      while ((m = re.exec(d.toString())) !== null) {
        this.progress = Number(m[1]);
        this.emit('progress', this.progress, m[2]);
        if (this.progress === 100) this.emit('ready', this.port);
      }
    });
    proc.on('error', e => this.emit('failed', 'Не удалось запустить Tor: ' + e.message));
    proc.on('exit', code => {
      if (proc !== this.proc) return;
      this.proc = null;
      const reason = (tail.match(/\[err\][^\r\n]*/g) || []).pop() || ('код ' + code);
      this.emit('failed', 'Tor остановился: ' + reason.replace(/^\[err\]\s*/, ''));
    });
  }

  stop() {
    const p = this.proc;
    this.proc = null;
    if (p) p.kill();
  }
}

module.exports = { Tor, freePort };

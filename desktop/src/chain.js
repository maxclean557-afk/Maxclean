'use strict';
// A small SOCKS5 server on 127.0.0.1 that sends every connection through a chain of proxies,
// one after another: hop 1 → hop 2 → … → the site. Each hop is a SOCKS5 or HTTP proxy, with or
// without a login. Host names are passed on as names, never resolved here, so DNS also travels
// through the chain (and through Tor, when Tor is one of the hops).
// Same design as ProxyChain.java in the Android app.

const net = require('net');

const MAX_HOPS = 5;
const TIMEOUT_MS = 30000;

/** "socks5://[user:pass@]host:port", "http://…" or bare "host:port" → hop, or null. */
function parseHop(raw) {
  raw = (raw || '').trim();
  if (!raw || raw.startsWith('#')) return null;
  if (!raw.includes('://')) raw = 'socks5://' + raw;
  let u;
  try {
    u = new URL(raw);
  } catch (e) {
    return null;
  }
  const scheme = u.protocol.replace(':', '').toLowerCase();
  const http = scheme === 'http' || scheme === 'https';
  const socks = scheme === 'socks5' || scheme === 'socks' || scheme === 'socks5h';
  const port = Number(u.port);
  if ((!http && !socks) || !u.hostname || !(port > 0 && port < 65536)) return null;
  return {
    http,
    host: u.hostname.replace(/^\[|\]$/g, ''),
    port,
    user: u.username ? decodeURIComponent(u.username) : null,
    pass: u.password ? decodeURIComponent(u.password) : '',
    toString() { return (http ? 'http://' : 'socks5://') + this.host + ':' + this.port; },
  };
}

function parseChain(text) {
  const hops = [];
  for (const line of (text || '').split(/\r?\n/)) {
    const h = parseHop(line);
    if (h && hops.length < MAX_HOPS) hops.push(h);
  }
  return hops;
}

/** Reads exactly n bytes from a socket (with a buffer of what is already read). */
function reader(sock) {
  let buf = Buffer.alloc(0);
  const waiting = [];
  const pump = () => {
    while (waiting.length && buf.length >= waiting[0].n) {
      const w = waiting.shift();
      const out = buf.subarray(0, w.n);
      buf = buf.subarray(w.n);
      w.resolve(out);
    }
  };
  const onData = d => { buf = Buffer.concat([buf, d]); pump(); };
  const onEnd = () => { while (waiting.length) waiting.shift().reject(new Error('closed')); };
  sock.on('data', onData);
  sock.on('close', onEnd);
  sock.on('error', onEnd);
  return {
    read(n) { return new Promise((resolve, reject) => { waiting.push({ n, resolve, reject }); pump(); }); },
    /** Reads up to the end of HTTP headers. */
    async headers() {
      let text = '';
      while (!text.endsWith('\r\n\r\n')) {
        text += (await this.read(1)).toString('latin1');
        if (text.length > 16384) throw new Error('bad answer');
      }
      return text;
    },
    /** Stops reading and returns whatever arrived early, for the byte pipe. */
    release() {
      sock.removeListener('data', onData);
      sock.removeListener('close', onEnd);
      sock.removeListener('error', onEnd);
      const rest = buf;
      buf = Buffer.alloc(0);
      return rest;
    },
  };
}

async function readAddress(r, type) {
  if (type === 1) return [...await r.read(4)].join('.');
  if (type === 3) return (await r.read((await r.read(1))[0])).toString('utf8');
  if (type === 4) {
    const b = await r.read(16);
    const parts = [];
    for (let i = 0; i < 16; i += 2) parts.push(b.readUInt16BE(i).toString(16));
    return parts.join(':');
  }
  throw new Error('bad address type ' + type);
}

async function socksConnect(sock, r, via, host, port) {
  const login = via.user !== null;
  sock.write(Buffer.from(login ? [5, 2, 0, 2] : [5, 1, 0]));
  const choice = await r.read(2);
  if (choice[0] !== 5) throw new Error(via + ': not a SOCKS5 proxy');
  if (choice[1] === 2) {
    if (!login) throw new Error(via + ': asks for a login');
    const u = Buffer.from(via.user, 'utf8'), p = Buffer.from(via.pass, 'utf8');
    sock.write(Buffer.concat([Buffer.from([1, u.length]), u, Buffer.from([p.length]), p]));
    if ((await r.read(2))[1] !== 0) throw new Error(via + ': wrong login');
  } else if (choice[1] !== 0) {
    throw new Error(via + ': no usable login method');
  }
  const name = Buffer.from(host, 'utf8');
  sock.write(Buffer.concat([Buffer.from([5, 1, 0, 3, name.length]), name, Buffer.from([port >> 8, port & 255])]));
  const reply = await r.read(4);
  if (reply[1] !== 0) throw new Error(via + ': refused (' + reply[1] + ')');
  await readAddress(r, reply[3]);
  await r.read(2);
}

async function httpConnect(sock, r, via, host, port) {
  const target = (host.includes(':') ? '[' + host + ']' : host) + ':' + port;
  let req = 'CONNECT ' + target + ' HTTP/1.1\r\nHost: ' + target + '\r\n';
  if (via.user !== null) {
    req += 'Proxy-Authorization: Basic ' + Buffer.from(via.user + ':' + via.pass).toString('base64') + '\r\n';
  }
  sock.write(req + '\r\n', 'latin1');
  const head = await r.headers();
  const status = head.slice(0, head.indexOf('\r\n'));
  if (!/^HTTP\/1\.[01] 2\d\d/.test(status)) throw new Error(via + ': ' + status);
}

class ProxyChain {
  constructor(hops) {
    this.hops = hops.slice();
    this.sockets = new Set();
    this.server = null;
  }

  /** Starts listening on a free local port; resolves to the port. */
  start() {
    return new Promise((resolve, reject) => {
      this.server = net.createServer(c => this.serve(c));
      this.server.on('error', reject);
      this.server.listen(0, '127.0.0.1', () => resolve(this.server.address().port));
    });
  }

  stop() {
    if (this.server) this.server.close();
    for (const s of this.sockets) s.destroy();
    this.sockets.clear();
  }

  track(s) {
    this.sockets.add(s);
    s.on('close', () => this.sockets.delete(s));
    s.setTimeout(TIMEOUT_MS, () => s.destroy());
    return s;
  }

  async serve(client) {
    this.track(client);
    const r = reader(client);
    let up = null;
    try {
      // SOCKS5 greeting: only "no login" towards the local browser or Tor.
      const head = await r.read(2);
      if (head[0] !== 5) throw new Error('not SOCKS5');
      await r.read(head[1]);
      client.write(Buffer.from([5, 0]));
      const req = await r.read(4);
      if (req[1] !== 1) {
        client.end(Buffer.from([5, 7, 0, 1, 0, 0, 0, 0, 0, 0]));
        return;
      }
      const host = await readAddress(r, req[3]);
      const pb = await r.read(2);
      const port = pb.readUInt16BE(0);
      try {
        up = await this.connectThrough(host, port);
      } catch (e) {
        client.end(Buffer.from([5, 5, 0, 1, 0, 0, 0, 0, 0, 0]));
        return;
      }
      client.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
      const early = r.release();
      if (early.length) up.sock.write(early);
      if (up.early.length) client.write(up.early);
      client.setTimeout(0);
      up.sock.setTimeout(0);
      client.pipe(up.sock);
      up.sock.pipe(client);
    } catch (e) {
      client.destroy();
      if (up) up.sock.destroy();
    }
  }

  /** Opens a connection to host:port through every hop in turn. */
  connectThrough(host, port) {
    const first = this.hops[0];
    return new Promise((resolve, reject) => {
      const sock = this.track(net.connect(first.port, first.host));
      sock.once('error', reject);
      sock.once('connect', async () => {
        const r = reader(sock);
        try {
          for (let i = 0; i < this.hops.length; i++) {
            const via = this.hops[i];
            const last = i === this.hops.length - 1;
            const nextHost = last ? host : this.hops[i + 1].host;
            const nextPort = last ? port : this.hops[i + 1].port;
            if (via.http) await httpConnect(sock, r, via, nextHost, nextPort);
            else await socksConnect(sock, r, via, nextHost, nextPort);
          }
          sock.removeListener('error', reject);
          resolve({ sock, early: r.release() });
        } catch (e) {
          sock.destroy();
          reject(e);
        }
      });
    });
  }
}

module.exports = { ProxyChain, parseHop, parseChain, MAX_HOPS };

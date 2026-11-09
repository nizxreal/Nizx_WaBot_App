'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.argv[2]);
const SECRET = String(process.argv[3] || '');
const DATA_DIR = process.argv[4] || path.join(__dirname, 'data');
const AUTH_DIR = path.join(DATA_DIR, 'auth');
const START = Date.now();

// ---------- log: buffer 2000 baris, dibaca UI lewat /logs ----------
const LINES = [];
let seq = 0;
const LEVEL = { log: 'I', info: 'I', warn: 'W', error: 'E' };
function fmt(a) {
  if (typeof a === 'string') return a;
  if (a && a.stack) return a.stack;
  try { return JSON.stringify(a); } catch { return String(a); }
}
for (const k of Object.keys(LEVEL)) {
  const orig = console[k].bind(console);
  console[k] = (...args) => {
    LINES.push({ n: ++seq, t: `${new Date().toISOString().slice(11, 19)} ${LEVEL[k]} ${args.map(fmt).join(' ')}` });
    if (LINES.length > 2000) LINES.splice(0, LINES.length - 2000);
    try { orig(...args); } catch {}
  };
}
process.on('uncaughtException', (e) => console.error('uncaughtException:', e));
process.on('unhandledRejection', (e) => console.error('unhandledRejection:', e));

// ---------- Baileys ----------
let B = null;
async function loadBaileys() {
  if (B) return B;
  const mod = await import('@whiskeysockets/baileys');
  const pick = (k) => mod[k] ?? (mod.default && mod.default[k]);
  const make =
    typeof mod.makeWASocket === 'function' ? mod.makeWASocket
    : typeof mod.default === 'function' ? mod.default
    : mod.default && mod.default.default;
  B = {
    make,
    useMultiFileAuthState: pick('useMultiFileAuthState'),
    DisconnectReason: pick('DisconnectReason'),
    fetchLatestBaileysVersion: pick('fetchLatestBaileysVersion'),
    Browsers: pick('Browsers'),
  };
  return B;
}

function baileysVersion() {
  try {
    return JSON.parse(fs.readFileSync(path.join(__dirname, 'node_modules', '@whiskeysockets', 'baileys', 'package.json'), 'utf8')).version;
  } catch { return '?'; }
}
const BAILEYS = baileysVersion();

const noop = () => {};
const logger = { level: 'silent', trace: noop, debug: noop, info: noop, warn: noop, error: noop, fatal: noop, child() { return logger; } };

const bot = { sock: null, gen: 0, status: 'idle', number: '', pairingCode: '', pending: null, msgs: 0, attempts: 0, timer: null, stopped: false };
const digits = (j) => String(j || '').split('@')[0].split(':')[0].replace(/\D/g, '');

function hasSession() {
  try { return !!JSON.parse(fs.readFileSync(path.join(AUTH_DIR, 'creds.json'), 'utf8')).me; } catch { return false; }
}
function wipeAuth() { fs.rmSync(AUTH_DIR, { recursive: true, force: true }); }
function closeSock() {
  const s = bot.sock;
  bot.sock = null;
  if (s) { try { s.end(new Error('closed')); } catch {} }
}
function scheduleReconnect() {
  if (bot.stopped) return;
  bot.attempts += 1;
  clearTimeout(bot.timer);
  bot.timer = setTimeout(connect, Math.min(30000, 2000 * bot.attempts));
}

function requestPairingCode(gen) {
  const pending = bot.pending;
  const sock = bot.sock;
  if (!pending || pending.requesting || !sock || gen !== bot.gen) return;

  pending.requesting = true;
  Promise.resolve()
    .then(() => sock.requestPairingCode(pending.number))
    .then((code) => {
      if (gen !== bot.gen || bot.pending !== pending) return;
      bot.pending = null;
      bot.pairingCode = String(code || '');
      bot.status = 'pairing';
      console.log('[wa] kode pairing dibuat');
      pending.resolve(bot.pairingCode);
    })
    .catch((e) => {
      if (gen !== bot.gen || bot.pending !== pending) return;
      pending.requesting = false;
      console.error('[wa] gagal meminta kode pairing:', (e && e.stack) || e);
    });
}

async function connect() {
  if (bot.stopped) return;
  clearTimeout(bot.timer);
  const gen = ++bot.gen;
  closeSock();
  try {
    const b = await loadBaileys();
    if (gen !== bot.gen) return;
    fs.mkdirSync(AUTH_DIR, { recursive: true });
    const { state, saveCreds } = await b.useMultiFileAuthState(AUTH_DIR);
    const cfg = { auth: state, logger, printQRInTerminal: false, markOnlineOnConnect: false, syncFullHistory: false };
    try {
      const v = await b.fetchLatestBaileysVersion();
      if (v && v.version) cfg.version = v.version;
    } catch (e) { console.warn('fetchLatestBaileysVersion gagal:', e.message); }
    if (b.Browsers && b.Browsers.ubuntu) cfg.browser = b.Browsers.ubuntu('Chrome');
    if (gen !== bot.gen) return;
    const sock = b.make(cfg);
    bot.sock = sock;
    bot.status = bot.pending ? 'pairing' : 'connecting';
    sock.ev.on('creds.update', () => { if (gen === bot.gen) saveCreds(); });
    sock.ev.on('connection.update', (u) => onConn(gen, b, u));
    sock.ev.on('messages.upsert', (ev) => onMsg(gen, ev));
  } catch (e) {
    console.error('connect gagal:', (e && e.stack) || e);
    bot.status = 'error';
    if (gen === bot.gen) scheduleReconnect();
  }
}

function onConn(gen, b, u) {
  if (gen !== bot.gen) return;
  const { connection, lastDisconnect, qr } = u;

  if ((qr || connection === 'connecting') && bot.pending) requestPairingCode(gen);

  if (connection === 'open') {
    bot.attempts = 0;
    bot.pairingCode = '';
    bot.number = digits(bot.sock && bot.sock.user && bot.sock.user.id);
    bot.status = 'open';
    console.log('[wa] terhubung sebagai', bot.number);
  }
  if (connection === 'close') {
    const disconnectError = lastDisconnect && lastDisconnect.error;
    const code = disconnectError && disconnectError.output
      ? disconnectError.output.statusCode : undefined;
    const reason = disconnectError && (disconnectError.message || String(disconnectError));
    console.warn('[wa] koneksi tutup, kode', code, 'alasan', reason || '(tidak tersedia)');
    const linked = !!(bot.sock && bot.sock.authState && bot.sock.authState.creds && bot.sock.authState.creds.me);

    if (code === b.DisconnectReason.loggedOut) {
      closeSock(); wipeAuth(); bot.number = ''; bot.pairingCode = ''; bot.status = 'idle'; return;
    }
    if (code === b.DisconnectReason.connectionReplaced) { closeSock(); bot.status = 'replaced'; return; }
    if (bot.stopped) return;

    if (bot.pending) {
      bot.pending.requesting = false;
      bot.status = 'pairing';
      scheduleReconnect();
      return;
    }
    if (!linked && !bot.pairingCode && code !== b.DisconnectReason.restartRequired) {
      closeSock(); wipeAuth(); bot.number = ''; bot.status = 'idle';
      return;
    }
    bot.status = bot.pairingCode ? 'pairing' : 'reconnecting';
    scheduleReconnect();
  }
}

function onMsg(gen, ev) {
  if (gen !== bot.gen || ev.type !== 'notify') return;
  for (const m of ev.messages) {
    try {
      if (!m.message || m.key.remoteJid === 'status@broadcast') continue;
      const ts = Number(m.messageTimestamp);
      if (ts && Date.now() / 1000 - ts > 120) continue;
      bot.msgs++;
      const x = m.message;
      const text = x.conversation || (x.extendedTextMessage && x.extendedTextMessage.text) || '';
      console.log(`[msg] dari ...${digits(m.key.remoteJid).slice(-4)} fromMe=${!!m.key.fromMe} panjang=${text.length}`);
      if (text.trim().toLowerCase() === '.ping') {
        const up = Math.round((Date.now() - START) / 60000);
        bot.sock.sendMessage(m.key.remoteJid, { text: `pong (uptime ${up} menit, Node ${process.version})` }, { quoted: m })
          .catch((e) => console.error('kirim gagal:', e.message));
      }
    } catch (e) { console.error('pesan error:', e.message); }
  }
}

async function pair(number) {
  const n = String(number || '').replace(/\D/g, '');
  if (n.length < 8 || n.length > 15) throw new Error('Nomor tidak valid. Contoh: pair 6281234567890');
  if (bot.status === 'open') throw new Error('Sudah terhubung. Ketik logout dulu.');
  bot.stopped = false;
  bot.gen++;
  clearTimeout(bot.timer);
  closeSock();
  wipeAuth();
  return new Promise((resolve, reject) => {
    bot.pending = { number: n, resolve, reject, requesting: false };
    connect().catch(reject);
    setTimeout(() => {
      if (bot.pending && bot.pending.resolve === resolve) {
        bot.pending = null;
        bot.status = 'error';
        reject(new Error('Timeout meminta kode pairing. Periksa log koneksi dan coba lagi.'));
      }
    }, 30000);
  });
}

async function logout() {
  const s = bot.sock;
  const was = bot.status === 'open';
  bot.gen++;
  clearTimeout(bot.timer);
  try { if (s && was) await s.logout(); } catch {}
  closeSock();
  wipeAuth();
  bot.number = '';
  bot.pairingCode = '';
  bot.status = 'idle';
}

function statusObj() {
  return {
    ok: true,
    status: bot.status,
    number: bot.number || '-',
    pairingCode: bot.pairingCode || '',
    hasSession: hasSession(),
    msgs: bot.msgs,
    uptimeMin: Math.round((Date.now() - START) / 60000),
    rssMB: Math.round(process.memoryUsage().rss / 1048576),
    node: process.version,
    arch: process.arch,
    baileys: BAILEYS,
  };
}

async function runCmd(line) {
  const [cmd, ...rest] = line.trim().split(/\s+/);
  switch ((cmd || '').toLowerCase()) {
    case 'help': return 'help | status | pair <628xxx> | start | stop | logout';
    case 'status': return JSON.stringify(statusObj(), null, 2);
    case 'pair': return await pair(rest[0]);
    case 'start':
      if (!hasSession()) return 'Belum ada sesi. Pakai: pair <nomor>';
      bot.stopped = false;
      await connect();
      return 'ok';
    case 'stop':
      bot.stopped = true;
      bot.gen++;
      closeSock();
      bot.status = 'stopped';
      return 'ok';
    case 'logout': await logout(); return 'ok';
    default: return 'perintah tidak dikenal. Ketik help';
  }
}

// ---------- server HTTP lokal ----------
const server = http.createServer((req, res) => {
  const send = (code, obj) => {
    const body = JSON.stringify(obj);
    res.writeHead(code, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
    res.end(body);
  };
  const key = String(req.headers['x-key'] || '');
  const ok = key.length === SECRET.length && SECRET.length > 0 && crypto.timingSafeEqual(Buffer.from(key), Buffer.from(SECRET));
  if (!ok) return send(403, { ok: false });

  const url = new URL(req.url, 'http://127.0.0.1');

  if (req.method === 'GET' && url.pathname === '/status') return send(200, statusObj());
  if (req.method === 'GET' && url.pathname === '/logs') {
    const after = Number(url.searchParams.get('after')) || 0;
    return send(200, { next: seq, lines: LINES.filter((l) => l.n > after).map((l) => l.t) });
  }
  if (req.method === 'POST' && url.pathname === '/cmd') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 4096) req.destroy(); });
    req.on('end', async () => {
      try {
        const { cmd } = JSON.parse(body || '{}');
        send(200, { ok: true, out: String(await runCmd(String(cmd || ''))) });
      } catch (e) { send(200, { ok: false, out: e.message }); }
    });
    return;
  }
  send(404, { ok: false });
});
server.on('error', (e) => console.error('http error:', e.message));
server.listen(PORT, '127.0.0.1', () => console.log(`[node] http 127.0.0.1:${PORT}`));

console.log(`[node] ${process.version} ${process.platform}/${process.arch} baileys=${BAILEYS}`);
console.log(`[node] data=${DATA_DIR}`);

setInterval(() => {
  const m = process.memoryUsage();
  console.log(`[hb] up=${Math.round((Date.now() - START) / 60000)}m rss=${Math.round(m.rss / 1048576)}MB heap=${Math.round(m.heapUsed / 1048576)}MB wa=${bot.status} msgs=${bot.msgs}`);
}, 5 * 60 * 1000);

if (hasSession()) connect();

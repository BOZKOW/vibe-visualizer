"""Vibe Visualizer server helper.

Keeps a hidden Spotify web player open (headless Chromium), borrows the access
and client tokens it creates, and asks Spotify's own "canvas" GraphQL query for
a track's looping video. Unofficial: Spotify can change any of this at any time.

GET /canvas?id=<spotify track id>  ->  {"url": "https://canvaz.scdn.co/..."}  or 404
GET /health                        ->  status JSON
GET /lyrics?id=<spotify track id>  ->  Spicy Lyrics API answer (secret key stays on this server)
GET /spotify-lyrics?id=<track id>   ->  Spotify's own lyrics via the web player session (unofficial, needs sp_dc)
GET /netease?title=&artist=&duration=  ->  NetEase Cloud Music lyrics (unofficial API)
GET/PUT/DELETE /prefs              ->  per-device Vibe Visualizer preferences, keyed by the device's Tailscale IP
GET  /version                      ->  installed web app version and the newest one on GitHub
POST /update                       ->  download the newest web app files from GitHub into WEB_DIR
"""
import asyncio, ipaddress, json, os, re, time, urllib.error, urllib.parse, urllib.request
from playwright.async_api import async_playwright

SP_DC_FILE = os.environ.get("SP_DC_FILE", "/config/sp_dc")
PORT = int(os.environ.get("PORT", "8080"))
FALLBACK_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
TOKEN_MAX_AGE = 45 * 60
HIT_TTL, MISS_TTL = 12 * 3600, 2 * 3600
PREFS_DIR = os.environ.get("PREFS_DIR", "/prefs")
TS_NETS = [ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48")]
SPICY_KEY_FILE = os.environ.get("SPICY_KEY_FILE", "/config/spicy_key")
SPICY_API = "https://api.spicylyrics.org/v1/lyrics/"
SPICY_TTL, SPICY_MISS_TTL = 7 * 86400, 6 * 3600   # API terms: anything stored must be refetched or dropped within 30 days
lyrics_cache = {}      # in memory only: track id -> (status, body, expires)
# updates: the web app files are downloaded from this GitHub repository into WEB_DIR (the folder nginx serves)
UPDATE_REPO = os.environ.get("UPDATE_REPO", "BOZKOW/vibe-visualizer")
UPDATE_BRANCH = os.environ.get("UPDATE_BRANCH", "main")
WEB_DIR = os.environ.get("WEB_DIR", "/www")
WEB_FILES = ["index.html", "sw.js", "manifest.webmanifest", "logo.svg", "icon-192.png", "icon-512.png",
             "icon-maskable-512.png", "apple-touch-icon.png"]
ID_RE = re.compile(r"^[0-9A-Za-z]{22}$")
HASH_RE = re.compile(r'"canvas","query","([0-9a-f]{64})"')

timing = {}          # service -> {"ms", "status", "at"}: timings of real requests, for the ping meter
state = {"headers": None, "minted": 0, "hash": FALLBACK_HASH, "logged_in": False, "error": None}
cache = {}            # track id -> (url or None, expires)
lock = asyncio.Lock()
pw = browser = page = None


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def read_sp_dc():
    try:
        return open(SP_DC_FILE, encoding="utf-8-sig").read().strip() or None   # utf-8-sig: files saved on Windows may start with a BOM
    except OSError:
        return None


async def mint():
    """(Re)open the web player and wait until it has made an authorized API request."""
    global page
    got = asyncio.get_running_loop().create_future()

    async def on_request(req):
        u = req.url
        if got.done() or not ("api-partner.spotify.com" in u or "spclient" in u):
            return
        h = await req.all_headers()
        if h.get("authorization", "").startswith("Bearer ") and h.get("client-token"):
            keep = {k: v for k, v in h.items() if k in ("authorization", "client-token", "app-platform", "spotify-app-version", "accept-language")}
            if not got.done():
                got.set_result(keep)

    async def on_response(res):
        if "/cdn/build/web-player/" in res.url and res.url.endswith(".js"):
            try:
                m = HASH_RE.search(await res.text())
                if m and m.group(1) != state["hash"]:
                    state["hash"] = m.group(1)
                    log("canvas query hash:", m.group(1))
            except Exception:
                pass

    if page:
        await page.context.close()
    ctx = await browser.new_context(locale="en-US", viewport={"width": 1280, "height": 800})
    sp_dc = read_sp_dc()
    if sp_dc:
        await ctx.add_cookies([{"name": "sp_dc", "value": sp_dc, "domain": ".spotify.com", "path": "/", "secure": True, "httpOnly": True}])
    page = await ctx.new_page()
    page.on("request", on_request)
    page.on("response", on_response)
    await page.goto("https://open.spotify.com/", wait_until="domcontentloaded", timeout=60000)
    state["headers"] = await asyncio.wait_for(got, 60)
    state["minted"] = time.time()
    state["logged_in"] = bool(sp_dc)
    state["error"] = None
    log("tokens ready", "(logged in)" if sp_dc else "(anonymous)")


async def query(track_id):
    body = {"variables": {"trackUri": "spotify:track:" + track_id}, "operationName": "canvas",
            "extensions": {"persistedQuery": {"version": 1, "sha256Hash": state["hash"]}}}
    # run the request inside the web player page, so it looks exactly like the player's own
    return await page.evaluate("""async ([headers, body]) => {
        for (const v of ['v2', 'v1']) {
            const r = await fetch('https://api-partner.spotify.com/pathfinder/' + v + '/query', {
                method: 'POST', headers: {...headers, 'content-type': 'application/json;charset=UTF-8', accept: 'application/json'},
                body: JSON.stringify(body)});
            if (r.status === 404 && v === 'v2') continue;
            return {status: r.status, json: await r.json().catch(() => null)};
        }
    }""", [state["headers"], body])


async def canvas(track_id):
    c = cache.get(track_id)
    if c and c[1] > time.time():
        return c[0]
    async with lock:
        for attempt in range(2):
            if not state["headers"] or time.time() - state["minted"] > TOKEN_MAX_AGE or attempt:
                await mint()
            t0 = time.time()
            r = await query(track_id)
            timing["canvas"] = {"ms": int((time.time() - t0) * 1000), "status": r and r["status"], "at": time.time()}
            if r and r["status"] in (401, 403):
                log("token rejected, re-minting"); continue
            if not r or r["status"] != 200:
                raise RuntimeError("Spotify answered HTTP %s" % (r and r["status"]))
            j = r["json"] or {}
            if j.get("errors") and not j.get("data"):
                raise RuntimeError("Spotify error: " + str(j["errors"])[:200])
            cv = (((j.get("data") or {}).get("trackUnion") or {}).get("canvas") or {})
            url = cv.get("url") if str(cv.get("url", "")).endswith((".mp4", ".webm")) else None
            cache[track_id] = (url, time.time() + (HIT_TTL if url else MISS_TTL))
            return url
        raise RuntimeError("Spotify keeps rejecting the token")


def spicy_key():
    try:
        return open(SPICY_KEY_FILE, encoding="utf-8-sig").read().strip() or None
    except OSError:
        return None


def spicy_fetch(track_id, key):
    req = urllib.request.Request(SPICY_API + track_id, headers={
        "Authorization": "Bearer " + key, "Accept": "application/json", "User-Agent": "VibeVisualizer/1.0 (self-hosted, single user)"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read() or b"null")
        except ValueError:
            body = None
        return e.code, body


async def spicy_lyrics(track_id):
    c = lyrics_cache.get(track_id)
    if c and c[2] > time.time():
        return c[0], c[1]
    key = spicy_key()
    if not key:
        return 503, {"error": "no Spicy Lyrics key on the server", "code": "no_key"}
    t0 = time.time()
    status, body = await asyncio.to_thread(spicy_fetch, track_id, key)
    timing["spicy"] = {"ms": int((time.time() - t0) * 1000), "status": status, "at": time.time()}
    if status in (200, 404):
        lyrics_cache[track_id] = (status, body, time.time() + (SPICY_TTL if status == 200 else SPICY_MISS_TTL))
        if len(lyrics_cache) > 2000:   # keep it a small cache, never a mirror
            for k in sorted(lyrics_cache, key=lambda k: lyrics_cache[k][2])[:500]:
                lyrics_cache.pop(k, None)
    if status == 404:
        return 404, {"error": "no lyrics for this track"}
    return status, body if body is not None else {"error": "HTTP %d" % status}


extra_cache = {}       # in memory only: ("spotify"|"netease", key) -> (status, body, expires)


def cached(kind, key):
    c = extra_cache.get((kind, key))
    return (c[0], c[1]) if c and c[2] > time.time() else None


def remember(kind, key, status, body):
    if status in (200, 404):
        extra_cache[(kind, key)] = (status, body, time.time() + (7 * 86400 if status == 200 else 6 * 3600))
        if len(extra_cache) > 3000:
            for k in sorted(extra_cache, key=lambda k: extra_cache[k][2])[:800]:
                extra_cache.pop(k, None)


async def spotify_lyrics(track_id):
    """Spotify's own lyrics (Musixmatch etc.), asked from inside the signed-in web player. Unofficial."""
    c = cached("spotify", track_id)
    if c:
        return c
    if not read_sp_dc():
        return 503, {"error": "no sp_dc cookie on the server", "code": "no_cookie"}
    async with lock:
        for attempt in range(2):
            if not state["headers"] or time.time() - state["minted"] > TOKEN_MAX_AGE or attempt:
                await mint()
            t0 = time.time()
            r = await page.evaluate("""async ([h, id]) => {
                const r = await fetch('https://spclient.wg.spotify.com/color-lyrics/v2/track/' + id + '?format=json&vocalRemoval=false&market=from_token',
                    {headers: {...h, 'app-platform': 'WebPlayer', accept: 'application/json'}});
                return {status: r.status, json: r.status === 200 ? await r.json().catch(() => null) : null};
            }""", [state["headers"], track_id])
            timing["spotify_lyrics"] = {"ms": int((time.time() - t0) * 1000), "status": r["status"], "at": time.time()}
            if r["status"] in (401, 403) and not attempt:
                continue
            break
    if r["status"] == 200 and r["json"]:
        remember("spotify", track_id, 200, r["json"])
        return 200, r["json"]
    if r["status"] in (204, 404):
        remember("spotify", track_id, 404, {"error": "no lyrics for this track"})
        return 404, {"error": "no lyrics for this track"}
    return 502, {"error": "Spotify answered HTTP %s" % r["status"]}


NE_HEADERS = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36",
              "Referer": "https://music.163.com/"}


def ne_get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=NE_HEADERS), timeout=15) as r:
        return json.loads(r.read() or b"null")


def ne_norm(s):
    s = re.sub(r"\s*[\(\[（【].*?[\)\]）】]", "", str(s or "").lower())
    return re.sub(r"[\W_]+", " ", s).strip()


def ne_work(title, artist, duration):
    d = ne_get("https://music.163.com/api/search/get?" + urllib.parse.urlencode({"s": (title + " " + artist).strip(), "type": 1, "limit": 10}))
    songs = ((d or {}).get("result") or {}).get("songs") or []
    t, a = ne_norm(title), ne_norm(artist)
    best, score = None, -1
    for s in songs:
        n = ne_norm(s.get("name"))
        sc = 4 if n == t else 2 if t and n and (t in n or n in t) else 0
        arts = ne_norm(" ".join(x.get("name", "") for x in s.get("artists") or []))
        if a and a in arts:
            sc += 3
        dur = (s.get("duration") or 0) / 1000
        if duration and dur and abs(dur - duration) <= 4:
            sc += 2
        if sc > score:
            best, score = s, sc
    if not best or score < 4:      # title or artist+duration must match, never "some song"
        return 404, {"error": "no match on NetEase"}
    ly = ne_get("https://music.163.com/api/song/lyric?" + urllib.parse.urlencode({"id": best["id"], "lv": 1, "kv": 1, "tv": -1, "rv": -1}))
    lrc = ((ly or {}).get("lrc") or {}).get("lyric") or ""
    if not lrc.strip() or (ly or {}).get("nolyric") or (ly or {}).get("uncollected"):
        return 404, {"error": "no lyrics on NetEase"}
    return 200, {"id": best["id"], "name": best.get("name"), "artists": [x.get("name") for x in best.get("artists") or []],
                 "lrc": lrc, "tlyric": ((ly.get("tlyric") or {}).get("lyric")) or "", "romalrc": ((ly.get("romalrc") or {}).get("lyric")) or ""}


async def netease(title, artist, duration):
    key = (title.lower(), artist.lower(), int(duration) // 3)
    c = cached("netease", key)
    if c:
        return c
    t0 = time.time()
    try:
        status, body = await asyncio.to_thread(ne_work, title, artist, duration)
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        status, body = 502, {"error": "NetEase did not answer (%s)" % e}
    timing["netease"] = {"ms": int((time.time() - t0) * 1000), "status": status, "at": time.time()}
    remember("netease", key, status, body)
    return status, body


def gh_get(url, raw=False):
    req = urllib.request.Request(url, headers={"User-Agent": "VibeVisualizer-updater", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
    return data if raw else json.loads(data)


def installed_version():
    try:
        return json.load(open(os.path.join(WEB_DIR, "version.json")))
    except (OSError, ValueError):
        return None


def latest_version():
    """The newest commit on GitHub that changed the web app."""
    c = gh_get("https://api.github.com/repos/%s/commits?sha=%s&path=web&per_page=1" % (UPDATE_REPO, UPDATE_BRANCH))[0]
    return {"sha": c["sha"], "date": c["commit"]["committer"]["date"], "message": c["commit"]["message"].splitlines()[0]}


def web_dir_writable():
    return os.path.isdir(WEB_DIR) and os.access(WEB_DIR, os.W_OK)


def do_update():
    if not web_dir_writable():
        return 501, {"error": "updates need the web folder mounted writable into the helper at %s (see the README)" % WEB_DIR}
    new = latest_version()
    cur = installed_version()
    if cur and cur.get("sha") == new["sha"]:
        return 200, {"updated": False, "version": cur}
    files = {}
    for f in WEB_FILES:   # download everything first, so a failed download leaves the installed app untouched
        files[f] = gh_get("https://raw.githubusercontent.com/%s/%s/web/%s" % (UPDATE_REPO, new["sha"], f), raw=True)
    page = files["index.html"].decode("utf-8", "replace")
    if "Vibe Visualizer" not in page or "</html>" not in page.lower():
        return 502, {"error": "the downloaded page doesn't look like Vibe Visualizer; nothing was changed"}
    for f, data in files.items():   # replace each file in one step
        tmp = os.path.join(WEB_DIR, "." + f + ".new")
        with open(tmp, "wb") as fh:
            fh.write(data)
        os.replace(tmp, os.path.join(WEB_DIR, f))
    with open(os.path.join(WEB_DIR, "version.json"), "w") as fh:
        json.dump(new, fh)
    log("updated web app to", new["sha"][:7], new["message"])
    return 200, {"updated": True, "version": new}


def client_ip(headers):
    """The device's Tailscale address, as passed on by `tailscale serve` -> nginx. None if not from the tailnet."""
    ip = headers.get("x-forwarded-for", "").split(",")[0].strip()
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return None
    return str(a) if any(a in n for n in TS_NETS) else None


def prefs(method, ip, body):
    path = os.path.join(PREFS_DIR, ip.replace(":", "_") + ".json")
    if method == "GET":
        try:
            return 200, json.load(open(path))
        except (OSError, ValueError):
            return 404, {"error": "nothing saved for this device"}
    if method == "DELETE":
        try:
            os.remove(path)
        except OSError:
            pass
        return 200, {"ok": True}
    if method == "PUT":
        data = json.loads(body or b"{}")
        if not isinstance(data, dict):
            return 400, {"error": "expected a JSON object"}
        os.makedirs(PREFS_DIR, exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        return 200, {"ok": True}
    return 405, {"error": "method not allowed"}


async def handle(reader, writer):
    status, out = 500, {"error": "internal"}
    try:
        line = (await asyncio.wait_for(reader.readline(), 10)).decode("latin-1")
        headers = {}
        while True:
            h = await asyncio.wait_for(reader.readline(), 10)
            if h in (b"\r\n", b"\n", b""):
                break
            k, _, v = h.decode("latin-1").partition(":")
            headers[k.strip().lower()] = v.strip()
        n = int(headers.get("content-length") or 0)
        if n > 256 * 1024:
            raise ValueError("request too large")
        body = await asyncio.wait_for(reader.readexactly(n), 10) if n else b""
        method = line.split(" ")[0]
        path = line.split(" ")[1] if " " in line else "/"
        route, _, qs = path.partition("?")
        params = {k: v[0] for k, v in urllib.parse.parse_qs(qs).items()}
        if route.endswith("/prefs"):
            ip = client_ip(headers)
            status, out = prefs(method, ip, body) if ip else (403, {"error": "open Vibe Visualizer through its Tailscale HTTPS address to save preferences"})
        elif route.endswith("/lyrics"):
            tid = params.get("id", "")
            if not client_ip(headers):
                status, out = 403, {"error": "only for Vibe Visualizer on your tailnet"}
            elif not ID_RE.match(tid):
                status, out = 400, {"error": "id must be a 22-character Spotify track id"}
            else:
                status, out = await spicy_lyrics(tid)
        elif route.endswith("/spotify-lyrics") or route.endswith("/netease"):
            if not client_ip(headers):
                status, out = 403, {"error": "only for Vibe Visualizer on your tailnet"}
            elif route.endswith("/netease"):
                title = params.get("title", "").strip()[:200]
                if not title:
                    status, out = 400, {"error": "title is required"}
                else:
                    try:
                        dur = float(params.get("duration") or 0)
                    except ValueError:
                        dur = 0
                    status, out = await netease(title, params.get("artist", "").strip()[:200], dur)
            else:
                tid = params.get("id", "")
                status, out = (await spotify_lyrics(tid)) if ID_RE.match(tid) else (400, {"error": "id must be a 22-character Spotify track id"})
        elif route.endswith("/version") or route.endswith("/update"):
            if not client_ip(headers):
                status, out = 403, {"error": "only for Vibe Visualizer on your tailnet"}
            elif route.endswith("/version"):
                try:
                    latest = await asyncio.to_thread(latest_version)
                except Exception as e:
                    latest = {"error": str(e)[:200]}
                status, out = 200, {"installed": installed_version(), "latest": latest, "can_update": web_dir_writable(), "repo": UPDATE_REPO}
            elif method != "POST":
                status, out = 405, {"error": "use POST"}
            else:
                status, out = await asyncio.to_thread(do_update)
        elif route.endswith("/whoami"):
            status, out = 200, {"ip": client_ip(headers), "x-forwarded-for": headers.get("x-forwarded-for"), "tailscale-user": headers.get("tailscale-user-login")}
        elif route.endswith("/health"):
            status, out = 200, {"ok": state["error"] is None, "logged_in": state["logged_in"], "hash": state["hash"],
                                "token_age_s": int(time.time() - state["minted"]) if state["minted"] else None,
                                "cached": len(cache), "error": state["error"], "spicy_key": bool(spicy_key()), "can_update": web_dir_writable(),
                                "timing": {k: {"ms": v["ms"], "status": v["status"], "age_s": int(time.time() - v["at"])} for k, v in timing.items()}}
        elif route.endswith("/canvas"):
            tid = params.get("id", "")
            if not ID_RE.match(tid):
                status, out = 400, {"error": "id must be a 22-character Spotify track id"}
            elif params.get("raw") == "1":       # debugging: Spotify's unprocessed answer
                async with lock:
                    if not state["headers"]:
                        await mint()
                    status, out = 200, await query(tid)
            else:
                url = await canvas(tid)
                status, out = (200, {"url": url}) if url else (404, {"error": "no canvas for this track"})
        else:
            status, out = 404, {"error": "not found"}
    except Exception as e:
        state["error"] = str(e)[:300]
        log("error:", e)
        status, out = 502, {"error": str(e)[:300]}
    data = json.dumps(out).encode()
    reason = {200: "OK", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed", 501: "Not Implemented", 429: "Too Many Requests", 500: "Internal Server Error", 502: "Bad Gateway", 503: "Service Unavailable"}.get(status, "Error")
    writer.write(("HTTP/1.1 %d %s\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: %d\r\nConnection: close\r\n\r\n"
                  % (status, reason, len(data))).encode() + data)
    try:
        await writer.drain()
    finally:
        writer.close()


async def main():
    global pw, browser
    pw = await async_playwright().start()
    browser = await pw.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage", "--mute-audio"])
    try:
        async with lock:
            await mint()
    except Exception as e:
        state["error"] = "startup: %s" % e
        log("startup mint failed:", e)
    server = await asyncio.start_server(handle, "0.0.0.0", PORT)
    log("listening on", PORT)
    async with server:
        await server.serve_forever()


asyncio.run(main())

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
GET  /version                      ->  installed web app version, the newest one on GitHub, release notes, recent
                                        changes and the newest Android app release
POST /report, GET /report, DELETE /report  ->  error reports from the app, kept on this server (errors.jsonl in PREFS_DIR)
WS   /bridge/push, /bridge/listen  ->  relay for Vibe Bridge: a bridge pushes its bar data, the app's pages listen
POST /update                       ->  download the newest web app files from GitHub into WEB_DIR
*    /spweb/<path>                  ->  relay to Spotify's Web API with the app's own login, so the app can read
                                       Spotify's Retry-After (browsers can't: Spotify doesn't expose it)
"""
import asyncio, base64, hashlib, ipaddress, json, os, re, struct, time, urllib.error, urllib.parse, urllib.request
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
             "icon-maskable-512.png", "apple-touch-icon.png",
             "InterVariable.woff2", "InterVariable-LICENSE.txt"]
ID_RE = re.compile(r"^[0-9A-Za-z]{22}$")
SPWEB_RE = re.compile(r"^/spweb/(me(/[A-Za-z0-9_/-]*)?|artists/[0-9A-Za-z]{22}|tracks/[0-9A-Za-z]{22}|search)$")
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
        now = time.time()
        for k in [k for k, v in lyrics_cache.items() if v[2] <= now]:   # out-of-date answers are dropped, not kept around
            lyrics_cache.pop(k, None)                                     # (Spicy Lyrics terms: 30 days at most; we keep 7)
        lyrics_cache[track_id] = (status, body, now + (SPICY_TTL if status == 200 else SPICY_MISS_TTL))
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


latest_cache = {"at": 0, "v": None}


def latest_version():
    """The newest commit on GitHub that changed the web app. Remembered for 10 minutes: GitHub allows only 60 API
    requests an hour without a login (shared by everything on the same internet connection). When the API says no,
    the repository's public commit feed (not rate limited) gives the newest commit instead."""
    if latest_cache["v"] and time.time() - latest_cache["at"] < 600:
        return latest_cache["v"]
    try:
        c = gh_get("https://api.github.com/repos/%s/commits?sha=%s&path=web&per_page=1" % (UPDATE_REPO, UPDATE_BRANCH))[0]
        v = {"sha": c["sha"], "date": c["commit"]["committer"]["date"], "message": c["commit"]["message"].splitlines()[0]}
    except urllib.error.HTTPError as e:
        if e.code not in (403, 429):
            raise
        feed = gh_get("https://github.com/%s/commits/%s.atom" % (UPDATE_REPO, UPDATE_BRANCH), raw=True).decode("utf-8", "replace")
        e1 = re.search(r"<entry>(.*?)</entry>", feed, re.S)
        sha = e1 and re.search(r"Commit/([0-9a-f]{40})<", e1.group(1))
        if not sha:
            raise RuntimeError("GitHub is busy right now (too many requests); try again in a few minutes")
        upd, title = re.search(r"<updated>([^<]+)</updated>", e1.group(1)), re.search(r"<title>\s*(.*?)\s*</title>", e1.group(1), re.S)
        import html as _h
        v = {"sha": sha.group(1), "date": upd.group(1) if upd else "", "message": _h.unescape(title.group(1)).splitlines()[0] if title else ""}
    latest_cache.update(at=time.time(), v=v)
    return v


def note_of(c):
    """One release note from a commit: its title and its "- " bullet points."""
    msg = c["commit"]["message"].splitlines()
    pts = []
    for l in msg[1:]:
        if l.strip().startswith("- "):
            pts.append(l.strip()[2:].strip())
        elif pts and l.startswith("  ") and l.strip():   # a bullet that wraps onto the next line
            pts[-1] += " " + l.strip()
    return {"sha": c["sha"][:7], "date": c["commit"]["committer"]["date"], "title": msg[0], "points": pts}


feed_cache = {}


def gh_feed(path, ttl=600):
    """A public GitHub Atom feed (commits, releases): no API rate limit. Kept for 10 minutes."""
    c = feed_cache.get(path)
    if c and time.time() - c[0] < ttl:
        return c[1]
    x = gh_get("https://github.com/%s/%s" % (UPDATE_REPO, path), raw=True).decode("utf-8", "replace")
    feed_cache[path] = (time.time(), x)
    return x


def feed_commits():
    """The newest 20 commits from the commit feed, as API-style dicts (full message included)."""
    import html as _h
    out = []
    for e in re.findall(r"<entry>(.*?)</entry>", gh_feed("commits/%s.atom" % UPDATE_BRANCH), re.S):
        sha = re.search(r"Commit/([0-9a-f]{40})<", e)
        body = re.search(r'<content type="html">(.*?)</content>', e, re.S)
        upd = re.search(r"<updated>([^<]+)</updated>", e)
        if not sha:
            continue
        msg = _h.unescape(re.sub(r"<[^>]+>", "", _h.unescape(body.group(1)))) if body else ""
        out.append({"sha": sha.group(1), "commit": {"message": msg.strip(), "committer": {"date": upd.group(1) if upd else ""}}})
    return out


def release_notes(cur_sha, new_sha):
    """What changed since the installed version (newest first, at most 25), from the commit feed; the API when the
    installed version is older than the feed reaches."""
    try:
        cs = feed_commits()
        shas = [c["sha"] for c in cs]
        if cur_sha in shas:
            commits = cs[:shas.index(cur_sha)]
        elif cur_sha:
            commits = gh_get("https://api.github.com/repos/%s/compare/%s...%s" % (UPDATE_REPO, cur_sha, new_sha))["commits"][::-1]
        else:
            commits = cs[:10]
        return [note_of(c) for c in commits[:25] if not c["commit"]["message"].startswith("Merge ")]
    except Exception as e:
        log("release notes:", e)
        return []


def recent_notes(n=15):
    """The latest changes, whether installed or not, for Settings → Help & about."""
    try:
        return [note_of(c) for c in feed_commits()[:n] if not c["commit"]["message"].startswith("Merge ")]
    except Exception as e:
        log("recent notes:", e)
        return []


android_cache = {"at": 0, "v": None}


def android_latest():
    """The newest Android app release (by when it was published), its notes and its APK link. Kept for 30 minutes."""
    if android_cache["v"] is not None and time.time() - android_cache["at"] < 1800:
        return android_cache["v"]
    v = {}
    try:
        rels = [r for r in gh_get("https://api.github.com/repos/%s/releases?per_page=30" % UPDATE_REPO)
                if r.get("tag_name", "").startswith("android-v") and not r.get("draft")]
        rels.sort(key=lambda r: r.get("published_at") or "", reverse=True)
        if rels:
            r = rels[0]
            apk = next((a["browser_download_url"] for a in r.get("assets", []) if a["name"].endswith(".apk")), "")
            v = {"tag": r["tag_name"], "version": r["tag_name"][len("android-v"):], "title": r.get("name") or r["tag_name"],
                 "date": r.get("published_at") or "", "beta": bool(r.get("prerelease")), "url": r.get("html_url", ""), "apk": apk,
                 "points": [l.strip()[2:].strip() for l in (r.get("body") or "").splitlines() if l.strip().startswith(("- ", "* "))][:20]}
    except Exception as e:
        log("android release:", e)
        if android_cache["v"] is not None:
            return android_cache["v"]
    android_cache.update(at=time.time(), v=v)
    return v


ERR_FILE = os.path.join(PREFS_DIR, "errors.jsonl")


def report(method, ip, body):
    """Errors the app ran into, kept on this server (last 300) so they can be looked at and fixed."""
    if method == "DELETE":
        try:
            os.remove(ERR_FILE)
        except OSError:
            pass
        return 200, {"ok": True}
    if method == "GET":
        try:
            lines = open(ERR_FILE, encoding="utf-8").read().splitlines()[-50:]
        except OSError:
            lines = []
        return 200, {"errors": [json.loads(l) for l in lines[::-1] if l.strip()]}
    try:
        e = json.loads(body or b"{}")
    except ValueError:
        return 400, {"error": "not JSON"}
    rec = {"at": int(time.time()), "device": ip}
    for k in ("kind", "msg", "where", "stack", "version", "ua", "view", "src"):
        if isinstance(e.get(k), str):
            rec[k] = e[k][:2000]
    try:
        old = open(ERR_FILE, encoding="utf-8").read().splitlines()[-299:]
    except OSError:
        old = []
    with open(ERR_FILE + ".tmp", "w", encoding="utf-8") as fh:
        fh.write("\n".join(old + [json.dumps(rec, ensure_ascii=False)]) + "\n")
    os.replace(ERR_FILE + ".tmp", ERR_FILE)
    log("app error from", ip, ":", rec.get("msg", "")[:160])
    return 200, {"ok": True}


def web_dir_writable():
    return os.path.isdir(WEB_DIR) and os.access(WEB_DIR, os.W_OK)


def do_update():
    if not web_dir_writable():
        return 501, {"error": "updates need the web folder mounted writable into the helper at %s (see the README)" % WEB_DIR}
    new = latest_version()
    cur = installed_version()
    if cur and cur.get("sha") == new["sha"] and all(os.path.exists(os.path.join(WEB_DIR, f)) for f in WEB_FILES):   # also install files a newer helper knows about
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


def shared_spot_id():
    """A new device (a phone) starts with the Spotify Client ID another of your devices saved: it isn't secret, and
    typing it on a phone is no fun. Only the Client ID; every device still logs in to Spotify by itself."""
    best, at = "", 0
    try:
        for n in os.listdir(PREFS_DIR):
            if not n.endswith(".json") or n == "errors.json":
                continue
            p = os.path.join(PREFS_DIR, n)
            try:
                c = (json.load(open(p)).get("C") or {})
                sid, t = str(c.get("spotId") or "").strip(), os.path.getmtime(p)
            except (OSError, ValueError, AttributeError):
                continue
            if re.fullmatch(r"[0-9a-fA-F]{32}", sid) and t > at:
                best, at = sid, t
    except OSError:
        pass
    return best


def prefs(method, ip, body):
    path = os.path.join(PREFS_DIR, ip.replace(":", "_") + ".json")
    if method == "GET":
        try:
            data = json.load(open(path))
        except (OSError, ValueError):
            return 404, {"error": "nothing saved for this device", "spotId": shared_spot_id()}
        if isinstance(data, dict) and not ((data.get("C") or {}).get("spotId") or "").strip():
            data["spotId"] = shared_spot_id()
        return 200, data
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


# ---- Vibe Bridge relay: a bridge on the computer that plays the music pushes its analyser frames here; every
# Vibe Visualizer page on your tailnet listens. Plain WebSocket (RFC 6455), so pages never need to reach the bridge.
bridge = {"hello": None, "listeners": set(), "pushers": 0, "last": 0.0}


async def ws_read(reader):
    """One WebSocket message -> (opcode, payload); None when the connection closes."""
    try:
        h = await reader.readexactly(2)
    except (asyncio.IncompleteReadError, ConnectionError):
        return None
    op, n, masked = h[0] & 15, h[1] & 127, h[1] & 128
    if n == 126:
        n = struct.unpack(">H", await reader.readexactly(2))[0]
    elif n == 127:
        n = struct.unpack(">Q", await reader.readexactly(8))[0]
    if n > 65536:
        return None
    mask = await reader.readexactly(4) if masked else b""
    data = await reader.readexactly(n)
    if masked:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    return op, data


def ws_frame(op, data):
    n = len(data)
    head = bytes([0x80 | op]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n) if n < 65536 else bytes([127]) + struct.pack(">Q", n))
    return head + data


async def bridge_ws(reader, writer, headers, role):
    key = headers.get("sec-websocket-key", "")
    accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
    writer.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
    await writer.drain()
    if role == "listen":
        bridge["listeners"].add(writer)
        if bridge["hello"]:
            writer.write(ws_frame(1, bridge["hello"]))
        try:
            while True:
                m = await ws_read(reader)
                if not m or m[0] == 8:
                    break
                if m[0] == 9:
                    writer.write(ws_frame(10, m[1]))
        finally:
            bridge["listeners"].discard(writer)
    else:
        bridge["pushers"] += 1
        log("Vibe Bridge connected")
        try:
            while True:
                m = await ws_read(reader)
                if not m or m[0] == 8:
                    break
                op, data = m
                if op == 9:
                    writer.write(ws_frame(10, data))
                    continue
                if op == 1:
                    bridge["hello"] = data
                elif op != 2:
                    continue
                bridge["last"] = time.time()
                frame = ws_frame(op, data)
                for w in list(bridge["listeners"]):
                    if w.transport.get_write_buffer_size() < 256 * 1024:   # a slow page skips frames instead of piling them up
                        try:
                            w.write(frame)
                        except Exception:
                            bridge["listeners"].discard(w)
        finally:
            bridge["pushers"] -= 1
            log("Vibe Bridge disconnected")
            if not bridge["pushers"]:
                bridge["hello"] = None
                for w in list(bridge["listeners"]):   # tell the pages it's gone
                    try:
                        w.write(ws_frame(1, json.dumps({"type": "bye"}).encode()))
                    except Exception:
                        pass
    try:
        writer.write(ws_frame(8, b""))
        writer.close()
    except Exception:
        pass


def spweb(method, sub, qs, headers, body):
    # the app's own Spotify Web API request, sent from here so its Retry-After can be passed back
    req = urllib.request.Request("https://api.spotify.com/v1/" + sub + ("?" + qs if qs else ""), data=body or None, method=method)
    req.add_header("Authorization", headers.get("authorization", ""))
    if headers.get("content-type"):
        req.add_header("Content-Type", headers["content-type"])
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read(), r.headers.get("Retry-After")
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers.get("Retry-After")


async def handle(reader, writer):
    status, out, raw, xh = 500, {"error": "internal"}, None, {}
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
        if headers.get("upgrade", "").lower() == "websocket" and (route.endswith("/bridge/push") or route.endswith("/bridge/listen")):
            if not client_ip(headers):
                status, out = 403, {"error": "only for Vibe Visualizer on your tailnet"}
            else:
                return await bridge_ws(reader, writer, headers, "push" if route.endswith("/push") else "listen")
        elif SPWEB_RE.match(route[route.find("/spweb/"):] if "/spweb/" in route else ""):
            if not client_ip(headers):
                status, out = 403, {"error": "only for Vibe Visualizer on your tailnet"}
            elif not headers.get("authorization", "").startswith("Bearer "):
                status, out = 401, {"error": "no Spotify login"}
            elif method not in ("GET", "PUT", "POST", "DELETE"):
                status, out = 405, {"error": "method not allowed"}
            else:
                sub = route[route.find("/spweb/") + 7:]
                status, raw, ra = await asyncio.to_thread(spweb, method, sub, qs, headers, body)
                xh["X-Via"] = "vv-helper"
                if ra:
                    xh["Retry-After"] = re.sub(r"[^0-9]", "", ra)[:10]
        elif route.endswith("/prefs"):
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
        elif route.endswith("/report"):
            ip = client_ip(headers)
            status, out = report(method, ip, body) if ip else (403, {"error": "only for Vibe Visualizer on your tailnet"})
        elif route.endswith("/version") or route.endswith("/update"):
            if not client_ip(headers):
                status, out = 403, {"error": "only for Vibe Visualizer on your tailnet"}
            elif route.endswith("/version"):
                try:
                    latest = await asyncio.to_thread(latest_version)
                except Exception as e:
                    latest = {"error": str(e)[:200]}
                cur = installed_version()
                notes = []
                if latest.get("sha") and (not cur or cur.get("sha") != latest["sha"]):
                    notes = await asyncio.to_thread(release_notes, cur and cur.get("sha"), latest["sha"])
                recent = await asyncio.to_thread(recent_notes)
                android = await asyncio.to_thread(android_latest)
                status, out = 200, {"installed": cur, "latest": latest, "notes": notes, "recent": recent, "android": android,
                                    "can_update": web_dir_writable(), "repo": UPDATE_REPO}
            elif method != "POST":
                status, out = 405, {"error": "use POST"}
            else:
                status, out = await asyncio.to_thread(do_update)
        elif route.endswith("/whoami"):
            status, out = 200, {"ip": client_ip(headers), "x-forwarded-for": headers.get("x-forwarded-for"), "tailscale-user": headers.get("tailscale-user-login")}
        elif route.endswith("/health"):
            status, out = 200, {"ok": state["error"] is None, "logged_in": state["logged_in"], "hash": state["hash"],
                                "token_age_s": int(time.time() - state["minted"]) if state["minted"] else None,
                                "cached": len(cache), "error": state["error"], "spicy_key": bool(spicy_key()), "can_update": web_dir_writable(), "bridge": bool(bridge["pushers"]),
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
    data = raw if raw is not None else json.dumps(out).encode()
    reason = {200: "OK", 201: "Created", 202: "Accepted", 204: "No Content", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed", 501: "Not Implemented", 429: "Too Many Requests", 500: "Internal Server Error", 502: "Bad Gateway", 503: "Service Unavailable"}.get(status, "Error")
    extra = "".join("%s: %s\r\n" % (k, v) for k, v in xh.items())
    writer.write(("HTTP/1.1 %d %s\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n"
                  % (status, reason, extra, len(data))).encode() + data)
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

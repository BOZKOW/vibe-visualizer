# Notes for Claude Code

Working notes for continuing development of Vibe Visualizer on any machine. No secrets belong here: keys,
cookies, passwords and server addresses stay out of the repository.

## Layout
- `web/index.html` is the whole app (HTML, CSS and one big script). It is the single source; edit it directly.
- `web/sw.js`: service worker. It must never cache `/api/` responses (Spicy Lyrics terms).
- `server/helper.py`: the server helper (lyrics, Canvas, prefs, update, error log, Vibe Bridge relay, Spotify
  Web API relay `/spweb/`). On the server it runs as `canvas.py` in the `pulse-canvas` container.
- `server/nginx.conf`, `docker-compose.yml`: hosting. `config/` and `prefs/` are never committed.
- `bridge/vibe_bridge.py`: Vibe Bridge (system audio → analyser frames). GitHub Actions builds it on `bridge-v*` tags.

## Workflow the owner expects
- After every change: commit and push to GitHub without asking (author BOZKOW, noreply email
  `234052027+BOZKOW@users.noreply.github.com`).
- The web app reaches the server through **Settings → Help & about → Update now**, which downloads `web/` from
  GitHub. Changes to `server/helper.py` must be copied to the server and the helper container restarted.
- Check changes in headless Chrome (screenshots, small scripted tests), and keep Focus mode, duets, Spotify
  controls and resync working.

## Rules to keep
- AGPL-3.0. The lyrics animation is ported from Spicy Lyrics. Keep the "unofficial" notice and the credits.
- Spicy Lyrics API terms (developers.spicylyrics.org/terms): the secret key stays on the server; no downloader or
  export; attribution wherever lyrics show (provider; uploader and maker linked); cache 30 days at most (we use 7);
  only the owner's own devices may use the server. A Spicetify version must not use Spicy Lyrics (shared origin).
- Spotify hides `Retry-After` from browsers; requests go through the helper's `/spweb/` relay so it can be read,
  with a direct fallback when the server is down. Keep the polling pace adaptive.
- Errors never show as notifications: they replace the lyrics (serious ones until solved, one-offs 10 s with OK).

## Ideas discussed, not started
- A Spicetify version without Spicy Lyrics (Spotify lyrics, LRCLIB, NetEase, local files), built from shared code.
- A macOS Vibe Bridge (BlackHole or ScreenCaptureKit), and an Android client APK that opens the server.

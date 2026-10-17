<p align="center"><img src="web/logo.svg" width="96" alt="Vibe Visualizer logo"></p>

# Vibe Visualizer

A self-hosted music visualizer with synced lyrics. Play a local audio file, or follow what you're
playing on Spotify, and get the cover art, an audio-reactive bar visualizer, a live blurred-cover
background, and word-by-word synced lyrics with romanization and translation.

It is a personal project: an installable web app plus a small server helper, meant to run on your own home
server and be used from your own devices over [Tailscale](https://tailscale.com). No public IP address,
port forwarding or domain is needed.

## Features

- **Bar visualizer** that reacts to local files, or to Spotify through tab/screen audio sharing.
- **Live background:** the cover art, blurred, drifting and pulsing with the bass.
- **Synced lyrics:** word-by-word fill, instrumental-break dots, background vocals, duet alignment.
- **Romanization** (Japanese, Korean, Chinese, Cyrillic, …) and **translation** into any language,
  with a list of languages to leave untranslated. Toggle both from the top bar (`T` / `R`).
- **Spotify** "now playing" through the official Web API (PKCE login, no client secret).
- **Canvas videos** (optional, unofficial; see [Third-party services](#third-party-services)).
- **Per-device settings** saved on your server, keyed by each device's Tailscale address.
- First-run setup guide with links to the official Spotify and Tailscale documentation.

## Lyrics sources and attribution

Lyrics come from the [Spicy Lyrics API](https://developers.spicylyrics.org) when a key is configured,
otherwise from [LRCLIB](https://lrclib.net). You can also drop your own `.lrc` or `.ttml` file onto the page.

The Spicy Lyrics integration follows the
[Spicy Lyrics Terms](https://developers.spicylyrics.org/terms) and
[attribution rules](https://developers.spicylyrics.org/docs/attribution):

| Rule | How Vibe Visualizer handles it |
| --- | --- |
| Secret keys stay on a server you control | The `sl_sk_…` key lives only in `config/spicy_key` on your server. The helper calls the API; the browser never sees the key. |
| Use, don't redistribute | The helper's `/api/lyrics` route only answers requests from your own Tailscale network (Tailscale `100.64.0.0/10` / `fd7a:115c:a1e0::/48` addresses, forwarded by `tailscale serve`), and the web port is bound to `127.0.0.1`. It is not a public or shared endpoint. Lyrics are fetched one track at a time, only for the song being played. |
| Show attribution with the lyrics | While lyrics are on screen, a credit line under the cover always names the provider from the response's `source` field. For `spicy_lyrics` it also credits and links the uploader and the maker. For other sources it shows the provider only, and "source unknown" when no source is given. It is never hidden, and never shown empty. Songwriters are listed when the response includes them. |
| Read attribution from each response | Nothing is hardcoded: the credit is built from `source` and `UploadAttribution` of every response. |
| Cache for no more than 30 days | Responses are cached **in memory only, for at most 7 days** (misses for 6 hours), and are never written to disk. |
| Your own key, never shared | Each person who runs their own copy needs their own Spicy Lyrics application and key. Don't share yours, and don't commit `config/`. |
| No signed-out demo, no bulk use | The helper only uses your key, and only for the track being played. It never downloads, scrapes or mirrors the catalogue. |
| Clear the rights separately | API access grants no licence to the lyrics. This project is for private, personal use on your own devices; you are responsible for the rights to any lyrics you display. |

## How it fits together

```
your devices ──Tailscale HTTPS──▶ tailscale serve ──▶ 127.0.0.1:8095  nginx (web/index.html)
                                                                     └─ /api/* ─▶ helper (server/helper.py)
                                                                                   ├─ Spicy Lyrics API (secret key)
                                                                                   ├─ per-device settings (prefs/)
                                                                                   └─ Spotify Canvas lookup (optional)
```

## Setup

You need a Linux server with Docker and Tailscale, and your devices on the same tailnet.

1. **Clone and start**
   ```sh
   git clone https://github.com/<your-username>/vibe-visualizer.git
   cd vibe-visualizer
   mkdir -p config prefs && chmod 700 config prefs
   docker compose up -d --build
   ```
2. **Serve it over HTTPS on your tailnet.** Enable *MagicDNS* and *HTTPS Certificates* in the
   Tailscale admin console ([guide](https://tailscale.com/kb/1153/enabling-https)), then:
   ```sh
   sudo tailscale serve --bg --https=443 http://127.0.0.1:8095
   ```
   Open `https://<server-name>.<your-tailnet>.ts.net/` on any of your devices.
3. **Spotify (optional).** Create an app in the
   [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), add your
   `https://<server-name>.<your-tailnet>.ts.net/` address as a Redirect URI, and paste the Client ID
   in the setup guide. In development mode, add your account under *User Management*.
4. **Spicy Lyrics (optional).** Create an application in the
   [Spicy Lyrics developer dashboard](https://developers.spicylyrics.org/dashboard) and save its secret key
   on the server, without echoing it or putting it in your shell history:
   ```sh
   read -rs K && printf '%s' "$K" > config/spicy_key && chmod 600 config/spicy_key && unset K
   ```
   Then press **Settings → Lyrics → Test connection**. No restart is needed.

5. **Install it as an app (optional).** Open your `https://…ts.net/` address in Chrome, Edge, Brave or
   Chromium and choose *Install Vibe Visualizer* (the install icon in the address bar, or the ⋮ menu).
   It then runs in its own window with its own icon, like any other app. On Android use Chrome's
   *Install app*; on iPhone use Safari's *Share → Add to Home Screen*.

Everything else (theme, visualizer, lyrics, translation) is in the in-app Settings panel.

## Third-party services

Some features rely on services that do not offer an official API for this purpose. They are optional,
may stop working at any time, and you are responsible for whether using them is acceptable to you:

- **Translation and romanization** use Google Translate's free web endpoint (unofficial). Lyric lines are sent to Google.
- **Spotify Canvas videos** are looked up by a headless Chromium that opens the Spotify web player with
  *your own* `sp_dc` cookie (`config/sp_dc`). This is not an official Spotify API and is against
  Spotify's terms of use. Leave `config/sp_dc` absent to keep it disabled.
- **Cover art** for local files is looked up through the iTunes Search API and MusicBrainz / Cover Art Archive.

## Privacy

- Your keys and the optional Spotify cookie stay in `config/` on your server, which is excluded from git.
- Per-device settings (including the Spotify login of that device) are stored in `prefs/` on your server.
  *Settings → Help → Forget this device* deletes them.
- Nothing is sent anywhere except the services listed above, and only when you use those features.

## Keyboard shortcuts

`Space` play/pause · `←`/`→` seek · `↑`/`↓` volume · `M` mute · `F` fullscreen ·
`1`/`2`/`3` visualizer / lyrics / both · `T` translate · `R` romanize

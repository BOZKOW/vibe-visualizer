<p align="center"><img src="web/logo.svg" width="96" alt="Vibe Visualizer logo"></p>

# Vibe Visualizer

A self-hosted music visualizer with synced lyrics. Play a local audio file, or follow what you're
playing on Spotify, and get the cover art, an audio-reactive bar visualizer, a live blurred-cover
background, and word-by-word synced lyrics with romanization and translation.

It is a personal project: an installable web app plus a small server helper, meant to run on your own home
server and be used from your own devices over [Tailscale](https://tailscale.com). No public IP address,
port forwarding or domain is needed.

## Features

- **Bar visualizer** that reacts to local files, or to Spotify through your computer's sound
  (a "Monitor of …" input on Linux, or tab/screen audio sharing).
- **Live background:** the cover art, blurred, drifting at the speed and tempo of the music and pulsing with the bass.
- **Cover that moves with the beat**, and a smooth layout change when a Canvas video starts or stops.
- **Synced lyrics** animated like Spicy Lyrics (its animation code, ported): each word fills, swells, lifts and glows on springs as it's
  sung, long held words wave letter by letter, and lines further away blur. Background vocals are animated on their own timing too. Also duet alignment,
  and instrumental-break dots that hop in time
  until the next line. Click any line to jump there.
- **Several lyrics providers** (Spicy Lyrics, Spotify, LRCLIB, NetEase Cloud Music), each one switchable, in the order you choose.
- **Romanization** (Japanese, Korean, Chinese, Cyrillic, …) and **translation** into any language,
  with a list of languages to leave untranslated. Toggle both from the top bar (`T` / `R`).
- **Spotify** "now playing" through the official Web API (PKCE login, no client secret), with previous / play-pause /
  next and seeking (needs Spotify Premium).
- **Ping meter** showing the response time and status of every service the app uses.
- **Canvas videos** (optional, unofficial; see [Third-party services](#third-party-services)).
- **Per-device settings** saved on your server, keyed by each device's Tailscale address.
- First-run setup guide with links to the official Spotify and Tailscale documentation.

## Lyrics sources and attribution

Lyrics are looked up in the order set under *Settings → Lyrics*, and each provider can be switched off:

1. [Spicy Lyrics API](https://developers.spicylyrics.org) (needs your own key on the server)
2. Spotify's own lyrics (unofficial, needs `config/sp_dc`; see [Third-party services](#third-party-services))
3. [LRCLIB](https://lrclib.net)
4. NetEase Cloud Music (unofficial)

You can also drop your own `.lrc` or `.ttml` file onto the page.

The Spicy Lyrics integration follows the
[Spicy Lyrics Terms](https://developers.spicylyrics.org/terms) and
[attribution rules](https://developers.spicylyrics.org/docs/attribution):

| Rule | How Vibe Visualizer handles it |
| --- | --- |
| Secret keys stay on a server you control | The `sl_sk_…` key lives only in `config/spicy_key` on your server. The helper calls the API; the browser never sees the key. |
| Use, don't redistribute | The helper's `/api/lyrics` route only answers requests from your own Tailscale network (Tailscale `100.64.0.0/10` / `fd7a:115c:a1e0::/48` addresses, forwarded by `tailscale serve`), and the web port is bound to `127.0.0.1`. It is not a public or shared endpoint. Lyrics are fetched one track at a time, only for the song being played. |
| Show attribution with the lyrics | While lyrics are on screen, a credit in the bottom-left corner always names the provider from the response's `source` field. For `spicy_lyrics` it also credits and links the uploader and the maker. For other sources it shows the provider only, and "source unknown" when no source is given. It is never hidden, and never shown empty. It is laid out the way Spicy Lyrics shows it (provider, then maker and uploader with their avatars), and *Settings → Lyrics credits* places it in the corner or right under the lyrics. There is no option to turn it off. Songwriters are listed when the response includes them. |
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
                                                                                   ├─ Spotify lyrics + Canvas (optional)
                                                                                   └─ NetEase lyrics lookup
```

## Setup

You need a computer that stays on, with Docker and Tailscale, and your devices on the same tailnet.
The steps below are for Linux; see [Running the server on Windows](#running-the-server-on-windows) for Windows.

1. **Clone and start**
   ```sh
   git clone https://github.com/BOZKOW/vibe-visualizer.git
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

### Running the server on Windows

The same setup works on Windows 10/11 with [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/)
and [Tailscale for Windows](https://tailscale.com/download/windows). In PowerShell:

```powershell
git clone https://github.com/BOZKOW/vibe-visualizer.git
cd vibe-visualizer
mkdir config, prefs
docker compose up -d --build
```

Then, in a PowerShell window opened with *Run as administrator*:

```powershell
tailscale serve --bg --https=443 http://127.0.0.1:8095
```

To save the Spicy Lyrics key without showing it on screen:

```powershell
$k = Read-Host "Spicy Lyrics key" -AsSecureString
[Net.NetworkCredential]::new('', $k).Password | Set-Content -NoNewline config\spicy_key
Remove-Variable k
```

Docker Desktop has to be running for the app to be up; turn on *Start Docker Desktop when you sign in* in its
settings, and set Windows not to sleep while you use it.

## Using it on Windows

- **Install as an app:** open your `https://…ts.net/` address in Edge or Chrome and choose *Install Vibe Visualizer*
  (Edge: *⋯ → Apps → Install this site as an app*). It gets its own window, Start menu entry and taskbar icon.
- **Bars for the Spotify app:** press the microphone button, choose *Share all computer sound*, pick
  **Entire screen** and turn on **Also share system audio**. That carries everything Windows plays, the Spotify
  app included. If *Stereo Mix* is enabled in the Windows sound settings, it also shows up in the device list.
- **Media keys and the Windows media flyout** control local files and show their title and cover.

## Third-party services

Some features rely on services that do not offer an official API for this purpose. They are optional,
may stop working at any time, and you are responsible for whether using them is acceptable to you:

- **Translation and romanization** use Google Translate's free web endpoint (unofficial). Lyric lines are sent to Google.
- **Spotify Canvas videos and Spotify's lyrics** are looked up by a headless Chromium that opens the Spotify web
  player with *your own* `sp_dc` cookie (`config/sp_dc`). This is not an official Spotify API and is against
  Spotify's terms of use. Leave `config/sp_dc` absent to keep it disabled.
- **NetEase Cloud Music lyrics** use NetEase's public web endpoints (unofficial).
- **Cover art** for local files is looked up through the iTunes Search API and MusicBrainz / Cover Art Archive.

## Privacy

- Your keys and the optional Spotify cookie stay in `config/` on your server, which is excluded from git.
- Per-device settings (including the Spotify login of that device) are stored in `prefs/` on your server.
  *Settings → Help → Forget this device* deletes them.
- Nothing is sent anywhere except the services listed above, and only when you use those features.

## Keyboard shortcuts

`Space` play/pause · `←`/`→` seek · `↑`/`↓` volume · `M` mute · `F` fullscreen ·
`1`/`2`/`3` visualizer / lyrics / both · `T` translate · `R` romanize · `]`/`[` lyrics earlier / later · click a lyric line to jump to it

## Credits

Made by [BOZKOW](https://github.com/BOZKOW), built together with [Claude Code](https://claude.com/claude-code) (Anthropic's Claude Opus).

The lyrics animation (word and letter springs, fill gradient, line dimming and blur) and the credits layout are
ported from [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics) by Spikerko and the Spicy Lyrics contributors,
licensed under the GNU AGPL-3.0. Its spring model is a port of [spr](https://github.com/Fraktality/spr) by Fraktality (MIT).

## License

Vibe Visualizer is free software, licensed under the [GNU Affero General Public License v3.0](LICENSE)
(AGPL-3.0), because it includes code ported from Spicy Lyrics. If you run a modified version for other people
over a network, you must offer them its source code; the app links to it under *Settings → Help*.

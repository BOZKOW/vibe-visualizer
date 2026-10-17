<p align="center"><img src="web/logo.svg" width="96" alt="Vibe Visualizer logo"></p>

# Vibe Visualizer

A self-hosted music visualizer with synced lyrics. Play a local audio file, or follow what you're playing on
Spotify, and get the cover art, an audio-reactive bar visualizer, a live background made from the cover, and
word-by-word synced lyrics, animated the way [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics) animates them,
with romanization and translation.

It's a personal project: an installable web app plus a small server helper, meant to run on your own home server
and be used from your own devices over [Tailscale](https://tailscale.com). No public IP address, port forwarding or
domain is needed.

> **Unofficial.** Vibe Visualizer is not made by, endorsed by or affiliated with Spicy Lyrics. It uses the
> [Spicy Lyrics API](https://developers.spicylyrics.org) with your own key, and its lyrics animation is ported from
> the Spicy Lyrics source code (see [Credits](#credits) and [License](#license)).

## Contents

- [Features](#features)
- [Spicy Lyrics integration](#spicy-lyrics-integration)
- [How it fits together](#how-it-fits-together)
- [Setup](#setup)
- [Updating](#updating)
- [Using it on Windows](#using-it-on-windows)
- [Third-party services](#third-party-services)
- [Privacy](#privacy)
- [Keyboard shortcuts](#keyboard-shortcuts)
- [Credits](#credits) · [License](#license)

## Features

**Lyrics**
- Word-by-word synced lyrics with the Spicy Lyrics animation: each word fills, swells, lifts and glows as it's sung,
  long held words wave letter by letter, and every line but the one being sung is blurred.
- Background vocals, duets (each singer on their own side) and overlapping lines, each on its own timing.
- Instrumental breaks show Spicy Lyrics' three dots, which swell one after another and pop away just before the
  singing starts.
- Line-synced and untimed lyrics too: untimed lyrics scroll slowly with the song, from its start to its end.
- Four lyrics sources (Spicy Lyrics, Spotify, LRCLIB, NetEase Cloud Music), each switchable, in the order you choose.
- Romanization (Japanese, Korean, Chinese, Russian and other Cyrillic, Greek) and translation into any language, with
  a list of languages to leave untranslated. Both fill in time with the singing. Toggle them with `R` and `T`.
- Timing that stays right: the lyrics resync with Spotify every 2 seconds, a resync button in the top bar,
  `]` / `[` to move them earlier or later, and an automatic check that stretches lyrics found for another version of a
  song (radio edit, sped up) to fit the one playing.
- Click any line to jump there.
- **Focus mode** (*Settings → Experiments*): only the line being sung, sharp in the middle. The next line flies in
  from the bottom 200 ms before it's sung, finished lines fly up and disappear, and lines sung together stack.

**Visuals**
- Bar visualizer that reacts to local files, or to Spotify through your computer's sound (a "Monitor of …" input on
  Linux, a screen share with system audio on Windows, or a browser tab).
- Live background made from the cover art, drifting at the music's pace. Or, as in Spicy Lyrics: the artist's photo,
  the still cover art, a color from the cover, or none.
- Spotify Canvas videos (optional, see [Third-party services](#third-party-services)), with a smooth layout change when
  one starts or stops.
- A frosted-glass design with Liquid Glass behaviour: controls swell under the pointer, squish when pressed and spring
  back.

**App**
- Spotify "now playing" through the official Web API (PKCE login, no client secret), with previous, play/pause, next
  and seeking (Spotify Premium).
- Settings in their own window, with categories and a search box.
- A home screen (click the logo), a first-run setup guide, and a ping meter for every service the app uses.
- Installable as an app on computers and phones.
- Per-device settings saved on your server, keyed by each device's Tailscale address.
- Updates from inside the app, with release notes, and an error log on your server.

## Spicy Lyrics integration

Vibe Visualizer is built around Spicy Lyrics: its API for the lyrics, and its open-source code for how they look and
move.

**Lyrics from the Spicy Lyrics API**
- Word-synced (syllable), line-synced and static lyrics, read straight from each response.
- Background vocals and duet lines (`OppositeAligned`) shown the way Spicy Lyrics shows them.
- Romanization and translation that come with the lyrics are used first; anything missing is filled in syllable by
  syllable, as Spicy Lyrics does it.
- Credits read from every response: the provider, the maker and the uploader with their avatars, linked to their
  Spicy Lyrics profiles, plus the songwriters.

**Ported from the Spicy Lyrics source code** (AGPL-3.0)
- The word, letter and line animation: the same spring model, curves, letter groups for held words, fill gradient,
  line dimming and blur.
- The instrumental-break dots.
- The credits layout.
- Romanization per syllable with the same libraries.

**Spicy Lyrics' settings, in *Settings → Lyrics***
- Simple lyrics mode, minimal lyrics mode, highlight on hover, duet line padding, credits follow the last singer.
- Smooth scrolling, early scroll and its time, seek fade-in compensation, timing offset.
- No lyrics notice, volume slider, limit frame rate, use system font.
- Background style (dynamic, artist image, cover art, color, none) and image blur, under *Appearance*.
- Lyrics cache: clear this song's cache, clear stored lyrics, reset this song's lyrics.

### Following the Spicy Lyrics rules

The integration follows the [Spicy Lyrics Terms](https://developers.spicylyrics.org/terms) and
[attribution rules](https://developers.spicylyrics.org/docs/attribution):

| Rule | How Vibe Visualizer handles it |
| --- | --- |
| Secret keys stay on a server you control | The `sl_sk_…` key lives only in `config/spicy_key` on your server. The helper calls the API; the browser never sees the key. |
| Use, don't redistribute | The helper's lyrics route only answers your own Tailscale devices (`100.64.0.0/10` and `fd7a:115c:a1e0::/48`, forwarded by `tailscale serve`), and the web port is bound to `127.0.0.1`. It isn't a public or shared endpoint. Lyrics are fetched one song at a time, only for the song being played. |
| Show attribution with the lyrics | Whenever lyrics are on screen, a credit names the provider from the response's `source`. For `spicy_lyrics` it also credits and links the maker and the uploader. It's never hidden or empty; *Settings → Lyrics → Lyrics credits* only chooses where it goes (bottom-left corner or under the lyrics). |
| Read attribution from each response | Nothing is hardcoded: every credit is built from that response's `source` and `UploadAttribution`. |
| Cache for no more than 30 days | The server helper keeps responses in memory only, for at most 7 days (misses for 6 hours). Each device keeps the lyrics of songs it played for at most 7 days ("no lyrics" for 1 day) and deletes expired ones on start. |
| Your own key, never shared | Everyone who runs their own copy needs their own Spicy Lyrics application and key. Don't share yours, and don't commit `config/`. |
| No branding as Spicy Lyrics | The app is called Vibe Visualizer and says plainly that it's unofficial. It says it's built on Spicy Lyrics, which the terms allow. |
| No bulk use | Only the song being played is looked up. Nothing is downloaded, scraped or mirrored. |
| Clear the rights separately | API access grants no licence to the lyrics themselves. This project is for private use on your own devices; you're responsible for the rights to any lyrics you display. |

## How it fits together

```
your devices ──Tailscale HTTPS──▶ tailscale serve ──▶ 127.0.0.1:8095  nginx (web/index.html)
                                                                     └─ /api/* ─▶ helper (server/helper.py)
                                                                                   ├─ Spicy Lyrics API (secret key)
                                                                                   ├─ per-device settings and error log (prefs/)
                                                                                   ├─ Spotify lyrics and Canvas (optional)
                                                                                   ├─ NetEase lyrics lookup
                                                                                   └─ updates from GitHub (web/)
```

## Setup

You need a computer that stays on, with Docker and Tailscale, and your devices on the same tailnet. The steps below
are for Linux; see [Running the server on Windows](#running-the-server-on-windows) for Windows.

1. **Clone and start**
   ```sh
   git clone https://github.com/BOZKOW/vibe-visualizer.git
   cd vibe-visualizer
   mkdir -p config prefs && chmod 700 config prefs
   docker compose up -d --build
   ```
2. **Serve it over HTTPS on your tailnet.** Turn on *MagicDNS* and *HTTPS Certificates* in the Tailscale admin
   console ([guide](https://tailscale.com/kb/1153/enabling-https)), then:
   ```sh
   sudo tailscale serve --bg --https=443 http://127.0.0.1:8095
   ```
   Open `https://<server-name>.<your-tailnet>.ts.net/` on any of your devices.
3. **Spotify (optional).** Create an app in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard),
   add your `https://<server-name>.<your-tailnet>.ts.net/` address as a Redirect URI, and paste the Client ID into the
   setup guide. In development mode, add your account under *User Management*.
4. **Spicy Lyrics (optional, recommended).** Create an application in the
   [Spicy Lyrics developer dashboard](https://developers.spicylyrics.org/dashboard) and save its secret key on the
   server, without showing it on screen or keeping it in your shell history:
   ```sh
   read -rs K && printf '%s' "$K" > config/spicy_key && chmod 600 config/spicy_key && unset K
   ```
   Then press *Settings → Lyrics → Test connection*. No restart is needed.
5. **Install it as an app (optional).** Open your `https://…ts.net/` address in Chrome, Edge or Brave and choose
   *Install Vibe Visualizer* (the install icon in the address bar, or the ⋮ menu). It then runs in its own window with
   its own icon. On Android use Chrome's *Add to Home screen*; on iPhone, Safari's *Share → Add to Home Screen*.

Everything else is in the app's Settings window.

### Running the server on Windows

The same setup works on Windows 10 and 11 with
[Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) and
[Tailscale for Windows](https://tailscale.com/download/windows). In PowerShell:

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

Docker Desktop has to be running for the app to be up: turn on *Start Docker Desktop when you sign in* in its
settings, and set Windows not to sleep while you use it.

## Updating

When a new version is out, a notification in the app lists what's new, with **Later** and **Update now**. You can
also check yourself under *Settings → Help & about*. **Update now** downloads the new web app files from GitHub onto
your server and reloads the app; your `config/` and `prefs/` stay as they are. Afterwards a "What's new" card shows
what changed. The release notes come from the commit messages.

The server helper does the download, so it needs the web folder mounted at `/www`, which `docker-compose.yml`
already does. Changes to the helper itself still need `git pull && docker compose up -d --build`.

**Error log.** Errors the app runs into show up as a notification and are saved on your server in
`prefs/errors.jsonl` (the last 300, never sent anywhere else), so they can be looked at and fixed.
*Settings → Help & about → Error log* shows and clears them.

## Using it on Windows

- **Install as an app:** open your `https://…ts.net/` address in Edge or Chrome and choose *Install Vibe Visualizer*
  (in Edge: *⋯ → Apps → Install this site as an app*). It gets its own window, Start menu entry and taskbar icon.
- **Bars for the Spotify app:** press the microphone button, choose *Share all computer sound*, pick
  **Entire screen** and turn on **Also share system audio**. That carries everything Windows plays, including the
  Spotify app. If *Stereo Mix* is turned on in the Windows sound settings, it shows up in the device list too.
- **Media keys and the Windows media flyout** control local files and show their title and cover.

## Third-party services

Some features rely on services that don't offer an official API for this. They're optional, may stop working at any
time, and you're responsible for whether using them is acceptable to you:

- **Romanization** uses the romanization that comes with the lyrics first. The rest is romanized syllable by syllable
  in your browser with [kuroshiro](https://github.com/hexenq/kuroshiro) and kuromoji (Japanese),
  [pinyin](https://github.com/hotoo/pinyin) (Chinese), [aromanize](https://github.com/fujaru/aromanize-js) (Korean) and
  [cyrillic-romanization](https://www.npmjs.com/package/cyrillic-romanization) (Cyrillic), all MIT-licensed and loaded
  on demand from the jsDelivr CDN. The Japanese dictionary is about 17 MB and is downloaded once. Google is the
  fallback.
- **Translation** uses Google Translate's free web endpoint (unofficial), so lyric lines are sent to Google.
- **Spotify Canvas videos and Spotify's own lyrics** are looked up by a headless Chromium that opens the Spotify web
  player with *your own* `sp_dc` cookie (`config/sp_dc`). This isn't an official Spotify API and is against Spotify's
  terms of use. Leave `config/sp_dc` out to keep it off.
- **NetEase Cloud Music lyrics** use NetEase's public web endpoints (unofficial).
- **Cover art** for local files is looked up through the iTunes Search API and MusicBrainz / Cover Art Archive.

## Privacy

- Your keys and the optional Spotify cookie stay in `config/` on your server, which is excluded from git.
- Per-device settings (including that device's Spotify login) and the error log are stored in `prefs/` on your server.
- Lyrics of songs you played are kept in your browser for up to 7 days, so they don't have to be fetched again.
- *Settings → Help & about → Forget this device* deletes this device's settings and saved lyrics.
- Nothing is sent anywhere except the services listed above, and only when you use those features.

## Keyboard shortcuts

| Key | Action |
| --- | --- |
| `Space` | Play / pause |
| `←` `→` | Seek 5 seconds |
| `↑` `↓` | Volume |
| `N` `P` | Next / previous |
| `M` | Mute |
| `F` | Fullscreen |
| `1` `2` `3` | Visualizer / lyrics / both |
| `T` | Translate lyrics |
| `R` | Romanize lyrics |
| `]` `[` | Lyrics earlier / later |
| `Esc` | Close Settings or the home screen |

Click any lyric line to jump to it.

## Credits

Made by [BOZKOW](https://github.com/BOZKOW), built together with [Claude Code](https://claude.com/claude-code)
(Anthropic's Claude Opus).

The lyrics animation (word and letter springs, fill gradient, line dimming and blur), the instrumental-break dots,
the credits layout and the way romanization is done are ported from
[Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics) by Spikerko and the Spicy Lyrics contributors, licensed
under the GNU AGPL-3.0. Its spring model is itself a port of [spr](https://github.com/Fraktality/spr) by Fraktality
(MIT). **Vibe Visualizer is unofficial: it isn't made by, endorsed by or affiliated with Spicy Lyrics.**

## License

Vibe Visualizer is free software, licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0),
because it includes code ported from Spicy Lyrics. If you run a modified version for other people over a network,
you must offer them its source code; the app links to it under *Settings → Help & about*.

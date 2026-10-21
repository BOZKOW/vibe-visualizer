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

## Where it runs

Your server runs Vibe Visualizer once; every device opens it from there (over Tailscale), so settings, lyrics and
updates are shared.

| On | Use | Get it |
| --- | --- | --- |
| Any computer or phone | The web app in a browser, installable as an app (Chrome, Edge, Safari) | Open `https://NAME.ts.net` |
| Android phone or tablet | [Android app](#android-app): a native app, using your server for lyrics and Canvas | `vibe-visualizer-android.apk` on the [Releases page](../../releases) (beta) |
| Mac | [macOS app](#macos-app): your server in its own window | `Vibe-Visualizer-macOS.dmg` on the [Releases page](../../releases) (beta) |
| Windows and Linux (the computer that plays the music) | [Vibe Bridge](#vibe-bridge-bars-from-the-spotify-app): the bars follow everything it plays | `vibe-bridge-…` on the [Releases page](../../releases) |

The macOS app only shows your server: the page itself updates with the server (*Settings → Help & about → Update
now*), and the app tells you there when a new version of the app itself is out.

On Android there's a native [Android app](#android-app) (beta): `vibe-visualizer-android.apk` on the Releases page.

## Contents

- [Features](#features)
- [Spicy Lyrics integration](#spicy-lyrics-integration)
- [How it fits together](#how-it-fits-together)
- [Setup](#setup)
- [Vibe Bridge: bars from the Spotify app](#vibe-bridge-bars-from-the-spotify-app)
- [Updating](#updating)
- [Using it on Linux](#using-it-on-linux)
- [Using it on Windows](#using-it-on-windows)
- [Android app](#android-app) (beta)
- [macOS app](#macos-app)
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
  Lyrics without timing keep scrolling slowly, as in Spicy Lyrics.

**Visuals**
- Bar visualizer that reacts to local files, or to everything your computer plays (the Spotify app too) through the
  small [Vibe Bridge](#vibe-bridge-bars-from-the-spotify-app) app, a "Monitor of …" input on Linux, a screen share
  with system audio on Windows, or a browser tab.
- Live background made from the cover art, drifting at the music's pace. Or, as in Spicy Lyrics: the artist's photo,
  the still cover art, a color from the cover, or none.
- Spotify Canvas videos (optional, see [Third-party services](#third-party-services)), with a smooth layout change when
  one starts or stops.
- A frosted-glass design with Liquid Glass behaviour: controls swell under the pointer, squish when pressed and spring
  back.

**App**
- Spotify "now playing" through the official Web API (PKCE login, no client secret), with previous, play/pause, next,
  seeking, shuffle, repeat, Spotify's own volume and a device picker to choose where it plays (Spotify Premium), and a
  heart to save the song to your Liked Songs. Logged in before these were added? Reconnect Spotify once to allow them.
- Your own lyrics files: add TTML or LRC files (Settings → Lyrics → Add lyrics files, or drop them on the page). Named
  `Artist - Title.ttml`, or with the Spotify track id in the name, they're used whenever that song plays, with word timing,
  duet sides, background vocals and songwriter credits. A file dropped while a song plays is kept for that song.
- Desync detection: with real sound (Vibe Bridge, audio capture or a local file) it listens to the singing and, if the
  lyrics run ahead or behind, offers to fix them for that song (or fixes them by itself; Settings → Lyrics).
- Song changes blend smoothly: the old cover melts into the new one and the colors fade over.
- Two designs (Settings → Appearance): Glass, with Liquid Glass motion, or Classic, the original look. The top and
  bottom bars can hide when the mouse is still or stay visible.
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
- No lyrics notice, volume slider, limit frame rate (up to 240 fps), lyrics font (Vibe Visualizer, a Spicy Lyrics look, or
  your system font).
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
| Cache for no more than 30 days | The server helper keeps responses in memory only, for at most 7 days (misses for 6 hours), and drops out-of-date ones. Each device keeps the lyrics of songs it played for at most 7 days ("no lyrics" for 1 day) and deletes expired ones on start. |
| Your own key, never shared | Everyone who runs their own copy needs their own Spicy Lyrics application and key. Don't share yours, and don't commit `config/`. |
| No downloader, no export | The app never offers lyrics as a file to save, and has no way to export them. "Your lyrics files" only reads TTML/LRC files you add yourself; it never stores Spicy Lyrics answers as files. |
| Don't work around limits | One key, one application. The helper's cache only saves repeat requests for songs you play; nothing rotates keys, fans out requests or bypasses rate limits. |
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

The macOS app opens the same address as a browser does and only adds what a browser can't: logging in to Spotify
through your browser and a window of its own.

## Setup

You need a computer that stays on, with Docker and Tailscale, and your devices on the same tailnet. The steps below
are for Linux and assume Docker and Tailscale are already installed; if they aren't, follow
[Running the server on Linux, step by step](#running-the-server-on-linux-step-by-step). For Windows, see
[Running the server on Windows](#running-the-server-on-windows).

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
5. **Spotify Canvas and Spotify's own lyrics (optional).** Canvas is the short looping video Spotify shows for many
   songs. The server gets it, and Spotify's own lyrics, by opening Spotify's web player in a hidden browser logged in
   with your account. It needs your Spotify login cookie, `sp_dc`:
   1. In Chrome, Edge or Brave on any computer, go to [open.spotify.com](https://open.spotify.com) and log in.
   2. Press **F12** to open the developer tools, then go to **Application** → **Cookies** → `https://open.spotify.com`
      (in Firefox: **Storage** → **Cookies**).
   3. Find the row named **`sp_dc`** and copy its **Value** (a long string of letters and numbers).
   4. On the server, in the `vibe-visualizer` folder, save it without showing it on screen, and restart the helper:
      ```sh
      read -rs C && printf '%s' "$C" > config/sp_dc && chmod 600 config/sp_dc && unset C
      docker compose restart helper
      ```
   5. Check it: in the app, open the **ping meter** (the `ms` button in the top bar). *Spotify Canvas* should no
      longer say "Canvas off". Play a song that has a Canvas on your phone's Spotify app; it now plays behind the cover.
      You can switch it off or on in **Settings → Spotify & Canvas → Canvas**.

   Keep the cookie private: it works like your Spotify password. It lasts about a year; if you log out of Spotify on
   the web ("Sign out everywhere" included), copy a new one. This uses Spotify's web player in a way Spotify doesn't
   officially offer (see [Third-party services](#third-party-services)); leave `config/sp_dc` out to keep it off.
6. **Install it as an app (optional).** Open your `https://…ts.net/` address in Chrome, Edge or Brave and choose
   *Install Vibe Visualizer* (the install icon in the address bar, or the ⋮ menu). It then runs in its own window with
   its own icon. On Android use Chrome's *Add to Home screen*; on iPhone, Safari's *Share → Add to Home Screen*.

Everything else is in the app's Settings window.

### Running the server on Linux, step by step

These commands are for Ubuntu and Debian; other distributions are noted where they differ.

1. **Install Docker** with Docker's official script, then let your user run it without `sudo`:
   ```sh
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker $USER
   sudo systemctl enable --now docker
   ```
   Log out and back in (or reboot) so the `docker` group applies. On Fedora use `sudo dnf install docker`, on Arch
   `sudo pacman -S docker docker-compose`, then the last two commands above.
   ([Docker's install guide](https://docs.docker.com/engine/install/))
2. **Install Tailscale** with Tailscale's official script and sign in:
   ```sh
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up
   ```
   Open the link it prints and sign in with the same account as your other devices.
   ([Tailscale's Linux guide](https://tailscale.com/kb/1031/install-linux))
3. **Install git** if it's missing: `sudo apt install git` (Fedora: `sudo dnf install git`, Arch: `sudo pacman -S git`).
4. **Follow the [Setup](#setup) steps above**: clone, `docker compose up -d --build`, `tailscale serve`, then
   Spotify and Spicy Lyrics.
5. **Check that it's running:**
   ```sh
   docker compose ps                # both services should say "running"
   docker compose logs -f helper    # the helper's log; Ctrl+C to stop watching
   ```
6. **Keep the server awake.** On a laptop or desktop used as a server, stop it from sleeping:
   ```sh
   sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
   ```
   Docker starts Vibe Visualizer again by itself after a reboot.

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

For Spotify Canvas (step 5 of [Setup](#setup)), save the `sp_dc` cookie the same way and restart the helper:

```powershell
$c = Read-Host "sp_dc cookie" -AsSecureString
[Net.NetworkCredential]::new('', $c).Password | Set-Content -NoNewline config\sp_dc
Remove-Variable c
docker compose restart helper
```

Docker Desktop has to be running for the app to be up: turn on *Start Docker Desktop when you sign in* in its
settings, and set Windows not to sleep while you use it.

## Vibe Bridge: bars from the Spotify app

Spotify doesn't share its sound with other programs, so the browser can't hear the Spotify app by itself.
**Vibe Bridge** is a small program for the computer that plays your music (Linux or Windows). It listens to
everything that computer plays, the Spotify app included, and sends the bars' data to Vibe Visualizer on the same
computer, with no sharing prompt and no device to pick. It only accepts connections from that same computer, and no
sound or data leaves it.

It can also send the same data **through your Vibe Visualizer server**, which every page of the app can always
reach. That works even if your browser blocks connections to programs on the same computer, and your other devices
(a phone, a tablet) then get real bars from the computer playing the music. Start it once with your server's
address and it remembers it:

```sh
vibe-bridge --server https://<server-name>.<your-tailnet>.ts.net
```

(`vibe-bridge --server off` stops that again.) *Settings → Visualizer → Vibe Bridge* shows the exact command for
your server.

Download it from the [Releases page](https://github.com/BOZKOW/vibe-visualizer/releases) (look for "Vibe Bridge").
Vibe Visualizer finds it by itself; *Settings → Visualizer → Vibe Bridge* shows whether it's connected and which
output it's listening to, and the sound button in the top bar gets a green ring.

**Windows**
1. Download `vibe-bridge-windows.exe` and run it. Windows may say *Windows protected your PC*, because the program
   isn't signed: choose *More info → Run anyway*. To also send through your server, run it once from a terminal:
   `vibe-bridge-windows.exe --server https://<server-name>.<your-tailnet>.ts.net` (it remembers it).
2. Leave its window open (minimize it) while you listen.
3. To start it automatically: press `Win + R`, type `shell:startup`, and put a shortcut to `vibe-bridge-windows.exe`
   in the folder that opens.

**Linux**
1. Download `vibe-bridge-linux-x86_64`, then make it a program you can run:
   ```sh
   mkdir -p ~/.local/bin
   mv ~/Downloads/vibe-bridge-linux-x86_64 ~/.local/bin/vibe-bridge
   chmod +x ~/.local/bin/vibe-bridge
   vibe-bridge --server https://<server-name>.<your-tailnet>.ts.net
   ```
   Run it from a terminal the first time, so you can see what it prints ("listening to …" and
   "sending through the server …" mean it works). Double-clicking a program on Linux often does nothing.
   It needs PipeWire (with its PulseAudio layer) or PulseAudio, which almost every desktop has.
2. To start it automatically when you log in, use the service file from the `bridge` folder:
   ```sh
   mkdir -p ~/.config/systemd/user
   cp bridge/vibe-bridge.service ~/.config/systemd/user/
   systemctl --user daemon-reload && systemctl --user enable --now vibe-bridge
   ```

**Good to know**
- `vibe-bridge --list` lists your outputs, and `vibe-bridge --device "Headphones"` follows a specific one instead of
  the default. By default it follows whichever output is the default, also after you switch.
- Your browser may ask once whether Vibe Visualizer can *access other apps and services on this device*. Allow it;
  that's the connection to the bridge.
- Local audio files you play in Vibe Visualizer itself still use the browser's own sound.
- From source instead: `pip install -r bridge/requirements.txt` and `python bridge/vibe_bridge.py`.

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

## Using it on Linux

- **Install as an app:** open your `https://…ts.net/` address in Chrome, Chromium, Edge or Brave and choose
  *Install Vibe Visualizer* (the install icon in the address bar, or *⋮ → Cast, save and share → Install page as app*).
  It appears in your app menu with its own icon. Firefox can't install web apps; use it as a normal tab instead.
- **Bars for the Spotify app:** press the microphone button and pick a **🔊 Monitor of …** device for the speakers or
  headphones you listen on, then **Start**. A monitor carries everything your computer plays, the Spotify desktop app
  included (also the Snap and Flatpak versions). Allow microphone access when asked; that's only how browsers list
  these devices.
- **No "Monitor of …" device in the list?** Check that your system runs PipeWire (most current distributions) or
  PulseAudio, and that monitors exist:
  ```sh
  pactl list short sources | grep monitor
  ```
  If that prints nothing, install the PipeWire PulseAudio layer (`sudo apt install pipewire-pulse`, Fedora:
  `pipewire-pulseaudio`, Arch: `pipewire-pulse`), log out and back in, and try again.
- **Firefox** can't share sound this way, so the bars only imitate the music there. Use a Chromium-based browser for
  the real thing.
- **Media keys** control local files; with Spotify, your keys go to the Spotify app as usual.
- **The bars stay flat:** make sure something is playing on the output whose monitor you picked; if you switched
  headphones or speakers, pick the matching monitor again.

## Using it on Windows

- **Install as an app:** open your `https://…ts.net/` address in Edge or Chrome and choose *Install Vibe Visualizer*
  (in Edge: *⋯ → Apps → Install this site as an app*). It gets its own window, Start menu entry and taskbar icon.
- **Bars for the Spotify app:** press the microphone button, choose *Share all computer sound*, pick
  **Entire screen** and turn on **Also share system audio**. That carries everything Windows plays, including the
  Spotify app. If *Stereo Mix* is turned on in the Windows sound settings, it shows up in the device list too.
- **Media keys and the Windows media flyout** control local files and show their title and cover.

## Android app

**Vibe Visualizer for Android** (Android 12 or newer, beta) is a native app (Kotlin, Jetpack Compose, Material 3
Expressive), not the web page in a browser. It has the web app's player, lyrics, visualizer, settings and Spotify
features, and uses your server (over Tailscale) only for what needs it: Spicy Lyrics, Spotify's own lyrics and Canvas,
and NetEase. Without a server, LRCLIB, AMLL and your own lyrics files still work.

1. Download `vibe-visualizer-android.apk` from the newest *Vibe Visualizer for Android* release on the
   [Releases page](../../releases) on the phone and open it (allow installing from your browser when Android asks).
   If you still have the old app (0.2 to 0.7 and its 1.0.0, marked "old, out of support"), uninstall it first.
2. The setup guide asks for your server's address (`NAME.your-tailnet.ts.net`). Keep Tailscale connected on the
   phone; if the server can't be found, turn off Android's Private DNS (it hides Tailscale names).
3. Spotify logs in with the same Client ID and Redirect URI as the web app: Spotify comes back to your server's page,
   which hands the login to the app.

What it adds on a phone:
- **Phone sound**: the bars follow everything the phone plays, the Spotify app included (Android's Visualizer; it
  needs the microphone permission, and nothing is recorded or sent).
- **The Spotify app on the phone** (optional, with notification access): exact lyrics timing from its player, song
  changes at once, far fewer Web API requests, and play/pause/skip/seek without Premium.
- Your own songs keep playing in the background, with media controls on the lock screen and Bluetooth.
- Picture-in-picture lyrics, keep the screen on while music plays, haptics, app shortcuts, and keyboard shortcuts with
  a keyboard (DeX, tablets).
- Updates from inside the app (*Settings → Help & about*), installed through Android's installer.

The app keeps its own settings on the phone; *Settings → Help & about → Back up settings* copies them to paste into the
web app or another device, and back. It's built by `.github/workflows/android.yml`; releases are published on
`android-v*` tags with `android/RELEASE_NOTES.md` as their notes.

## macOS app

**Vibe Visualizer for macOS** (macOS 13 or newer) opens your Vibe Visualizer server in its own window, so lyrics,
Canvas, your settings and updates all come from the server, the same as in a browser. It doesn't run the server
itself; keep Tailscale connected on the Mac if your server is reached through your tailnet.

- **Install:** download `Vibe-Visualizer-macOS.dmg` from the [Releases page](../../releases), open it and drag
  *Vibe Visualizer* to *Applications*. The app isn't signed by Apple yet, so the first time, right-click it and choose
  *Open* (or allow it under *System Settings → Privacy & Security*).
- **First start:** type your server's address (`https://NAME.ts.net`). Change it later with the gear button in the
  app's window. The app keeps its own browser data, separate from Safari.
- **Spotify login** opens in your default browser. Spotify comes back to your server's address (the Redirect URI you
  already registered), and the page there has an **Open Vibe Visualizer** button that takes the login back to the
  app.
- **Bars from the Spotify app:** there's no Vibe Bridge for macOS yet. Install a loopback device such as
  [BlackHole](https://github.com/ExistentialAudio/BlackHole), send the Mac's sound to it (a Multi-Output Device in
  *Audio MIDI Setup* keeps your speakers playing), and pick it with the microphone button; it's marked 🔊.
  Local files work as they are.
- **Files** open with the Mac's own file picker (music, lyrics files, a cover or a Canvas video).
- **Updates:** *Settings → Help & about → Mac app* shows the app's version and, when a newer one is out, what changed
  and a download button.
- **Building it yourself:** the app is in `macos/` (SwiftUI and WebKit, no third-party libraries). Open
  `macos/VibeVisualizer.xcodeproj` in Xcode and build the `VibeVisualizer` scheme; `bash macos/package-dmg.sh` turns the
  Release build into a `.dmg`. GitHub Actions builds the `.dmg` on every change (a workflow artifact) and publishes it
  when a tag like `macos-v0.1.0` is pushed.

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
| `↑` `↓` | Volume (Spotify's own volume while Spotify is playing) |
| `N` `P` | Next / previous |
| `S` | Shuffle (Spotify) |
| `L` | Save to / remove from Liked Songs (Spotify) |
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
(Anthropic's Claude Opus). The macOS app was made by Nikki Honzulů.

The "Spicy Lyrics" lyrics font option uses [Inter](https://rsms.me/inter/) by Rasmus Andersson (SIL Open Font License
1.1, `web/InterVariable-LICENSE.txt`), or the device's own San Francisco font on Apple devices.

Background vocals in brackets and a song's own lyrics delay come from
[Spicy Lyrics Mobile](https://github.com/spicylyrics/mobile) by TX24 (AGPL-3.0).

The moving background is drawn with [Kawarp](https://github.com/better-lyrics/kawarp) (MIT, from the Better Lyrics
project), as Spicy Lyrics does: a WebGL Kawase blur with a slow liquid warp.

The lyrics animation (word and letter springs, fill gradient, line dimming and blur), the instrumental-break dots,
the credits layout and the way romanization is done are ported from
[Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics) by Spikerko and the Spicy Lyrics contributors, licensed
under the GNU AGPL-3.0. Its spring model is itself a port of [spr](https://github.com/Fraktality/spr) by Fraktality
(MIT). **Vibe Visualizer is unofficial: it isn't made by, endorsed by or affiliated with Spicy Lyrics.**

## License

Vibe Visualizer is free software, licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0),
because it includes code ported from Spicy Lyrics. If you run a modified version for other people over a network,
you must offer them its source code; the app links to it under *Settings → Help & about*.

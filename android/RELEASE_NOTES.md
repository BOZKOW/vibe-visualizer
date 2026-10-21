The new Vibe Visualizer for Android: a native app (Kotlin, Jetpack Compose, Material 3 Expressive) instead of the old web page in a WebView. Android 12 or newer. It uses your Vibe Visualizer server over Tailscale for Spicy Lyrics, Spotify's own lyrics, Canvas and NetEase, and does everything else on the phone.

This is the first test build (0.0.1). Uninstall the old app first; settings it kept on your server come along when you enter your server's address in the setup guide.

### What's in it

- The web app's player: cover and title, Visualizer / Lyrics / Both views, word-synced lyrics with the Spicy Lyrics animation (springs, letter waves on held words, glow, distance blur, the three dots), background vocals, duets, Focus mode and the other experiments
- All six lyrics sources in your order (your files, Spicy Lyrics, AMLL, Spotify, LRCLIB, NetEase), the 7-day lyrics cache, translation and romanization (Japanese, Chinese, Korean, Cyrillic and Greek romanized on the phone)
- Spotify: PKCE login through your server's page, now playing with the same adaptive polling and back-off, play/pause/skip/seek, shuffle, repeat, Liked Songs, the device picker, Spotify volume, Next up, Canvas videos with the blurred-Canvas background
- The live background made from the cover (a liquid warp on Android 13+), still backgrounds, cover colors, the bar visualizer with all its settings, desync detection, the ping meter, problems shown where the lyrics go
- Your own songs: open them from the app, a file manager or Share; they keep playing in the background with media controls on the lock screen and Bluetooth; the bars follow the real sound
- Phone sound: the bars follow everything the phone plays, the Spotify app included (microphone permission; nothing is recorded)
- With notification access, the Spotify app on the phone gives exact lyrics timing, instant song changes and play/pause/skip/seek without Premium
- Android extras: picture-in-picture lyrics, keep the screen on while music plays, edge-to-edge, Material You neutrals with the cover's accent, haptics, app shortcuts, keyboard shortcuts, app updates from inside the app
- Every setting of the web app, with search; settings backups paste between the app and the web app

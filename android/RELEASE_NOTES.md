Vibe Visualizer for Android 0.1.10 (beta). Android 12 or newer; it installs over 0.1.9 (Settings → Help & about → Update now).

### From Spicy Lyrics 6.4.70

- Background vocal pop-out (Settings → Experiments) only applies with Simple lyrics and Refreshed simple lyrics on, as in Spicy Lyrics now; anywhere else every background vocal line stays open. It animates the same with reduced motion on. (The Canvas already keeps playing with reduced motion on in this app.)

### Fixes from a look through the whole app

- Picking a cover image while a song's lyrics were still being found cancelled them; now they still come
- When Spotify keeps failing (offline, or access refused), the app asks it less and less often (3, 6, 12, 24, then 30 s) instead of every 3 seconds, so it doesn't run into Spotify's limits or use battery
- Your own songs: the lyrics sources that look the song up on Spotify share one search instead of sending three at once
- Turning Canvas on during a song shows that song's Canvas, not only from the next song

Checked: all the unit tests pass, including real answers from every lyrics source going through the app's own parsers.

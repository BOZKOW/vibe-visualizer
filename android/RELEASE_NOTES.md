Vibe Visualizer for Android 0.0.8 (beta). Android 12 or newer; it installs over 0.0.7 (Settings → Help & about → Update now).

### What's new

- A song without lyrics shows its cover big on the Lyrics screen (and on a wide screen), with a short note under it
- The bars show in every view: under the cover on Now Playing too (⋮ → Bars in every view to turn them off)
- Settings opens on its list of pages, not on the page you last had open
- Blur the Canvas: a new switch in the ⋮ menu while a Canvas plays, and in Settings next to the other Canvas options
- The moving background is calmer: it follows the tempo and the loudness, a beat only adds a little, and changes ease in

### Cooler and lighter on the battery

- The moving background is drawn at a small size and stretched (as Spicy Lyrics Mobile does): about 1/50 of the work for the graphics chip, looking the same
- 60 fps by default, and the screen runs at 60 Hz with it. Settings → Performance → Limit frame rate brings 120 back if you prefer it smoother
- While paused, the background redraws 20 times a second; the blurred Canvas behind the player is taken 12 times a second instead of 30, without a new picture each time
- The bars no longer create new objects every frame

From 0.0.7 (Spicy Lyrics Mobile by TheX24): faster lyrics lookups with word-synced lyrics preferred, the Unison and LRCMux sources, steadier timing with the Spotify app, lyrics timing per audio output, cleaner names for your own files.

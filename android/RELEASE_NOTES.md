Vibe Visualizer for Android 0.1.16 (beta). Android 12 or newer; it installs over 0.1.15 (Settings → Help & about → Update now).

### New

- **Landscape laid out as Spicy Lyrics Mobile's**, in the portrait look: with lyrics, the cover (or the Canvas, which takes its place) with the song and the seek bar on the left, the lyrics on the right, the controls under each side. Without lyrics, everything in the middle: the cover with the times beside the seek bar and the song big under it ("artist · year"), or the Canvas centred with soft edges and just the song and the seek bar at the bottom. The controls hide while music plays, as in portrait
- **Home screen widget** (from Spicy Lyrics Mobile 0.15.0): the line being sung with the lines around it in the lyrics font (the current one glowing, the other singer's on the right, background vocals under their line, breaks as dots), the song, previous / play / next and how far the song is, over a blurred still of the cover. Touch and hold your home screen → Widgets → Vibe Visualizer. It follows the song line by line while the app runs; size, the song, the controls and the background are under Settings → Phone → Home screen widget
- **Ambient mode** (Settings → Phone, off by default): left untouched while music plays, the screen dims to the lyrics and the song on black after 30 seconds (or what you pick under Dim after). Over the big cover or a song without lyrics, just the song. Any touch brings everything back
- **Menus open out of the button you tap**: Settings, each Settings page, the sheets, the dialogs and the setup guide grow from where you touched and shrink back into it. On a phone, a Settings page now animates over the list (it used to switch at once) and the list keeps its place. Lyrics and the Visualizer grow from their buttons. Sheets close with a tap beside them, Back, or a pull on the handle
- **Spicy Lyrics' Canvas service** (Settings → Spotify & Canvas, on): when your server has no Canvas for a Spotify song, or no server is set, the song's Spotify ID goes to Spicy Lyrics' Canvas service (spicylyrics.org). Nothing else is sent; answers are remembered on the phone
- **Lyrics size of its own for picture-in-picture** (Settings → Phone), so big lyrics on the full screen don't leave one word per row in the small window

### Fixes

- The lyrics no longer pause and jump back to the start when the Spotify app plays on a Spotify Connect speaker: the app stays on the Spotify session it follows through its brief drop-outs
- A Canvas lookup that fails is tried once more, then left alone for a minute (doubling after more failures) instead of on every song
- Over the lock screen, Settings and the menus ask you to unlock first
- A word wider than the screen (a long held "Weeeeeng") makes its line smaller instead of running off the edge
- More room between wrapped community credit lines

From 0.1.15: a long song name's first letter is whole again.

Vibe Visualizer for Android 0.0.9 (beta). Android 12 or newer; it installs over 0.0.8 (Settings → Help & about → Update now).

### What's new

- Hide the status bar and Hide the navigation bar (Settings → Phone): a swipe from the edge brings them back for a moment
- Next up shows on every screen: over the end of the lyrics, under the song on the Visualizer, beside the lyrics on a wide screen
- The Canvas plays behind the lyrics in picture-in-picture too
- Canvas blur: a slider for how strong Blur the Canvas is (Settings, next to the other Canvas options)

### Fixes

- "Wrong lyrics? Try another source" asked Spicy Lyrics again for songs Spicy Lyrics gets from Apple Music
- Japanese romanization: a small っ at the end of a word doubles the next consonant (だった is "da tta", not "da ta")
- Your server: a pasted address no longer reads https://https://…, a link to a page on the server is saved as just the server, and the answer to Test and save shows under the button, the whole width

### Checked

Real answers from every lyrics source (Spicy Lyrics, AMLL, Unison, LRCMux, NetEase, Spotify, LRCLIB) now go through the app's own parsers in its tests: word sync, background vocals, duets, credits, interlude dots, translations and romanization all come out right. The Canvas lookup on your server answers too.

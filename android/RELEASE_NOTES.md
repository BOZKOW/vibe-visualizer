Vibe Visualizer for Android 0.19.8 (beta). Android 12 or newer; it installs over 0.19.7 (Settings → Help & about → Update now).

### Faster

- **Song changes show at once** when the Spotify app plays on this phone (with notification access): the new song's name and cover appear the moment it starts, instead of after Spotify's servers name it; the lyrics, the Canvas and the sharper cover follow a moment later without the song animating in twice. The Web API is asked sooner after a change too
- **Lyrics don't wait for a slow source**: if a source further down your list already has them, they show after 2 seconds instead of waiting for the first source to answer (or time out); if the first one then answers just as well within 8 seconds, its lyrics take over, so your order still decides

### Fixes

- **"No lyrics" notes are turned down**: some sources (NetEase, QQ Music, KuGou…) answer an instrumental with a note like 纯音乐，请欣赏 ("pure music, please enjoy"), 暂无歌词 ("no lyrics yet") or "Instrumental" instead of lyrics. These count as not found now, so the next source is asked; ones saved earlier are looked up again
- Copy debug info says how the app hears about Spotify's song changes, and how often it asks

From 0.19.7: landscape keeps clear of the camera hole.

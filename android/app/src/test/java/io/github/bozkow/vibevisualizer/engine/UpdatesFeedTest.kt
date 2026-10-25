package io.github.bozkow.vibevisualizer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub's release feed, as it looks (two entries, the notes escaped HTML). */
class UpdatesFeedTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/" xml:lang="en-US">
  <id>tag:github.com,2008:https://github.com/BOZKOW/vibe-visualizer/releases</id>
  <link type="text/html" rel="alternate" href="https://github.com/BOZKOW/vibe-visualizer/releases"/>
  <title>Release notes from vibe-visualizer</title>
  <entry>
    <id>tag:github.com,2008:Repository/1/web-v1.4.0</id>
    <updated>2026-10-25T12:27:22Z</updated>
    <link rel="alternate" type="text/html" href="https://github.com/BOZKOW/vibe-visualizer/releases/tag/web-v1.4.0"/>
    <title>web-v1.4.0: 1.4.0: The Stage design is gone</title>
    <content type="html">&lt;p&gt;Stage is removed.&lt;/p&gt;</content>
  </entry>
  <entry>
    <id>tag:github.com,2008:Repository/1/android-v0.19.3</id>
    <updated>2026-10-25T21:06:40Z</updated>
    <link rel="alternate" type="text/html" href="https://github.com/BOZKOW/vibe-visualizer/releases/tag/android-v0.19.3"/>
    <title>Vibe Visualizer for Android 0.19.3 beta</title>
    <content type="html">&lt;p&gt;Vibe Visualizer for Android 0.19.3 (beta). (Settings → Help &amp;amp; about → Update now).&lt;/p&gt;
&lt;h3&gt;New&lt;/h3&gt;
&lt;ul&gt;
&lt;li&gt;&lt;strong&gt;A warning when the phone gets warm&lt;/strong&gt;, with the battery&#39;s temperature (&quot;getting warm&quot;)&lt;/li&gt;
&lt;li&gt;Previous and next
animate&lt;/li&gt;
&lt;/ul&gt;</content>
  </entry>
</feed>"""

    @Test fun entries() {
        val f = Updates.parseFeed(feed)
        assertEquals(listOf("web-v1.4.0", "android-v0.19.3"), f.map { it.tag })
        val a = f[1]
        assertEquals("Vibe Visualizer for Android 0.19.3 beta", a.title)
        assertEquals("2026-10-25T21:06:40Z", a.date)
        assertEquals(listOf("A warning when the phone gets warm, with the battery's temperature (\"getting warm\")", "Previous and next animate"), a.points)
        assertTrue(f[0].points.isEmpty())
    }

    @Test fun nothing() = assertTrue(Updates.parseFeed("<html>not a feed</html>").isEmpty())
}

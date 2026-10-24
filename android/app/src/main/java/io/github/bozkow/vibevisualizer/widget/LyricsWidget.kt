package io.github.bozkow.vibevisualizer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import io.github.bozkow.vibevisualizer.MainActivity
import io.github.bozkow.vibevisualizer.R
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.Visuals
import io.github.bozkow.vibevisualizer.lyrics.Line
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The home screen widget: the line being sung with the lines around it, the song, the controls and how far it is,
 *  over a blurred still of the cover (Spicy Lyrics Mobile 0.15.0's widget, in this app's look). [Widgets] keeps it
 *  current while the app runs (playing your files, or following the Spotify app). */
class LyricsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = Widgets.refresh()
    override fun onEnabled(context: Context) = Widgets.refresh()
    override fun onDisabled(context: Context) = Widgets.refresh()
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = Widgets.refresh()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PREV -> E.prev()
            TOGGLE -> E.toggle()
            NEXT -> E.next()
            else -> return super.onReceive(context, intent)
        }
        Widgets.refresh()
    }

    companion object {
        const val PREV = "io.github.bozkow.vibevisualizer.widget.PREV"
        const val TOGGLE = "io.github.bozkow.vibevisualizer.widget.TOGGLE"
        const val NEXT = "io.github.bozkow.vibevisualizer.widget.NEXT"
    }
}

object Widgets {
    private var ids = IntArray(0)
    private var job: Job? = null
    private var lastKey: Any? = null
    private var lastAt = 0L
    private var coverFor = -1; private var backdrop: Bitmap? = null; private var thumb: Bitmap? = null

    private fun query(): IntArray = runCatching { AppWidgetManager.getInstance(Vibe.app).getAppWidgetIds(ComponentName(Vibe.app, LyricsWidget::class.java)) }.getOrDefault(IntArray(0))

    /** Widgets were placed, removed, resized or a setting changed: everything is drawn again. Only while there's a
     *  widget does anything run (a few times a second while music plays, once a second when it doesn't). */
    fun refresh() {
        ids = query(); lastKey = null
        if (ids.isEmpty()) { job?.cancel(); job = null; return }
        if (job?.isActive == true) { tick(); return }
        job = Vibe.scope.launch { while (isActive) { tick(); delay(if (E.playingState) 250 else 1000) } }
    }

    /** The line sung now: the last whose time has come (-1 before the first). */
    internal fun current(ls: List<Line>, pos: Double): Int {
        var k = -1
        for ((j, l) in ls.withIndex()) { val t = l.t ?: continue; if (t <= pos) k = j else break }
        return k
    }

    private fun tick() {
        if (ids.isEmpty()) return
        val ls = Lyr.L
        val i = if (Lyr.lsync) current(ls, Lyr.lyrT()) else -1
        val key = listOf(E.trackVer, E.title, E.artist, E.playingState, i, ls.size, Lyr.lsync, Visuals.coverVer, S.tr, S.romMode, Lyr.credit, Lyr.msg?.msg)
        val now = SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastAt < 20_000) return   // the time runs by itself; the bar moves at least every 20 s
        lastKey = key; lastAt = now
        runCatching { push(ls, i) }.onFailure { android.util.Log.w("VV-widget", "update: ${it.message}") }
    }

    private fun push(ls: List<Line>, cur: Int) {
        val ctx = Vibe.app
        val m = AppWidgetManager.getInstance(ctx)
        val d = ctx.resources.displayMetrics.density
        val song = S.b("aWgSong"); val ctl = S.b("aWgCtl")
        pictures(d)
        for (id in ids) {
            val o = m.getAppWidgetOptions(id)
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val hDp = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).takeIf { it > 0 } ?: 180
            val v = RemoteViews(ctx.packageName, R.layout.widget_lyrics)
            // the lyrics get what the song row, the time and the credit leave
            val credit = creditLine()
            val linesH = hDp - 32 - (if (song || ctl) 50 else 0) - 24 - (if (credit != null) 18 else 0)
            if (linesH >= 28) {
                v.setViewVisibility(R.id.widget_lines, View.VISIBLE)
                v.setImageViewBitmap(R.id.widget_lines, WidgetPainter.paint(ls, cur, ((wDp - 32) * d).toInt(), (linesH * d).toInt(), d))
                v.setContentDescription(R.id.widget_lines, ls.getOrNull(cur)?.takeIf { !it.dots }?.text ?: "")
            } else v.setViewVisibility(R.id.widget_lines, View.GONE)
            // the song and the controls
            v.setViewVisibility(R.id.widget_song_row, if (song || ctl) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.widget_song, if (song) View.VISIBLE else View.INVISIBLE)
            v.setViewVisibility(R.id.widget_cover, if (song) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_title, E.title.ifEmpty { "Vibe Visualizer" })
            v.setTextViewText(R.id.widget_artist, if (E.src == "none") "Open the app to play something" else E.artist)
            thumb?.let { v.setImageViewBitmap(R.id.widget_cover, it) } ?: v.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover)
            for (b in listOf(R.id.widget_prev, R.id.widget_toggle, R.id.widget_next)) v.setViewVisibility(b, if (ctl) View.VISIBLE else View.GONE)
            v.setImageViewResource(R.id.widget_toggle, if (E.playingState) R.drawable.pip_pause else R.drawable.pip_play)
            v.setContentDescription(R.id.widget_toggle, if (E.playingState) "Pause" else "Play")
            // how far the song is: the elapsed time counts by itself
            val dur = E.curDur(); val pos = E.curTime()
            val timed = dur > 0
            v.setViewVisibility(R.id.widget_times, if (timed) View.VISIBLE else View.GONE)
            if (timed) {
                v.setProgressBar(R.id.widget_progress, 1000, (pos / dur * 1000).toInt().coerceIn(0, 1000), false)
                v.setChronometer(R.id.widget_elapsed, SystemClock.elapsedRealtime() - (pos * 1000).toLong(), null, E.playingState)
                v.setTextViewText(R.id.widget_duration, "%d:%02d".format((dur / 60).toInt(), (dur % 60).toInt()))
            }
            // where the lyrics are from, wherever they show
            v.setViewVisibility(R.id.widget_credit, if (credit != null) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_credit, credit ?: "")
            // the background
            when (S.str("aWgBg")) {
                "clear" -> { v.setInt(R.id.widget_root, "setBackgroundResource", android.R.color.transparent); v.setImageViewResource(R.id.widget_backdrop, android.R.color.transparent) }
                "black" -> { v.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_black); v.setImageViewResource(R.id.widget_backdrop, android.R.color.transparent) }
                else -> { v.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg)
                    backdrop?.let { v.setImageViewBitmap(R.id.widget_backdrop, it) } ?: v.setImageViewResource(R.id.widget_backdrop, android.R.color.transparent) }
            }
            // a tap opens the lyrics
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).setAction("io.github.bozkow.vibevisualizer.LYRICS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            v.setOnClickPendingIntent(R.id.widget_prev, act(ctx, LyricsWidget.PREV, 11))
            v.setOnClickPendingIntent(R.id.widget_toggle, act(ctx, LyricsWidget.TOGGLE, 12))
            v.setOnClickPendingIntent(R.id.widget_next, act(ctx, LyricsWidget.NEXT, 13))
            m.updateAppWidget(id, v)
        }
    }

    private fun act(ctx: Context, a: String, req: Int) = PendingIntent.getBroadcast(ctx, req, Intent(ctx, LyricsWidget::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** "Provided by Spicy Lyrics · made by @someone", as the credits under the lyrics say it. */
    private fun creditLine(): String? {
        val c = Lyr.credit ?: return null
        if (Lyr.lines == 0) return null
        if (c.source == "file") return "From your file"
        val name = c.source?.let { Lyr.PROVIDERS[it] ?: it } ?: return null
        val who = when (c.source) { "spicy_lyrics" -> (c.maker ?: c.uploader)?.let { "made by @" + it.username }; "unison" -> c.uploader?.let { "synced by " + it.username }; else -> null }
        return "Provided by " + name + (c.via?.let { " ($it)" } ?: "") + (who?.let { " · $it" } ?: "")
    }

    /** The cover, small and rounded, and a blurred, darkened still of it for the background: made once per cover. */
    private fun pictures(d: Float) {
        if (coverFor == Visuals.coverVer) return
        coverFor = Visuals.coverVer; thumb = null; backdrop = null
        val c = Visuals.cover?.takeIf { !it.isRecycled } ?: return
        runCatching {
            val sq = minOf(c.width, c.height)
            val src = android.graphics.Rect((c.width - sq) / 2, (c.height - sq) / 2, (c.width + sq) / 2, (c.height + sq) / 2)   // the middle square
            val fp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val ts = (40 * d).toInt().coerceAtLeast(16)
            thumb = Bitmap.createBitmap(ts, ts, Bitmap.Config.ARGB_8888).also { b ->
                val cv = Canvas(b); val r = 8 * d
                cv.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, ts.toFloat(), ts.toFloat()), r, r, Path.Direction.CW) })
                cv.drawBitmap(c, src, android.graphics.Rect(0, 0, ts, ts), fp)
            }
            // shrunk to a few pixels and stretched back: soft, like the app's background, and tiny to send
            val tiny = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(c, src, android.graphics.Rect(0, 0, 12, 12), fp) }
            backdrop = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
                val cv = Canvas(b); cv.drawBitmap(tiny, null, android.graphics.Rect(0, 0, 96, 96), fp); cv.drawColor(Color.argb(115, 0, 0, 0))
            }
        }.onFailure { thumb = null; backdrop = null }
    }
}

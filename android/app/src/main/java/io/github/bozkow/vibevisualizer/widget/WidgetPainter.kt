package io.github.bozkow.vibevisualizer.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.lyrics.Line

/** The widget's lyrics, drawn into a picture in the lyrics font (a widget can only show the system's): the line sung
 *  now, glowing, with [WidgetLook.around] lines above and below it (fewer when they don't fit), in the middle of the
 *  space. The other singer's lines on the right, background vocals, romanization and translation smaller under
 *  their line, breaks as three dots. */
internal object WidgetPainter {
    private val faces = HashMap<String, Typeface>()
    private fun face(weight: Int): Typeface {
        val f = S.str("lfont"); val k = "$f$weight"
        return faces.getOrPut(k) {
            if (f == "system") Typeface.create(Typeface.DEFAULT, weight, false)
            else runCatching { Typeface.Builder(Vibe.app.assets, "fonts/inter.ttf").setFontVariationSettings(if (f == "spicy") "'wght' $weight, 'opsz' 32" else "'wght' $weight").build() }
                .getOrDefault(Typeface.create(Typeface.DEFAULT, weight, false))
        }
    }

    private class Block(val line: Line, val dist: Int, val parts: List<StaticLayout>, val h: Float)

    fun paint(ls: List<Line>, cur: Int, w: Int, h: Int, d: Float, look: WidgetLook): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val (curSp, aroundSp, noteSp) = look.sizes
        val sd = d * Vibe.app.resources.configuration.fontScale
        val duet = ls.any { it.opp }
        if (ls.isEmpty()) {   // no lyrics: why, dimly
            val m = Lyr.msg?.msg ?: return bmp
            val sl = StaticLayout.Builder.obtain(m, 0, m.length, text(aroundSp * sd, 600, .55f), w).setMaxLines(2)
                .setAlignment(if (look.center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).build()
            c.save(); c.translate(0f, (h - sl.height) / 2f); sl.draw(c); c.restore()
            return bmp
        }
        val gap = aroundSp * sd * .5f
        fun block(i: Int): Block {
            val l = ls[i]; val dist = i - cur
            val now = dist == 0
            val fs = (if (now) curSp else aroundSp) * sd
            if (l.dots) return Block(l, dist, emptyList(), fs * 1.2f)
            val al = when { look.center -> Layout.Alignment.ALIGN_CENTER; duet && l.opp -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
            val a = when { now -> 1f; dist < 0 -> .4f; else -> .55f }
            val parts = ArrayList<StaticLayout>()
            fun add(t: String, size: Float, weight: Int, alpha: Float, glow: Boolean) {
                val p = text(size, weight, alpha); if (glow) p.setShadowLayer(size * .45f, 0f, 0f, android.graphics.Color.argb(120, 255, 255, 255))
                parts.add(StaticLayout.Builder.obtain(t, 0, t.length, p, w).setAlignment(al).setIncludePad(false).setLineSpacing(0f, 1.05f).build())
            }
            add(l.text, fs, 700, a, now)
            l.bg?.forEach { b -> add(b.text, fs * .72f, 600, a * .75f, false) }
            val note = noteSp * sd
            if (look.rom) (l.rom ?: l.lineRom ?: l.apiRom)?.takeIf { it != l.text }?.let { add(it, note, 600, a * .8f, false) }
            if (look.tr) (l.tr ?: l.apiTr)?.let { add(it, note, 600, a * .8f, false) }
            return Block(l, dist, parts, parts.sumOf { it.height.toDouble() }.toFloat() + (parts.size - 1) * note * .15f)
        }
        // the line sung now (before the first one: the first lines, as coming up) and the lines around it
        val first = if (cur >= 0) cur else 0
        val from = if (cur >= 0) (cur - look.around).coerceAtLeast(0) else 0
        val to = (first + look.around + if (cur < 0) 1 else 0).coerceAtMost(ls.lastIndex)
        val out = (from..to).map { block(it) }.toMutableList()
        fun total() = out.sumOf { it.h.toDouble() }.toFloat() + gap * (out.size - 1).coerceAtLeast(0)
        // what doesn't fit goes, farthest first (one coming up before one already sung)
        while (out.size > 1 && total() > h) { val far = out.maxByOrNull { kotlin.math.abs(it.dist) * 2 + (if (it.dist < 0) 1 else 0) }!!; out.remove(far) }
        var y = ((h - total()) / 2f).coerceAtLeast(0f)
        for (b in out) {
            if (b.parts.isEmpty()) dots(c, y, b.h, b.dist == 0, when { look.center -> w / 2f; duet && b.line.opp -> -w.toFloat(); else -> 0f })
            else { var py = y; for (p in b.parts) { c.save(); c.translate(0f, py); p.draw(c); c.restore(); py += p.height + noteSp * sd * .15f } }
            y += b.h + gap
        }
        return bmp
    }

    private fun text(size: Float, weight: Int, alpha: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face(weight); textSize = size; color = android.graphics.Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
    }

    /** A break: three dots, lit while it's the one going on. [x]: 0 from the left, a positive middle, a negative
     *  (minus the width) from the right. */
    private fun dots(c: Canvas, y: Float, h: Float, on: Boolean, x: Float) {
        val r = h * .13f; val step = r * 3.2f; val span = r * 2 + step * 2
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(if (on) 235 else 110, 255, 255, 255); if (on) setShadowLayer(r * 2.2f, 0f, 0f, android.graphics.Color.argb(150, 255, 255, 255)) }
        val x0 = when { x > 0f -> x - span / 2 + r; x < 0f -> -x - span + r; else -> r }
        for (i in 0..2) c.drawCircle(x0 + i * step, y + h / 2, r, p)
    }
}

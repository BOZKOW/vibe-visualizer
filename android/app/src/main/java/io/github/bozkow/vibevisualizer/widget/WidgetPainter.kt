package io.github.bozkow.vibevisualizer.widget

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.lyrics.Line

/** The widget's lyrics, drawn into a picture in the lyrics font (a widget can only show the system's): the line
 *  before (when there's room), the one sung now glowing, the ones coming up fading out at the bottom. The other
 *  singer's lines on the right, background vocals smaller under their line, breaks as three dots. */
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

    private class Block(val line: Line?, val role: Int, val parts: List<StaticLayout>, val h: Float)

    fun paint(ls: List<Line>, cur: Int, w: Int, h: Int, d: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = when (S.str("aWgSize")) { "s" -> .82f; "l" -> 1.22f; else -> 1f }
        val fs = 20f * d * k
        val right = ls.any { it.opp }
        if (ls.isEmpty()) {   // no lyrics: why, dimly
            val m = Lyr.msg?.msg ?: return bmp
            val p = text(fs * .8f, 600, .55f)
            c.save(); c.translate(0f, (h - fs) / 2); StaticLayout.Builder.obtain(m, 0, m.length, p, w).setMaxLines(2).build().draw(c); c.restore()
            return bmp
        }
        fun block(i: Int, role: Int): Block {
            val l = ls[i]
            if (l.dots) return Block(l, role, emptyList(), fs * 1.3f)
            val al = if (right && l.opp) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            val a = when (role) { 0 -> 1f; -1 -> .38f; else -> .5f }
            val rom = if (S.romMode != "off") (l.rom ?: l.lineRom ?: l.apiRom) else null
            val main = if (S.romMode == "replace" && rom != null) rom else l.text
            val parts = ArrayList<StaticLayout>()
            fun add(t: String, size: Float, weight: Int, alpha: Float, glow: Boolean) {
                val p = text(size, weight, alpha); if (glow) p.setShadowLayer(size * .45f, 0f, 0f, android.graphics.Color.argb(120, 255, 255, 255))
                parts.add(StaticLayout.Builder.obtain(t, 0, t.length, p, w).setAlignment(al).setIncludePad(false).setLineSpacing(0f, 1.05f).build())
            }
            add(main, fs, 700, a, role == 0)
            if (S.romMode == "below" && rom != null && rom != main) add(rom, fs * .62f, 600, a * .8f, false)
            (if (S.tr) l.tr ?: l.apiTr else null)?.let { add(it, fs * .62f, 600, a * .8f, false) }
            l.bg?.forEach { b -> add(b.text, fs * .7f, 600, a * .75f, false) }
            return Block(l, role, parts, parts.sumOf { it.height.toDouble() }.toFloat() + (parts.size - 1) * fs * .12f)
        }
        val gap = fs * .55f
        val out = ArrayList<Block>()
        val start = cur.coerceAtLeast(0)
        if (cur > 0) block(cur - 1, -1).let { prev -> if (prev.h + gap + block(cur, 0).h < h * .62f) out.add(prev) }
        var y = out.sumOf { (it.h + gap).toDouble() }.toFloat()
        var j = start
        while (j < ls.size && y < h) { val b = block(j, if (j == cur) 0 else 1); out.add(b); y += b.h + gap; j++ }
        // draw, top down
        y = 0f
        for (b in out) {
            if (b.parts.isEmpty()) dots(c, y, fs, b.role == 0, if (right && b.line?.opp == true) w.toFloat() else null)
            else { var py = y; for (p in b.parts) { c.save(); c.translate(0f, py); p.draw(c); c.restore(); py += p.height + fs * .12f } }
            y += b.h + gap
        }
        // the lines coming up fade out at the bottom
        val f = Paint().apply { shader = LinearGradient(0f, h * .62f, 0f, h.toFloat(), intArrayOf(-0x1000000, 0), null, Shader.TileMode.CLAMP); blendMode = BlendMode.DST_IN }
        c.drawRect(0f, h * .62f, w.toFloat(), h.toFloat(), f)
        return bmp
    }

    private fun text(size: Float, weight: Int, alpha: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face(weight); textSize = size; color = android.graphics.Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
    }

    /** A break: three dots, lit while it's the one going on. */
    private fun dots(c: Canvas, y: Float, fs: Float, on: Boolean, rightEdge: Float?) {
        val r = fs * .16f; val step = r * 3.2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(if (on) 235 else 110, 255, 255, 255); if (on) setShadowLayer(r * 2.2f, 0f, 0f, android.graphics.Color.argb(150, 255, 255, 255)) }
        val x0 = if (rightEdge != null) rightEdge - r - 2 * step - r else r + r
        for (i in 0..2) c.drawCircle(x0 + i * step, y + fs * .65f, r, p)
    }
}

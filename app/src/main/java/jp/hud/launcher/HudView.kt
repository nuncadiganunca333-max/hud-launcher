package jp.hud.launcher

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

data class Stats(
    var battery: Int = 0,
    var charging: Boolean = false,
    var memory: Int = 0,
    var storage: Int = 0,
    var temp: Float? = null,
    var humidity: Int = 0,
    var weather: String = "---",
    var forecast: List<String> = emptyList(),
)

class HudView(context: Context) : View(context) {

    interface Listener {
        fun onOpenDrawer()
        fun onLaunch(app: AppEntry)
        fun onDockLongPress(app: AppEntry)
        fun onGaugeTap(index: Int)
        fun onClockTap()
        fun onHomeSettings()
    }

    var listener: Listener? = null
    var dock: List<AppEntry> = emptyList()
        set(v) { field = v; invalidate() }
    var stats = Stats()
        set(v) { field = v; invalidate() }

    private val CYAN = Color.rgb(0, 229, 255)
    private val RED = Color.rgb(255, 60, 60)
    private fun cyan(a: Int) = Color.argb(a, 0, 229, 255)

    private var running = false
    private val startTime = System.nanoTime()
    private var staticLayer: Bitmap? = null
    private var top = 0f

    // layout
    private var wide = true
    private var cx = 0f; private var cy = 0f; private var coreR = 0f
    private val gauges = ArrayList<PointF>()
    private var gaugeR = 0f
    private var topBarY = 0f
    private val infoRect = RectF()
    private val dockRect = RectF()
    private val dockCells = ArrayList<RectF>()
    private var dockCols = 6

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val textGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan(170)
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) { longPress(e.x, e.y) }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (vy < -1200 && abs(vy) > abs(vx)) { listener?.onOpenDrawer(); return true }
            return false
        }
    })

    fun setTopInset(px: Int) { top = px.toFloat(); if (width > 0) relayout(width, height) }

    fun start() { if (!running) { running = true; invalidate() } }
    fun stop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout(w, h) }

    private fun relayout(w: Int, h: Int) {
        val W = w.toFloat(); val H = h.toFloat()
        wide = W / H > 0.75f
        gauges.clear()
        val m = min(W, H)
        if (wide) {
            topBarY = top + H * 0.045f
            cx = W / 2; cy = H * 0.43f; coreR = m * 0.22f
            gaugeR = m * 0.085f
            for ((gx, gy) in listOf(0.16f to 0.24f, 0.84f to 0.24f, 0.16f to 0.66f, 0.84f to 0.66f))
                gauges += PointF(W * gx, H * gy)
            infoRect.set(W * 0.33f, H * 0.69f, W * 0.67f, H * 0.80f)
            dockRect.set(W * 0.05f, H * 0.85f, W * 0.95f, H * 0.95f)
            dockCols = 6
        } else {
            topBarY = top + H * 0.03f
            cx = W / 2; cy = top + H * 0.25f; coreR = W * 0.33f
            gaugeR = W * 0.12f
            for ((gx, gy) in listOf(0.25f to 0.52f, 0.75f to 0.52f, 0.25f to 0.66f, 0.75f to 0.66f))
                gauges += PointF(W * gx, H * gy)
            infoRect.setEmpty()
            dockRect.set(W * 0.06f, H * 0.75f, W * 0.94f, H * 0.93f)
            dockCols = 3
        }
        layoutDock()
        staticLayer?.recycle()
        staticLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawStatic(Canvas(it), W, H) }
        invalidate()
    }

    private fun layoutDock() {
        dockCells.clear()
        val total = (dock.size + 1).coerceAtMost(if (wide) 6 else 6)
        val rows = if (wide) 1 else 2
        val cols = if (wide) total.coerceAtLeast(1) else 3
        val pad = dockRect.height() * 0.08f
        val cw = (dockRect.width() - pad * (cols + 1)) / cols
        val ch = (dockRect.height() - pad * (rows + 1)) / rows
        for (i in 0 until total) {
            val r = i / cols; val c = i % cols
            val x = dockRect.left + pad + c * (cw + pad)
            val y = dockRect.top + pad + r * (ch + pad)
            dockCells += RectF(x, y, x + cw, y + ch)
        }
    }

    // ---------- static layer ----------
    private fun drawStatic(c: Canvas, W: Float, H: Float) {
        val bg = Paint().apply {
            shader = RadialGradient(cx, cy, max(W, H) * 0.75f,
                intArrayOf(Color.rgb(10, 50, 78), Color.rgb(3, 20, 36), Color.rgb(1, 7, 15)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W, H, bg)
        val rnd = Random(7)
        // hex grid
        stroke.strokeWidth = 1.5f; stroke.color = cyan(14)
        val s = min(W, H) / 50f; val hh = s * sqrt(3f)
        var gx = -1
        while (gx * 1.5f * s < W + s) {
            var gy = -1
            while (gy * hh < H + hh) {
                val ox = gx * 1.5f * s; val oy = gy * hh + if (gx % 2 != 0) hh / 2 else 0f
                val p = Path()
                for (k in 0..6) {
                    val a = PI.toFloat() / 3 * k
                    if (k == 0) p.moveTo(ox + s * cos(a), oy + s * sin(a)) else p.lineTo(ox + s * cos(a), oy + s * sin(a))
                }
                c.drawPath(p, stroke); gy++
            }
            gx++
        }
        // circuits
        stroke.strokeWidth = 2f
        repeat(40) {
            var x = rnd.nextFloat() * W; var y = rnd.nextFloat() * H
            val p = Path().apply { moveTo(x, y) }
            repeat(rnd.nextInt(2, 5)) {
                if (rnd.nextBoolean()) x += (if (rnd.nextBoolean()) 1 else -1) * rnd.nextInt(60, 300)
                else { val d = rnd.nextInt(40, 160).toFloat(); x += (if (rnd.nextBoolean()) d else -d); y += if (rnd.nextBoolean()) d else -d }
                p.lineTo(x, y)
            }
            stroke.color = cyan(rnd.nextInt(30, 90)); c.drawPath(p, stroke)
            stroke.color = cyan(160); c.drawCircle(x, y, 5f, stroke)
        }
        // particles
        repeat(220) {
            fill.color = cyan(rnd.nextInt(40, 200))
            c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H, rnd.nextInt(1, 4).toFloat(), fill)
        }
        // side rails
        stroke.strokeWidth = 4f; stroke.color = cyan(170)
        val railTop = topBarY + H * 0.05f; val railBot = dockRect.top - H * 0.03f
        for (x in listOf(W * 0.025f, W * 0.975f)) {
            c.drawLine(x, railTop, x, railBot, stroke)
            stroke.strokeWidth = 2f; stroke.color = cyan(110)
            var y = railTop
            val dir = if (x < W / 2) 1 else -1
            while (y < railBot) { c.drawLine(x, y, x + dir * 16, y, stroke); y += 36 }
            stroke.strokeWidth = 4f; stroke.color = cyan(170)
        }
        // core static ticks
        ticks(c, cx, cy, coreR + 34, 120, 10f, cyan(140))
        ticks(c, cx, cy, coreR * 0.66f - 6, 72, 7f, cyan(110))
        ring(c, cx, cy, coreR, 4f, cyan(220))
        ring(c, cx, cy, coreR * 0.66f, 3f, cyan(190))
        ring(c, cx, cy, coreR * 0.52f, 1.5f, cyan(90))
        // gauge static
        for (g in gauges) {
            ticks(c, g.x, g.y, gaugeR + 16, 60, 8f, cyan(110))
            ring(c, g.x, g.y, gaugeR, 3f, cyan(200))
            ring(c, g.x, g.y, gaugeR * 0.62f, 1.5f, cyan(90))
            stroke.color = cyan(55); stroke.strokeWidth = gaugeR * 0.1f
            c.drawCircle(g.x, g.y, gaugeR * 0.8f, stroke)
        }
        // info panel
        if (!infoRect.isEmpty) frame(c, infoRect)
        frame(c, dockRect)
    }

    private fun ring(c: Canvas, x: Float, y: Float, r: Float, w: Float, col: Int) {
        stroke.strokeWidth = w; stroke.color = col; c.drawCircle(x, y, r, stroke)
    }

    private fun ticks(c: Canvas, x: Float, y: Float, r: Float, n: Int, len: Float, col: Int) {
        stroke.color = col; stroke.strokeWidth = 2f
        for (i in 0 until n) {
            val t = 2 * PI * i / n; val l = if (i % 5 == 0) len * 2 else len
            c.drawLine(x + r * cos(t).toFloat(), y + r * sin(t).toFloat(),
                x + (r - l) * cos(t).toFloat(), y + (r - l) * sin(t).toFloat(), stroke)
        }
    }

    private fun frame(c: Canvas, r: RectF) {
        fill.color = Color.argb(70, 0, 40, 60); c.drawRect(r, fill)
        stroke.strokeWidth = 2f; stroke.color = cyan(140); c.drawRect(r, stroke)
        stroke.strokeWidth = 6f; stroke.color = CYAN
        c.drawLine(r.left, r.top, r.left + 60, r.top, stroke)
        c.drawLine(r.right - 60, r.bottom, r.right, r.bottom, stroke)
    }

    private fun arc(c: Canvas, x: Float, y: Float, r: Float, w: Float, start: Float, sweep: Float, col: Int, withGlow: Boolean = true) {
        val o = RectF(x - r, y - r, x + r, y + r)
        if (withGlow) { glow.strokeWidth = w * 1.6f; glow.color = Color.argb(140, Color.red(col), Color.green(col), Color.blue(col)); c.drawArc(o, start, sweep, false, glow) }
        stroke.strokeWidth = w; stroke.color = col; c.drawArc(o, start, sweep, false, stroke)
    }

    // ---------- frame ----------
    override fun onDraw(c: Canvas) {
        staticLayer?.let { c.drawBitmap(it, 0f, 0f, null) } ?: return
        val t = (System.nanoTime() - startTime) / 1e9f
        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f

        drawCore(c, t, now, sec)
        drawTopBar(c, now)
        drawGauges(c, t)
        drawInfo(c)
        drawDock(c)

        // scan line
        val sy = ((t * 0.12f) % 1f) * height
        stroke.strokeWidth = 2f; stroke.color = cyan(28)
        c.drawLine(0f, sy, width.toFloat(), sy, stroke)

        if (running) postInvalidateDelayed(33)
    }

    private fun drawCore(c: Canvas, t: Float, now: Calendar, sec: Float) {
        // rotating segmented ring
        val rot = t * 18f
        for (k in 0 until 4) arc(c, cx, cy, coreR - coreR * 0.12f, coreR * 0.06f, rot + k * 90f, 50f, CYAN)
        stroke.strokeWidth = coreR * 0.06f; stroke.color = cyan(45)
        c.drawCircle(cx, cy, coreR - coreR * 0.12f, stroke)
        // counter ring
        val rot2 = -t * 32f
        for (k in 0 until 3) arc(c, cx, cy, coreR * 0.74f, 6f, rot2 + k * 120f, 70f, cyan(210))
        // seconds sweep
        arc(c, cx, cy, coreR * 0.59f, 8f, -90f, sec / 60f * 360f, CYAN)
        // outer accents
        for (k in 0 until 8) arc(c, cx, cy, coreR + 70, 3f, k * 45f - t * 6f, 18f, cyan(170), false)
        arc(c, cx, cy, coreR + 52, 6f, 225f + sin(t * 0.7f) * 20f, 34f, RED)
        // pulse
        val pulse = (sin(t * 2f) + 1) / 2
        glow.strokeWidth = 26f; glow.color = cyan((40 + 50 * pulse).toInt())
        c.drawCircle(cx, cy, coreR, glow)

        // text
        val hm = String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val big = coreR * 0.40f
        thin.textSize = big; textGlow.textSize = big
        c.drawText(hm, cx, cy + big * 0.35f, textGlow)
        c.drawText(hm, cx, cy + big * 0.35f, thin)
        text.textAlign = Paint.Align.CENTER
        text.textSize = coreR * 0.09f; text.color = cyan(220); text.letterSpacing = 0.2f
        c.drawText(String.format(Locale.US, "%04d.%02d.%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)),
            cx, cy - big * 0.55f, text)
        val wd = now.get(Calendar.DAY_OF_WEEK) - 1
        val jp = arrayOf("日", "月", "火", "水", "木", "金", "土")[wd]
        val en = arrayOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")[wd]
        text.textSize = coreR * 0.075f
        c.drawText("$en  /  ${jp}曜日", cx, cy + big * 0.85f, text)
        text.textSize = coreR * 0.06f; text.color = cyan(150)
        c.drawText(String.format(Locale.US, "SEC %02d", sec.toInt()), cx, cy + big * 1.15f, text)
        text.letterSpacing = 0f; text.color = CYAN
    }

    private fun drawTopBar(c: Canvas, now: Calendar) {
        val days = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = now.get(Calendar.DAY_OF_MONTH)
        val l = width * 0.05f; val r = width * 0.95f
        stroke.strokeWidth = 2f; stroke.color = cyan(110)
        c.drawLine(l, topBarY + 26, r, topBarY + 26, stroke)
        text.textAlign = Paint.Align.CENTER
        val step = (r - l) / (days - 1)
        val fs = min(step * 0.55f, 34f)
        for (d in 1..days) {
            val x = l + step * (d - 1)
            if (d == today) {
                fill.color = CYAN
                c.drawRect(x - step * 0.45f, topBarY - fs, x + step * 0.45f, topBarY + 8, fill)
                text.color = Color.rgb(1, 10, 20)
            } else text.color = cyan(if (d < today) 90 else 190)
            text.textSize = fs
            if (wide || d % 2 == 1 || d == today) c.drawText(d.toString(), x, topBarY, text)
            stroke.color = cyan(150); c.drawLine(x, topBarY + 18, x, topBarY + 34, stroke)
        }
        text.color = CYAN
    }

    private fun drawGauges(c: Canvas, t: Float) {
        val s = stats
        val items = listOf(
            Triple("BATTERY", s.battery, "${s.battery}%" + if (s.charging) " ⚡" else ""),
            Triple("WEATHER", s.humidity, s.temp?.let { "${it.roundToInt()}°" } ?: "--°"),
            Triple("MEMORY", s.memory, "${s.memory}%"),
            Triple("STORAGE", s.storage, "${s.storage}%"),
        )
        for ((i, g) in gauges.withIndex()) {
            val (label, v, value) = items[i]
            val col = if (i == 0 && s.battery <= 20 && !s.charging) RED else CYAN
            arc(c, g.x, g.y, gaugeR * 0.8f, gaugeR * 0.1f, -90f, v.coerceIn(0, 100) * 3.6f, col)
            arc(c, g.x, g.y, gaugeR + 30, 4f, t * (20 + i * 7) % 360, 40f, cyan(170), false)
            text.textAlign = Paint.Align.CENTER
            text.color = col; text.textSize = gaugeR * 0.36f
            c.drawText(value, g.x, g.y + gaugeR * 0.12f, text)
            text.color = cyan(180); text.textSize = gaugeR * 0.15f; text.letterSpacing = 0.15f
            c.drawText(label, g.x, g.y + gaugeR * 0.38f, text)
            if (i == 1) {
                text.textSize = gaugeR * 0.14f; text.color = cyan(200)
                c.drawText(s.weather, g.x, g.y - gaugeR * 0.3f, text)
            }
            text.letterSpacing = 0f; text.color = CYAN
        }
    }

    private fun drawInfo(c: Canvas) {
        if (infoRect.isEmpty) return
        text.textAlign = Paint.Align.LEFT
        val fs = infoRect.height() * 0.17f
        text.textSize = fs; text.color = CYAN; text.letterSpacing = 0.15f
        c.drawText("FORECAST", infoRect.left + fs, infoRect.top + fs * 1.5f, text)
        text.letterSpacing = 0f; text.color = cyan(210); text.textSize = fs * 0.95f
        stats.forecast.take(3).forEachIndexed { i, line ->
            c.drawText(line, infoRect.left + fs, infoRect.top + fs * (2.9f + i * 1.3f), text)
        }
        text.color = CYAN
    }

    private fun drawDock(c: Canvas) {
        if (dockCells.size != (dock.size + 1).coerceAtMost(6)) layoutDock()
        text.textAlign = Paint.Align.CENTER
        for ((i, r) in dockCells.withIndex()) {
            val isApps = i == dockCells.lastIndex
            fill.color = if (isApps) cyan(40) else Color.argb(90, 0, 30, 50); c.drawRect(r, fill)
            stroke.strokeWidth = 2f; stroke.color = cyan(if (isApps) 230 else 150); c.drawRect(r, stroke)
            stroke.strokeWidth = 5f; stroke.color = CYAN
            c.drawLine(r.left, r.top, r.left + r.width() * 0.2f, r.top, stroke)
            val label = if (isApps) "APPS  ▸" else dock[i].label
            text.textSize = min(r.height() * 0.32f, 44f); text.color = CYAN
            var shown = label
            while (text.measureText(shown) > r.width() * 0.88f && shown.length > 1) shown = shown.dropLast(1)
            if (shown != label) shown = shown.dropLast(1) + "…"
            c.drawText(shown, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
        }
        if (dock.isEmpty()) {
            text.textSize = dockRect.height() * (if (wide) 0.14f else 0.07f); text.color = cyan(140)
            c.drawText("APPS を開いてアプリを長押しするとここに登録", dockRect.centerX(), dockRect.top - 18, text)
            text.color = CYAN
        }
    }

    // ---------- input ----------
    override fun onTouchEvent(e: MotionEvent): Boolean = gestures.onTouchEvent(e)

    private fun dockIndexAt(x: Float, y: Float) = dockCells.indexOfFirst { it.contains(x, y) }

    private fun tap(x: Float, y: Float) {
        val d = dockIndexAt(x, y)
        if (d >= 0) {
            if (d == dockCells.lastIndex) listener?.onOpenDrawer() else listener?.onLaunch(dock[d])
            return
        }
        gauges.forEachIndexed { i, g -> if (hypot(x - g.x, y - g.y) < gaugeR * 1.2f) { listener?.onGaugeTap(i); return } }
        if (hypot(x - cx, y - cy) < coreR * 0.66f) { listener?.onClockTap(); return }
    }

    private fun longPress(x: Float, y: Float) {
        val d = dockIndexAt(x, y)
        if (d in dock.indices) { listener?.onDockLongPress(dock[d]); return }
        if (y < topBarY + 60) listener?.onHomeSettings()
    }
}

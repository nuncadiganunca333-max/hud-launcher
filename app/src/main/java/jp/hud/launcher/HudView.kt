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
    var battTemp: Float = 0f,
    var voltage: Int = 0,
    var memory: Int = 0,
    var storage: Int = 0,
    var uptimeMs: Long = 0,
    var rxKbps: Float = 0f,
    var txKbps: Float = 0f,
    var rxHist: List<Float> = emptyList(),
    var txHist: List<Float> = emptyList(),
    var memHist: List<Float> = emptyList(),
    var battHist: List<Float> = emptyList(),
    var temp: Float? = null,
    var feels: Float? = null,
    var humidity: Int = 0,
    var wind: Float = 0f,
    var weather: String = "---",
    var sunrise: String = "--:--",
    var sunset: String = "--:--",
    var forecast: List<String> = emptyList(),
)

class HudView(context: Context) : View(context) {

    interface Listener {
        fun onOpenDrawer()
        fun onLaunch(app: AppEntry)
        fun onDockLongPress(app: AppEntry)
        fun onPanelTap(panel: String)
        fun onClockTap()
        fun onHomeSettings()
        fun onTopStripTap()
    }

    var listener: Listener? = null
    var dock: List<AppEntry> = emptyList()
        set(v) { field = v; invalidate() }
    var notifLink = false
        set(v) { field = v; invalidate() }
    var voiceOn = true
        set(v) { field = v; invalidate() }
    var stats = Stats()
        set(v) { field = v; invalidate() }

    private val CYAN = Color.rgb(0, 229, 255)
    private val RED = Color.rgb(255, 70, 70)
    private val DARK = Color.rgb(1, 10, 20)
    private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 0, 229, 255)

    private var running = false
    private val startTime = System.nanoTime()
    private var staticLayer: Bitmap? = null
    private var insetTop = 0f
    private var insetBottom = 0f

    // ---- layout ----
    private var wide = true
    private var unit = 40f
    private var cx = 0f; private var cy = 0f; private var coreR = 0f
    private val topStrip = RectF()
    private val statusStrip = RectF()
    private val panels = LinkedHashMap<String, RectF>()

    // ---- paints ----
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(6f, 12f), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val textGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan(170); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
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

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top.toFloat(); insetBottom = bottom.toFloat()
        if (width > 0) relayout(width, height)
    }

    fun start() { if (!running) { running = true; invalidate() } }
    fun stop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout(w, h) }

    // ======================= LAYOUT =======================
    private fun relayout(w: Int, h: Int) {
        val W = w.toFloat(); val H = h.toFloat()
        wide = W / H > 0.75f
        panels.clear()
        unit = if (wide) min(W, H) / 40f else W / 26f
        val m = unit * 0.6f
        val gap = unit * 0.45f
        topStrip.set(m, insetTop + unit * 0.4f, W - m, insetTop + unit * 1.8f)
        val top = topStrip.bottom + unit * 0.8f
        val bottom = H - insetBottom - unit * 0.6f

        if (wide) {
            val colBottom = top + (bottom - top) * 0.78f
            val lx2 = W * 0.255f; val rx1 = W * 0.745f
            val lh = colBottom - top
            panels["SYSTEM"] = RectF(m, top, lx2, top + lh * 0.52f - gap / 2)
            panels["POWER"] = RectF(m, top + lh * 0.52f + gap / 2, lx2, colBottom)
            panels["CALENDAR"] = RectF(rx1, top, W - m, top + lh * 0.38f - gap / 2)
            panels["WEATHER"] = RectF(rx1, top + lh * 0.38f + gap / 2, W - m, top + lh * 0.74f - gap / 2)
            panels["FORECAST"] = RectF(rx1, top + lh * 0.74f + gap / 2, W - m, colBottom)
            panels["NETWORK"] = RectF(m, colBottom + gap, W - m, bottom)
            statusStrip.set(W * 0.28f, colBottom - unit * 1.9f, W * 0.72f, colBottom)
            cx = W / 2
            cy = top + (statusStrip.top - top) * 0.5f
            coreR = min(W * 0.205f, (statusStrip.top - top) * 0.42f)
        } else {
            cx = W / 2; coreR = W * 0.355f
            cy = top + coreR * 1.18f
            val pTop = cy + coreR * 1.22f
            val ph = bottom - pTop
            val mid = W / 2
            val r1 = pTop + ph * 0.36f; val r2 = pTop + ph * 0.70f
            panels["SYSTEM"] = RectF(m, pTop, mid - gap / 2, r1 - gap / 2)
            panels["WEATHER"] = RectF(mid + gap / 2, pTop, W - m, r1 - gap / 2)
            panels["POWER"] = RectF(m, r1 + gap / 2, mid - gap / 2, r2 - gap / 2)
            panels["CALENDAR"] = RectF(mid + gap / 2, r1 + gap / 2, W - m, r2 - gap / 2)
            panels["NETWORK"] = RectF(m, r2 + gap / 2, W - m, bottom)
            statusStrip.setEmpty()
        }
        staticLayer?.recycle()
        staticLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawStatic(Canvas(it), W, H) }
        invalidate()
    }

    // ======================= STATIC =======================
    private fun drawStatic(c: Canvas, W: Float, H: Float) {
        fill.shader = RadialGradient(cx, cy, max(W, H) * 0.8f,
            intArrayOf(Color.rgb(8, 44, 72), Color.rgb(3, 18, 34), Color.rgb(1, 6, 13)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill); fill.shader = null

        // fine + coarse grid
        stroke.strokeWidth = 1f
        var i = 0; var x = 0f
        while (x < W) { stroke.color = cyan(if (i % 4 == 0) 22 else 9); c.drawLine(x, 0f, x, H, stroke); x += unit; i++ }
        i = 0; var y = 0f
        while (y < H) { stroke.color = cyan(if (i % 4 == 0) 22 else 9); c.drawLine(0f, y, W, y, stroke); y += unit; i++ }

        val rnd = Random(11)
        stroke.strokeWidth = 2f
        repeat(26) {
            var px = rnd.nextFloat() * W; var py = rnd.nextFloat() * H
            val p = Path().apply { moveTo(px, py) }
            repeat(rnd.nextInt(2, 4)) {
                if (rnd.nextBoolean()) px += (if (rnd.nextBoolean()) 1 else -1) * rnd.nextInt(60, 260)
                else { val d = rnd.nextInt(40, 140).toFloat(); px += if (rnd.nextBoolean()) d else -d; py += if (rnd.nextBoolean()) d else -d }
                p.lineTo(px, py)
            }
            stroke.color = cyan(rnd.nextInt(25, 70)); c.drawPath(p, stroke)
            stroke.color = cyan(140); c.drawCircle(px, py, 4f, stroke)
        }
        repeat(160) {
            fill.color = cyan(rnd.nextInt(40, 180))
            c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H, rnd.nextInt(1, 3).toFloat(), fill)
        }

        // core static decoration
        ticks(c, coreR * 1.13f, 144, unit * 0.22f, cyan(120))
        ring(c, coreR * 1.02f, 2f, cyan(120))
        ring(c, coreR * 1.2f, 1.5f, cyan(60))
        ticks(c, coreR * 0.70f, 90, unit * 0.15f, cyan(110))
        // crosshair lines out of core
        stroke.strokeWidth = 1.5f; stroke.color = cyan(70)
        c.drawLine(cx - coreR * 1.45f, cy, cx - coreR * 1.24f, cy, stroke)
        c.drawLine(cx + coreR * 1.24f, cy, cx + coreR * 1.45f, cy, stroke)
        c.drawLine(cx, cy - coreR * 1.38f, cx, cy - coreR * 1.24f, stroke)

        for ((name, r) in panels) panelFrame(c, r, name)
        if (!statusStrip.isEmpty) panelFrame(c, statusStrip, null)
        // top strip
        stroke.strokeWidth = 2f; stroke.color = cyan(150)
        c.drawLine(topStrip.left, topStrip.bottom, topStrip.right, topStrip.bottom, stroke)
        stroke.strokeWidth = 6f; stroke.color = CYAN
        c.drawLine(topStrip.left, topStrip.bottom, topStrip.left + unit * 3, topStrip.bottom, stroke)
        c.drawLine(topStrip.right - unit * 3, topStrip.bottom, topStrip.right, topStrip.bottom, stroke)
    }

    private fun ring(c: Canvas, r: Float, w: Float, col: Int) { stroke.strokeWidth = w; stroke.color = col; c.drawCircle(cx, cy, r, stroke) }

    private fun ticks(c: Canvas, r: Float, n: Int, len: Float, col: Int) {
        stroke.color = col; stroke.strokeWidth = 2f
        for (k in 0 until n) {
            val t = 2 * PI * k / n; val l = if (k % 6 == 0) len * 2 else len
            c.drawLine(cx + r * cos(t).toFloat(), cy + r * sin(t).toFloat(),
                cx + (r - l) * cos(t).toFloat(), cy + (r - l) * sin(t).toFloat(), stroke)
        }
    }

    private fun headerH() = unit * 1.05f

    private fun panelFrame(c: Canvas, r: RectF, title: String?) {
        fill.color = Color.argb(95, 0, 28, 48); c.drawRect(r, fill)
        stroke.strokeWidth = 2f; stroke.color = cyan(130); c.drawRect(r, stroke)
        if (title != null) {
            val hh = headerH()
            fill.color = cyan(38); c.drawRect(r.left, r.top, r.right, r.top + hh, fill)
            stroke.strokeWidth = 1.5f; stroke.color = cyan(150)
            c.drawLine(r.left, r.top + hh, r.right, r.top + hh, stroke)
            label.textAlign = Paint.Align.LEFT; label.textSize = hh * 0.55f; label.letterSpacing = 0.18f; label.color = CYAN
            c.drawText(title, r.left + hh * 0.45f, r.top + hh * 0.7f, label)
            label.letterSpacing = 0f
            fill.color = CYAN
            for (k in 0 until 3) {
                val s = hh * 0.22f; val x = r.right - hh * 0.4f - k * s * 1.8f
                fill.color = cyan(if (k == 0) 255 else 120)
                c.drawRect(x - s, r.top + hh / 2 - s / 2, x, r.top + hh / 2 + s / 2, fill)
            }
        }
        // corner accents
        stroke.strokeWidth = 5f; stroke.color = CYAN
        val a = unit * 1.2f
        c.drawLine(r.left, r.top, r.left + a, r.top, stroke); c.drawLine(r.left, r.top, r.left, r.top + a * 0.6f, stroke)
        c.drawLine(r.right - a, r.bottom, r.right, r.bottom, stroke); c.drawLine(r.right, r.bottom - a * 0.6f, r.right, r.bottom, stroke)
    }

    // ======================= FRAME =======================
    override fun onDraw(c: Canvas) {
        staticLayer?.let { c.drawBitmap(it, 0f, 0f, null) } ?: return
        val t = (System.nanoTime() - startTime) / 1e9f
        val now = Calendar.getInstance()

        drawTopStrip(c, t, now)
        drawCore(c, t, now)
        panels["SYSTEM"]?.let { drawSystem(c, it) }
        panels["POWER"]?.let { drawPower(c, it) }
        panels["CALENDAR"]?.let { drawCalendar(c, it, now) }
        panels["WEATHER"]?.let { drawWeather(c, it) }
        panels["FORECAST"]?.let { drawForecast(c, it) }
        panels["NETWORK"]?.let { drawNetwork(c, it, t) }
        if (!statusStrip.isEmpty) drawStatus(c, statusStrip, t)

        val sy = ((t * 0.1f) % 1f) * height
        stroke.strokeWidth = 2f; stroke.color = cyan(26)
        c.drawLine(0f, sy, width.toFloat(), sy, stroke)

        if (running) postInvalidateDelayed(33)
    }

    private fun hexStream(seed: Long, len: Int): String {
        val r = Random(seed); val sb = StringBuilder()
        repeat(len) { if (it % 5 == 4) sb.append(' ') else sb.append("0123456789ABCDEF"[r.nextInt(16)]) }
        return sb.toString()
    }

    private fun drawTopStrip(c: Canvas, t: Float, now: Calendar) {
        val r = topStrip; val base = r.bottom - unit * 0.4f
        label.textAlign = Paint.Align.LEFT; label.textSize = unit * 0.75f; label.letterSpacing = 0.2f; label.color = CYAN
        c.drawText("◆ HUD//OS", r.left, base, label)
        val lw = label.measureText("◆ HUD//OS  ")
        label.textSize = unit * 0.45f
        label.color = if (notifLink) cyan(170) else RED
        val linkTxt = if (notifLink) "NOTIFY LINK ● ONLINE" + (if (voiceOn) "  VOICE ON" else "  VOICE OFF") else "NOTIFY LINK ○ OFFLINE  (TAP)"
        c.drawText(linkTxt, r.left + lw, base, label)
        label.letterSpacing = 0f
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = unit * 0.62f; mono.color = CYAN
        val wd = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")[now.get(Calendar.DAY_OF_WEEK) - 1]
        val s = String.format(Locale.US, "%04d.%02d.%02d %s %02d:%02d:%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH), wd, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
        c.drawText(s, r.right, base, mono)
        if (wide) {
            mono.textAlign = Paint.Align.CENTER; mono.textSize = unit * 0.42f; mono.color = cyan(110)
            c.drawText(hexStream((t * 6).toLong(), 30), r.centerX(), base, mono)
        }
        mono.color = CYAN
    }

    // ---------- core / radial launcher ----------
    private class Seg(val app: AppEntry?, val isApps: Boolean)

    private fun segments(): List<Seg> {
        val list = ArrayList<Seg>()
        dock.take(7).forEach { list += Seg(it, false) }
        list += Seg(null, true)
        while (list.size < 6) list.add(list.size - 1, Seg(null, false))
        return list
    }

    private val segR1 get() = coreR * 0.78f
    private val segR2 get() = coreR

    private fun drawCore(c: Canvas, t: Float, now: Calendar) {
        val segs = segments()
        val n = segs.size; val sw = 360f / n; val start = -90f - sw / 2
        val r1 = segR1; val r2 = segR2
        val outer = RectF(cx - r2, cy - r2, cx + r2, cy + r2)
        val inner = RectF(cx - r1, cy - r1, cx + r1, cy + r1)
        val mid = (r1 + r2) / 2; val midRect = RectF(cx - mid, cy - mid, cx + mid, cy + mid)
        val highlight = ((t * 0.6f) % n).toInt()
        for ((i, s) in segs.withIndex()) {
            val a0 = start + i * sw + 1.2f; val sweep = sw - 2.4f
            val p = Path()
            p.arcTo(outer, a0, sweep, true); p.arcTo(inner, a0 + sweep, -sweep); p.close()
            fill.color = when { s.isApps -> cyan(85); s.app != null -> cyan(if (i == highlight) 60 else 32); else -> cyan(12) }
            c.drawPath(p, fill)
            stroke.strokeWidth = 2f; stroke.color = cyan(if (s.app != null || s.isApps) 190 else 70); c.drawPath(p, stroke)
            val text = when { s.isApps -> "APPS"; s.app != null -> s.app.label.uppercase(); else -> "- - -" }
            label.textSize = (r2 - r1) * 0.30f; label.textAlign = Paint.Align.LEFT; label.letterSpacing = 0.08f
            label.color = if (s.isApps || s.app != null) CYAN else cyan(90)
            val center = (a0 + sweep / 2 + 360f) % 360f
            val bottomHalf = center in 0f..180f
            val arcLen = (Math.toRadians(sweep.toDouble()) * mid).toFloat()
            var shown = text
            while (label.measureText(shown) > arcLen * 0.84f && shown.length > 1) shown = shown.dropLast(1)
            if (shown != text) shown = shown.dropLast(1) + "…"
            val tp = Path()
            if (bottomHalf) tp.addArc(midRect, a0 + sweep, -sweep) else tp.addArc(midRect, a0, sweep)
            val h = (arcLen - label.measureText(shown)) / 2
            c.drawTextOnPath(shown, tp, h, label.textSize * 0.36f, label)
            label.letterSpacing = 0f
        }

        // rotating outer accents
        arcAt(c, coreR * 1.08f, 4f, t * 14f, 300f, cyan(60), false)
        for (k in 0 until 4) arcAt(c, coreR * 1.08f, 7f, t * 14f + k * 90f, 28f, CYAN, true)
        arcAt(c, coreR * 1.27f, 6f, 210f + sin(t * 0.6f) * 25f, 30f, RED, true)
        for (k in 0 until 10) arcAt(c, coreR * 1.31f, 3f, k * 36f - t * 5f, 14f, cyan(150), false)

        // dashed rotating ring
        c.save(); c.rotate(-t * 22f, cx, cy)
        dash.strokeWidth = 4f; dash.color = cyan(170); c.drawCircle(cx, cy, coreR * 0.64f, dash)
        c.restore()
        // seconds sweep
        val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        arcAt(c, coreR * 0.585f, 6f, -90f, sec / 60f * 360f, CYAN, true)

        // orbit dots
        for (k in 0 until 3) {
            val a = t * (0.9f + k * 0.35f) + k * 2.1f
            fill.color = CYAN
            c.drawCircle(cx + cos(a) * coreR * 0.72f, cy + sin(a) * coreR * 0.72f, unit * 0.13f, fill)
        }

        // hexagon core
        val hr = coreR * 0.5f
        fill.shader = RadialGradient(cx, cy, hr, intArrayOf(cyan(70), cyan(18), cyan(0)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(hexPath(hr, t * 6f), fill); fill.shader = null
        val pulse = (sin(t * 2.2f) + 1) / 2
        glow.strokeWidth = 14f; glow.color = cyan((90 + 90 * pulse).toInt()); c.drawPath(hexPath(hr, t * 6f), glow)
        stroke.strokeWidth = 4f; stroke.color = CYAN; c.drawPath(hexPath(hr, t * 6f), stroke)
        stroke.strokeWidth = 1.5f; stroke.color = cyan(120); c.drawPath(hexPath(hr * 0.86f, -t * 10f), stroke)

        // clock text
        val hm = String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val big = coreR * 0.27f
        thin.textSize = big; textGlow.textSize = big
        c.drawText(hm, cx, cy + big * 0.33f, textGlow); c.drawText(hm, cx, cy + big * 0.33f, thin)
        mono.textAlign = Paint.Align.CENTER; mono.textSize = coreR * 0.06f; mono.color = cyan(210)
        c.drawText(String.format(Locale.US, "%02d.%02d  SEC %02d", now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH), sec.toInt()),
            cx, cy - big * 0.62f, mono)
        val wd = now.get(Calendar.DAY_OF_WEEK) - 1
        label.textAlign = Paint.Align.CENTER; label.textSize = coreR * 0.065f; label.color = cyan(220); label.letterSpacing = 0.25f
        c.drawText(arrayOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")[wd], cx, cy + big * 0.95f, label)
        label.letterSpacing = 0f; label.color = CYAN; mono.color = CYAN
    }

    private fun hexPath(r: Float, rotDeg: Float): Path {
        val p = Path()
        for (k in 0..6) {
            val a = Math.toRadians((rotDeg + 30 + k * 60).toDouble())
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close(); return p
    }

    private fun arcAt(c: Canvas, r: Float, w: Float, start: Float, sweep: Float, col: Int, withGlow: Boolean) {
        val o = RectF(cx - r, cy - r, cx + r, cy + r)
        if (withGlow) { glow.strokeWidth = w * 1.8f; glow.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col)); c.drawArc(o, start, sweep, false, glow) }
        stroke.strokeWidth = w; stroke.color = col; c.drawArc(o, start, sweep, false, stroke)
    }

    // ---------- panels ----------
    private fun body(r: RectF) = RectF(r.left + unit * 0.5f, r.top + headerH() + unit * 0.4f, r.right - unit * 0.5f, r.bottom - unit * 0.4f)

    private fun blockBar(c: Canvas, x: Float, y: Float, w: Float, h: Float, v: Int, warn: Boolean) {
        val n = 20; val gap = w * 0.008f; val bw = (w - gap * (n - 1)) / n
        val on = (v.coerceIn(0, 100) * n / 100f).roundToInt()
        for (k in 0 until n) {
            fill.color = if (k < on) (if (warn) RED else CYAN) else cyan(30)
            c.drawRect(x + k * (bw + gap), y, x + k * (bw + gap) + bw, y + h, fill)
        }
    }

    private fun graph(c: Canvas, r: RectF, data: List<Float>, maxV: Float, col: Int, gridLines: Boolean = true) {
        if (gridLines) {
            stroke.strokeWidth = 1f; stroke.color = cyan(30)
            for (k in 1..3) { val y = r.top + r.height() * k / 4; c.drawLine(r.left, y, r.right, y, stroke) }
            for (k in 1..7) { val x = r.left + r.width() * k / 8; c.drawLine(x, r.top, x, r.bottom, stroke) }
        }
        stroke.strokeWidth = 1.5f; stroke.color = cyan(80); c.drawRect(r, stroke)
        if (data.size < 2) return
        val mx = max(maxV, 1e-3f)
        val line = Path(); val area = Path()
        val step = r.width() / (data.size - 1)
        data.forEachIndexed { i, v ->
            val x = r.left + i * step; val y = r.bottom - (v / mx).coerceIn(0f, 1f) * r.height() * 0.92f
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, r.bottom); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(r.right, r.bottom); area.close()
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            Color.argb(120, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(5, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP)
        c.drawPath(area, fill); fill.shader = null
        stroke.strokeWidth = 3f; stroke.color = col; c.drawPath(line, stroke)
    }

    private fun kv(c: Canvas, x1: Float, x2: Float, y: Float, k: String, v: String, size: Float, vcol: Int = CYAN) {
        label.textAlign = Paint.Align.LEFT; label.textSize = size; label.color = cyan(170); label.letterSpacing = 0.1f
        c.drawText(k, x1, y, label); label.letterSpacing = 0f
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = size; mono.color = vcol
        c.drawText(v, x2, y, mono); mono.color = CYAN; label.color = CYAN
    }

    private fun drawSystem(c: Canvas, r: RectF) {
        val b = body(r); val s = stats
        val fs = min(unit * 0.62f, b.height() / 11f)
        val rows = listOf(Triple("MEMORY", s.memory, s.memory > 90), Triple("STORAGE", s.storage, s.storage > 90),
            Triple("BATTERY", s.battery, s.battery <= 20 && !s.charging))
        var y = b.top + fs
        for ((k, v, warn) in rows) {
            kv(c, b.left, b.right, y, k, "$v%", fs, if (warn) RED else CYAN)
            blockBar(c, b.left, y + fs * 0.4f, b.width(), fs * 0.75f, v, warn)
            y += fs * 2.6f
        }
        val g = RectF(b.left, y - fs * 0.2f, b.right, b.bottom)
        if (g.height() > fs * 2) {
            label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.7f; label.color = cyan(140)
            c.drawText("MEMORY LOAD / 2 MIN", g.left + fs * 0.3f, g.top + fs * 0.9f, label); label.color = CYAN
            graph(c, g, s.memHist, 100f, CYAN)
        }
    }

    private fun drawPower(c: Canvas, r: RectF) {
        val b = body(r); val s = stats
        val fs = min(unit * 0.62f, b.height() / 10f)
        val bigS = fs * 3.2f
        val col = if (s.battery <= 20 && !s.charging) RED else CYAN
        mono.textAlign = Paint.Align.LEFT; mono.textSize = bigS; mono.color = col
        c.drawText("${s.battery}", b.left, b.top + bigS * 0.85f, mono)
        val w = mono.measureText("${s.battery}")
        mono.textSize = fs * 1.2f; c.drawText("%", b.left + w + fs * 0.2f, b.top + bigS * 0.85f, mono)
        label.textAlign = Paint.Align.RIGHT; label.textSize = fs * 0.8f; label.letterSpacing = 0.15f
        label.color = if (s.charging) CYAN else cyan(160)
        c.drawText(if (s.charging) "⚡ CHARGING" else "DISCHARGING", b.right, b.top + fs, label)
        label.letterSpacing = 0f; label.color = CYAN; mono.color = CYAN
        var y = b.top + bigS * 0.85f + fs * 1.6f
        kv(c, b.left, b.right, y, "TEMP", String.format(Locale.US, "%.1f°C", s.battTemp), fs, if (s.battTemp >= 42) RED else CYAN); y += fs * 1.5f
        kv(c, b.left, b.right, y, "VOLTAGE", String.format(Locale.US, "%.2fV", s.voltage / 1000f), fs); y += fs * 1.5f
        val up = s.uptimeMs / 1000
        kv(c, b.left, b.right, y, "UPTIME", String.format(Locale.US, "%dd %02d:%02d", up / 86400, up / 3600 % 24, up / 60 % 60), fs); y += fs * 0.8f
        val g = RectF(b.left, y, b.right, b.bottom)
        if (g.height() > fs * 2) {
            graph(c, g, s.battHist, 100f, col)
            label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.7f; label.color = cyan(140)
            c.drawText("BATTERY LEVEL / 2 H", g.left + fs * 0.3f, g.top + fs * 0.9f, label); label.color = CYAN
        }
    }

    private fun drawCalendar(c: Canvas, r: RectF, now: Calendar) {
        val b = body(r)
        val cal = now.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val first = cal.get(Calendar.DAY_OF_WEEK) - 1
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = now.get(Calendar.DAY_OF_MONTH)
        val rows = (first + days + 6) / 7
        val headH = b.height() * 0.16f
        label.textAlign = Paint.Align.LEFT; label.textSize = headH * 0.7f; label.letterSpacing = 0.2f
        val mn = arrayOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")
        c.drawText("${mn[now.get(Calendar.MONTH)]} ${now.get(Calendar.YEAR)}", b.left, b.top + headH * 0.75f, label)
        label.letterSpacing = 0f
        val cw = b.width() / 7; val gTop = b.top + headH * 1.15f
        val ch = (b.bottom - gTop) / (rows + 1)
        mono.textAlign = Paint.Align.CENTER
        val fs = min(ch * 0.55f, cw * 0.5f)
        val wk = arrayOf("S", "M", "T", "W", "T", "F", "S")
        for (k in 0 until 7) {
            mono.textSize = fs * 0.85f; mono.color = if (k == 0) Color.argb(200, 255, 110, 110) else cyan(150)
            c.drawText(wk[k], b.left + cw * (k + 0.5f), gTop + ch * 0.65f, mono)
        }
        for (d in 1..days) {
            val idx = first + d - 1; val col = idx % 7; val row = idx / 7 + 1
            val x = b.left + cw * (col + 0.5f); val y = gTop + ch * row + ch * 0.65f
            mono.textSize = fs
            if (d == today) {
                fill.color = CYAN
                c.drawRect(x - cw * 0.42f, gTop + ch * row + ch * 0.08f, x + cw * 0.42f, gTop + ch * (row + 1) - ch * 0.08f, fill)
                mono.color = DARK
            } else mono.color = when { d < today -> cyan(80); col == 0 -> Color.argb(220, 255, 110, 110); else -> cyan(220) }
            c.drawText(d.toString(), x, y, mono)
        }
        mono.color = CYAN
    }

    private fun drawWeather(c: Canvas, r: RectF) {
        val b = body(r); val s = stats
        val fs = min(unit * 0.6f, b.height() / (if (wide) 9f else 11f))
        label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.75f; label.color = cyan(160); label.letterSpacing = 0.15f
        c.drawText("SHIMIZU / SHIZUOKA", b.left, b.top + fs * 0.8f, label); label.letterSpacing = 0f
        val bigS = fs * 3.3f
        mono.textAlign = Paint.Align.LEFT; mono.textSize = bigS; mono.color = CYAN
        val tt = s.temp?.let { "${it.roundToInt()}" } ?: "--"
        c.drawText(tt, b.left, b.top + fs * 1.2f + bigS * 0.85f, mono)
        val w = mono.measureText(tt)
        mono.textSize = fs * 1.3f; c.drawText("°C", b.left + w + fs * 0.2f, b.top + fs * 1.2f + bigS * 0.4f, mono)
        label.textAlign = Paint.Align.RIGHT; label.textSize = fs * 1.25f; label.color = CYAN
        c.drawText(s.weather, b.right, b.top + fs * 1.2f + bigS * 0.8f, label)
        var y = b.top + fs * 1.2f + bigS + fs * 1.1f
        kv(c, b.left, b.right, y, "FEELS", s.feels?.let { "${it.roundToInt()}°C" } ?: "--", fs); y += fs * 1.45f
        kv(c, b.left, b.right, y, "HUMIDITY", "${s.humidity}%", fs); y += fs * 1.45f
        kv(c, b.left, b.right, y, "WIND", String.format(Locale.US, "%.1fm/s", s.wind), fs); y += fs * 1.45f
        if (y < b.bottom) { kv(c, b.left, b.right, y, "SUN ↑↓", "${s.sunrise}  ${s.sunset}", fs); y += fs * 1.6f }
        if (!wide) {
            s.forecast.drop(1).take(2).forEach {
                if (y < b.bottom) { label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.95f; label.color = cyan(210); c.drawText(it, b.left, y, label); y += fs * 1.4f }
            }
            label.color = CYAN
        }
    }

    private fun drawForecast(c: Canvas, r: RectF) {
        val b = body(r)
        val rows = stats.forecast.take(3)
        if (rows.isEmpty()) return
        val rh = b.height() / 3
        val fs = min(unit * 0.62f, rh * 0.42f)
        rows.forEachIndexed { i, line ->
            val y = b.top + rh * i + rh * 0.62f
            fill.color = cyan(if (i == 0) 255 else 120)
            c.drawRect(b.left, y - fs * 0.75f, b.left + fs * 0.25f, y + fs * 0.1f, fill)
            label.textAlign = Paint.Align.LEFT; label.textSize = fs; label.color = cyan(if (i == 0) 255 else 210)
            c.drawText(line, b.left + fs * 0.7f, y, label)
            if (i < 2) { stroke.strokeWidth = 1f; stroke.color = cyan(40); c.drawLine(b.left, b.top + rh * (i + 1), b.right, b.top + rh * (i + 1), stroke) }
        }
        label.color = CYAN
    }

    private fun drawNetwork(c: Canvas, r: RectF, t: Float) {
        val b = body(r); val s = stats
        val fs = min(unit * 0.6f, b.height() / 6f)
        val infoW = if (wide) b.width() * 0.16f else b.width() * 0.32f
        // readouts
        label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.75f; label.color = cyan(160); label.letterSpacing = 0.15f
        c.drawText("▼ DOWN", b.left, b.top + fs, label)
        c.drawText("▲ UP", b.left, b.top + b.height() * 0.55f, label)
        label.letterSpacing = 0f; label.color = CYAN
        mono.textAlign = Paint.Align.LEFT; mono.textSize = fs * 1.5f; mono.color = CYAN
        c.drawText(rate(s.rxKbps), b.left, b.top + fs * 2.6f, mono)
        mono.color = Color.rgb(120, 200, 255)
        c.drawText(rate(s.txKbps), b.left, b.top + b.height() * 0.55f + fs * 1.6f, mono)
        mono.color = CYAN
        val g = RectF(b.left + infoW, b.top, b.right, b.bottom)
        val mx = max(max(s.rxHist.maxOrNull() ?: 0f, s.txHist.maxOrNull() ?: 0f), 32f) * 1.15f
        graph(c, g, s.rxHist, mx, CYAN)
        graph(c, g, s.txHist, mx, Color.rgb(120, 200, 255), gridLines = false)
        // moving scan marker
        val sx = g.left + ((t * 0.25f) % 1f) * g.width()
        stroke.strokeWidth = 2f; stroke.color = cyan(90); c.drawLine(sx, g.top, sx, g.bottom, stroke)
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = fs * 0.6f; mono.color = cyan(130)
        c.drawText("PEAK ${rate(mx / 1.15f)}  /  60s", g.right - fs * 0.3f, g.top + fs * 0.9f, mono)
        mono.color = CYAN
    }

    private fun rate(kbps: Float) = if (kbps >= 1024) String.format(Locale.US, "%.1f MB/s", kbps / 1024) else String.format(Locale.US, "%.0f KB/s", kbps)

    private fun drawStatus(c: Canvas, r: RectF, t: Float) {
        val s = stats
        val y1 = r.top + r.height() * 0.42f; val y2 = r.top + r.height() * 0.82f
        val up = s.uptimeMs / 1000
        label.textAlign = Paint.Align.LEFT; label.textSize = r.height() * 0.26f; label.letterSpacing = 0.2f
        val blink = ((t * 1.5f).toInt() % 2 == 0)
        label.color = if (blink) CYAN else cyan(110)
        c.drawText("● ALL SYSTEMS NOMINAL", r.left + unit * 0.6f, y1, label)
        label.letterSpacing = 0f; label.color = CYAN
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = r.height() * 0.24f; mono.color = cyan(200)
        c.drawText(String.format(Locale.US, "UPTIME %02d:%02d:%02d", up / 3600, up / 60 % 60, up % 60), r.right - unit * 0.6f, y1, mono)
        mono.textAlign = Paint.Align.LEFT; mono.textSize = r.height() * 0.2f; mono.color = cyan(100)
        c.drawText(hexStream((t * 4).toLong() + 99, 44), r.left + unit * 0.6f, y2, mono)
        mono.color = CYAN
    }

    // ======================= INPUT =======================
    override fun onTouchEvent(e: MotionEvent): Boolean = gestures.onTouchEvent(e)

    private fun segmentAt(x: Float, y: Float): Seg? {
        val d = hypot(x - cx, y - cy)
        if (d < segR1 || d > segR2 * 1.05f) return null
        val segs = segments(); val n = segs.size; val sw = 360f / n
        var ang = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        ang = (ang - (-90f - sw / 2) + 720f) % 360f
        return segs[(ang / sw).toInt().coerceIn(0, n - 1)]
    }

    private fun tap(x: Float, y: Float) {
        if (y < topStrip.bottom + unit * 0.5f && x < width * 0.5f) { listener?.onTopStripTap(); return }
        segmentAt(x, y)?.let { s ->
            when { s.isApps || s.app == null -> listener?.onOpenDrawer(); else -> listener?.onLaunch(s.app) }
            return
        }
        if (hypot(x - cx, y - cy) < coreR * 0.55f) { listener?.onClockTap(); return }
        for ((name, r) in panels) if (r.contains(x, y)) { listener?.onPanelTap(name); return }
    }

    private fun longPress(x: Float, y: Float) {
        segmentAt(x, y)?.app?.let { listener?.onDockLongPress(it); return }
        if (y < topStrip.bottom + unit) listener?.onHomeSettings()
    }
}

package jp.hud.launcher

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
    var hourly: List<Float> = emptyList(),
    var place: String = "SHIMIZU / SHIZUOKA",
)

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val duration: Long,
    val position: Long,
    val updateTime: Long,
    val speed: Float,
)

class HudView(context: Context) : View(context), SensorEventListener {

    interface Listener {
        fun onOpenDrawer()
        fun onLaunch(app: AppEntry, src: Rect?)
        fun onMediaAction(action: String)
        fun onDockLongPress(app: AppEntry)
        fun onPanelTap(panel: String)
        fun onClockTap()
        fun onHomeSettings()
        fun onTopStripTap()
        fun onCoreLongPress()
        fun onModuleRect(r: RectF)
        fun onModuleEdit()
        fun onPanelsAlpha(a: Float)
        fun onScan()
        fun onPay()
        fun onUserActivity()
    }

    var listener: Listener? = null
    var dock: List<AppEntry> = emptyList()
        set(v) { field = v; invalidate() }
    var notifLink = false
        set(v) { field = v; invalidate() }
    var voiceOn = true
        set(v) { field = v; invalidate() }
    var stats = Stats()
        set(v) {
            if (statsInit && v.charging != field.charging) { chargeEventAt = System.currentTimeMillis(); chargeOn = v.charging }
            statsInit = true
            field = v; invalidate()
        }
    private var statsInit = false
    private var chargeEventAt = 0L
    private var chargeOn = false
    var thinking = false
    var userTag = "YUDAI"
    var gl: GLBackground? = null
    var payEnabled = false
        set(v) { field = v; invalidate() }
    var interactive = true
    var gestureState = 0       // 0=OFF, 1=ON, 2=ジェスチャーON, -1=エラー
    var gestureMotion = 0f
    private var gestureHitAt = 0L
    fun gestureHit() { gestureHitAt = System.currentTimeMillis(); invalidate() }           // 外部ディスプレイ用はタッチ無効
    // ワークショップモード（ヒンジ位置。-1 で通常）
    var hingeY = -1f
        private set
    val workshop get() = hingeY > 0f && wide
    fun setHinge(y: Float) {
        if (abs(y - hingeY) < 1f) return
        hingeY = y; bootStart = System.currentTimeMillis(); expanded = null
        if (width > 0) relayout(width, height)
    }
    // 指で操作するホログラム
    private var globeAX = 0f; private var globeAY = 0f
    private var globeVX = 0f; private var globeVY = 0f
    private var globeZoom = 1f
    private var downInCore = false
    private var dragging = false
    private val swapFx = HashMap<String, Long>()
    private var swipeFxAt = 0L; private var swipeDir = 0
    // ---- external inputs for effects ----
    var geoLat = 35.02; var geoLon = 138.48
    var speaking = false          // assistant voice output
    var voiceLevel = 0f           // mic level while listening
    var spectrum: FloatArray? = null  // music FFT bands 0..1
    var idleTimeoutMs = 45000L
    var talkHint = "TAP TO TALK"
    var lowPower = false
    val lastTouchAt get() = lastTouch
    var moduleLinked = false
        set(v) { field = v; invalidate() }
    fun moduleBody(): RectF? = panels["MODULE"]?.let { RectF(it.left + unit * 0.35f, it.top + headerH() + unit * 0.25f, it.right - unit * 0.35f, it.bottom - unit * 0.3f) }
    var media: MediaInfo? = null
        set(v) { field = v; invalidate() }

    // launch flash
    private var flashRect: RectF? = null
    private var flashLabel = ""
    private var flashStart = 0L

    fun flashLaunch(src: Rect, name: String) {
        flashRect = RectF(src); flashLabel = name; flashStart = System.currentTimeMillis(); invalidate()
    }

    private val CYAN = Color.rgb(120, 222, 255)
    private val BLUE = Color.rgb(120, 200, 255)
    private val RED = Color.rgb(255, 70, 70)
    private val DARK = Color.rgb(1, 10, 20)
    private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 120, 222, 255)
    private fun withA(col: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))

    private var running = false
    private val startTime = System.nanoTime()
    private var bootStart = 0L
    private var bgLayer: Bitmap? = null
    private var fgLayer: Bitmap? = null
    private var ovLayer: Bitmap? = null
    private var bgMargin = 0
    private var insetTop = 0f
    private var insetBottom = 0f

    // smoothed display values (count-up effect)
    private var dBatt = 0f; private var dMem = 0f; private var dSto = 0f; private var dTemp = 0f

    // parallax
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var gx = 0f; private var gy = 0f; private var bx = 0f; private var by = 0f
    private var px = 0f; private var py = 0f
    private var dX = 0f; private var dY = 0f
    private val camera = android.graphics.Camera()
    private var lastTouch = System.currentTimeMillis()
    private var idleP = 0f
    private var lastPanelsAlpha = -1f
    private class Ripple(val x: Float, val y: Float, val t0: Long)
    private val ripples = ArrayList<Ripple>()
    private var incomingAt = 0L
    private var expanded: String? = null
    private var expandAt = 0L
    private var collapsing = false
    private val expandRect = RectF()
    private val expandOpenBtn = RectF()
    private var lastWide: Boolean? = null
    private var speakEnv = 0f
    private val m3d = Matrix()

    // ---- layout ----
    private var wide = true
    private var unit = 40f
    private var cx = 0f; private var cy = 0f; private var coreR = 0f
    private val topStrip = RectF()
    private val statusStrip = RectF()
    private val panels = LinkedHashMap<String, RectF>()

    // ---- paints ----
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // 毎フレームのぼかし処理は重いので、太く薄い加算の線で光を表現する
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.32f, 0f)))
    }
    private val glowStatic = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS
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
    private val monoGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); style = Paint.Style.STROKE; strokeWidth = 6f; blendMode = BlendMode.PLUS
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.3f, 0f)))
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val textGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan(170); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    private val layerPaint = Paint()

    private val scaler = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            globeZoom = (globeZoom * d.scaleFactor).coerceIn(0.6f, 2.4f); invalidate(); return true
        }
    })

    private fun inCore(x: Float, y: Float) = hypot(x - cx - dX * 0.3f, y - cy - dY * 0.3f) < coreR * 0.72f
    private var coreTouchAt = 0L

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            downInCore = inCore(e.x, e.y); dragging = false
            if (downInCore) {
                globeVX = 0f; globeVY = 0f; coreTouchAt = System.currentTimeMillis()
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
            }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!downInCore) return false
            dragging = true
            val k = 1f / (coreR * 0.42f)
            globeAY -= dx * k; globeAX = (globeAX - dy * k).coerceIn(-1.2f, 1.2f)
            globeVY = -dx * k * 0.6f; globeVX = -dy * k * 0.6f
            invalidate(); return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) { longPress(e.x, e.y) }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (downInCore) {
                val k = 1f / (coreR * 0.42f) / 60f
                globeVY = (vx * k).coerceIn(-0.4f, 0.4f); globeVX = (vy * k).coerceIn(-0.2f, 0.2f)
                dragging = false; invalidate(); return true
            }
            val start = e1 ?: return false
            val panel = panels.entries.firstOrNull { it.key != "MODULE" && it.value.contains(start.x, start.y) }?.key
            if (panel != null && max(abs(vx), abs(vy)) > 1500) {
                if (abs(vx) > abs(vy)) flingPanel(panel, if (vx > 0) 1 else -1, 0) else flingPanel(panel, 0, if (vy > 0) 1 else -1)
                return true
            }
            if (vy < -1200 && abs(vy) > abs(vx)) { SoundFx.play(SoundFx.Fx.OPEN); listener?.onOpenDrawer(); return true }
            return false
        }
    })

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top.toFloat(); insetBottom = bottom.toFloat()
        if (width > 0) relayout(width, height)
    }

    private var openingAt = 0L
    private var lastStopAt = 0L
    private val OPEN_DELAY = 650L

    fun start() {
        if (running) return
        running = true
        val now = System.currentTimeMillis()
        // しばらくぶり（10分以上）ならフルのオープニング、すぐ戻ったときは短い演出
        val cinematic = lastStopAt == 0L || now - lastStopAt > 10 * 60_000
        openingAt = if (cinematic) now else 0L
        bootStart = if (cinematic) now + OPEN_DELAY else now
        dBatt = 0f; dMem = 0f; dSto = 0f; dTemp = 0f
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        invalidate()
    }

    fun stop() { running = false; lastStopAt = System.currentTimeMillis(); sensors.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        gx = e.values[0] / 9.81f; gy = e.values[1] / 9.81f
        if (bx == 0f && by == 0f) { bx = gx; by = gy }
        bx += (gx - bx) * 0.015f; by += (gy - by) * 0.015f
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout(w, h) }

    // ======================= LAYOUT =======================
    private fun relayout(w: Int, h: Int) {
        val W = w.toFloat(); val H = h.toFloat()
        wide = W / H > 0.75f
        if (lastWide != null && lastWide != wide) { bootStart = System.currentTimeMillis(); expanded = null }
        lastWide = wide
        panels.clear()
        unit = if (wide) min(W, H) / 40f else W / 26f
        val m = unit * 0.6f
        val gap = unit * 0.45f
        topStrip.set(m, insetTop + unit * 0.4f, W - m, insetTop + unit * 1.8f)
        val top = topStrip.bottom + unit * 0.8f
        val bottom = H - insetBottom - unit * 0.6f

        if (workshop) {
            // 上：ホログラム（コア＋左右のパネル）／下：操作コンソール
            val hy = hingeY
            val upBottom = hy - unit * 1.2f
            val lowTop = hy + unit * 1.2f
            val sideW = W * 0.24f
            panels["SYSTEM"] = RectF(m, top, m + sideW, upBottom)
            panels["WEATHER"] = RectF(W - m - sideW, top, W - m, upBottom)
            cx = W / 2; cy = top + (upBottom - top) * 0.5f
            coreR = min((W - sideW * 2) * 0.36f, (upBottom - top) * 0.42f)
            val lh = bottom - lowTop
            val cw = (W - m * 2 - gap * 3) / 4
            fun col(i: Int, span: Int = 1) = m + i * (cw + gap) to m + i * (cw + gap) + cw * span + gap * (span - 1)
            val r1b = lowTop + lh * 0.5f - gap / 2; val r2t = lowTop + lh * 0.5f + gap / 2
            col(0).let { (a, b) -> panels["CALENDAR"] = RectF(a, lowTop, b, r1b) }
            col(1).let { (a, b) -> panels["MEDIA"] = RectF(a, lowTop, b, r1b) }
            col(2).let { (a, b) -> panels["POWER"] = RectF(a, lowTop, b, r1b) }
            col(3).let { (a, b) -> panels["MODULE"] = RectF(a, lowTop, b, bottom) }
            col(0, 3).let { (a, b) -> panels["NETWORK"] = RectF(a, r2t, b, bottom) }
            statusStrip.setEmpty()
        } else if (wide) {
            val colBottom = top + (bottom - top) * 0.78f
            val lx2 = W * 0.255f; val rx1 = W * 0.745f
            val lh = colBottom - top
            panels["SYSTEM"] = RectF(m, top, lx2, top + lh * 0.50f - gap / 2)
            panels["POWER"] = RectF(m, top + lh * 0.50f + gap / 2, lx2, colBottom)
            panels["CALENDAR"] = RectF(rx1, top, W - m, top + lh * 0.36f - gap / 2)
            panels["WEATHER"] = RectF(rx1, top + lh * 0.36f + gap / 2, W - m, top + lh * 0.76f - gap / 2)
            panels["MEDIA"] = RectF(rx1, top + lh * 0.76f + gap / 2, W - m, colBottom)
            panels["NETWORK"] = RectF(m, colBottom + gap, W - m, bottom)
            val modTop = colBottom - unit * 8f
            panels["MODULE"] = RectF(W * 0.28f, modTop, W * 0.72f, colBottom)
            statusStrip.setEmpty()
            cx = W / 2
            val avail = modTop - gap - top
            cy = top + avail * 0.5f
            coreR = min(W * 0.2f, avail * 0.36f)
        } else {
            cx = W / 2; coreR = W * 0.345f
            cy = top + coreR * 1.2f
            val pTop = cy + coreR * 1.24f
            val ph = bottom - pTop
            val mid = W / 2
            val r1 = pTop + ph * 0.37f; val r2 = pTop + ph * 0.71f
            panels["SYSTEM"] = RectF(m, pTop, mid - gap / 2, r1 - gap / 2)
            panels["WEATHER"] = RectF(mid + gap / 2, pTop, W - m, r1 - gap / 2)
            panels["MEDIA"] = RectF(m, r1 + gap / 2, mid - gap / 2, r2 - gap / 2)
            panels["CALENDAR"] = RectF(mid + gap / 2, r1 + gap / 2, W - m, r2 - gap / 2)
            panels["MODULE"] = RectF(m, r2 + gap / 2, W - m, bottom)
            statusStrip.setEmpty()
        }
        applySavedOrder()
        bgLayer?.recycle(); fgLayer?.recycle()
        bgMargin = (unit * 1.6f).toInt()
        bgLayer = Bitmap.createBitmap(w + bgMargin * 2, h + bgMargin * 2, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it); c.translate(bgMargin.toFloat(), bgMargin.toFloat())
            drawBackground(c, -bgMargin.toFloat(), -bgMargin.toFloat(), W + bgMargin, H + bgMargin)
        }
        fgLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawForeground(Canvas(it), W, H) }
        ovLayer?.recycle()
        ovLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawOverlay(Canvas(it), W, H) }
        moduleBody()?.let { listener?.onModuleRect(it) }
        invalidate()
    }

    // ======================= STATIC LAYERS =======================
    private fun drawBackground(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (gl == null) {
            fill.color = Color.WHITE; fill.shader = RadialGradient(cx, cy, max(x1 - x0, y1 - y0) * 0.8f,
                intArrayOf(Color.rgb(8, 46, 76), Color.rgb(3, 18, 34), Color.rgb(1, 5, 11)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(x0, y0, x1, y1, fill); fill.shader = null
        }

        stroke.strokeWidth = 1f
        var i = 0; var x = x0
        while (x < x1) { stroke.color = cyan(if (i % 4 == 0) 20 else 8); c.drawLine(x, y0, x, y1, stroke); x += unit; i++ }
        i = 0; var y = y0
        while (y < y1) { stroke.color = cyan(if (i % 4 == 0) 20 else 8); c.drawLine(x0, y, x1, y, stroke); y += unit; i++ }

        val rnd = Random(11)
        val w = x1 - x0; val h = y1 - y0
        stroke.strokeWidth = 2f
        repeat(26) {
            var qx = x0 + rnd.nextFloat() * w; var qy = y0 + rnd.nextFloat() * h
            val p = Path().apply { moveTo(qx, qy) }
            repeat(rnd.nextInt(2, 4)) {
                if (rnd.nextBoolean()) qx += (if (rnd.nextBoolean()) 1 else -1) * rnd.nextInt(60, 260)
                else { val d = rnd.nextInt(40, 140).toFloat(); qx += if (rnd.nextBoolean()) d else -d; qy += if (rnd.nextBoolean()) d else -d }
                p.lineTo(qx, qy)
            }
            stroke.color = cyan(rnd.nextInt(25, 65)); c.drawPath(p, stroke)
            stroke.color = cyan(130); c.drawCircle(qx, qy, 4f, stroke)
        }
        repeat(180) {
            fill.color = cyan(rnd.nextInt(40, 180))
            c.drawCircle(x0 + rnd.nextFloat() * w, y0 + rnd.nextFloat() * h, rnd.nextInt(1, 3).toFloat(), fill)
        }
    }

    private fun drawForeground(c: Canvas, W: Float, H: Float) {
        for ((name, r) in panels) panelFrame(c, r, name)
        if (!statusStrip.isEmpty) panelFrame(c, statusStrip, null)
        stroke.strokeWidth = 2f; stroke.color = cyan(150)
        c.drawLine(topStrip.left, topStrip.bottom, topStrip.right, topStrip.bottom, stroke)
        stroke.strokeWidth = 6f; stroke.color = CYAN
        c.drawLine(topStrip.left, topStrip.bottom, topStrip.left + unit * 3, topStrip.bottom, stroke)
        c.drawLine(topStrip.right - unit * 3, topStrip.bottom, topStrip.right, topStrip.bottom, stroke)
    }

    private fun drawOverlay(c: Canvas, W: Float, H: Float) {
        // hologram scanlines over everything
        fill.color = Color.argb(14, 0, 0, 0)
        var y = 0f
        while (y < H) { c.drawRect(0f, y, W, y + 2f, fill); y += 5f }
        // vignette
        fill.color = Color.WHITE; fill.shader = RadialGradient(W / 2, H / 2, max(W, H) * 0.75f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 0, 0, 0)),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill); fill.shader = null
    }

    private fun headerH() = unit * 1.05f

    /** 角を斜めに切り落としたパネル形状 */
    private fun chamfer(r: RectF, k: Float): Path = Path().apply {
        moveTo(r.left + k, r.top); lineTo(r.right - k * 0.4f, r.top); lineTo(r.right, r.top + k * 0.4f)
        lineTo(r.right, r.bottom - k); lineTo(r.right - k, r.bottom); lineTo(r.left + k * 0.4f, r.bottom)
        lineTo(r.left, r.bottom - k * 0.4f); lineTo(r.left, r.top + k); close()
    }

    private fun panelFrame(c: Canvas, r: RectF, title: String?) {
        val k = unit * 0.9f
        val shape = chamfer(r, k)
        fill.color = Color.WHITE; fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(195, 4, 26, 44), Color.argb(215, 1, 10, 20), Shader.TileMode.CLAMP)
        c.drawPath(shape, fill); fill.shader = null
        glowStatic.strokeWidth = 6f; glowStatic.color = cyan(60); c.drawPath(shape, glowStatic)
        stroke.strokeWidth = 1.4f; stroke.color = cyan(150); c.drawPath(shape, stroke)
        val inset = RectF(r.left + 6, r.top + 6, r.right - 6, r.bottom - 6)
        stroke.strokeWidth = 0.8f; stroke.color = cyan(35); c.drawPath(chamfer(inset, k * 0.8f), stroke)
        // edge ticks
        stroke.strokeWidth = 1f; stroke.color = cyan(90)
        var ty = r.top + k * 1.6f
        while (ty < r.bottom - k * 1.6f) { c.drawLine(r.right - unit * 0.25f, ty, r.right, ty, stroke); ty += unit * 0.5f }
        if (title != null) {
            val hh = headerH()
            c.save(); c.clipPath(shape)
            fill.color = Color.WHITE; fill.shader = LinearGradient(r.left, 0f, r.right, 0f, cyan(55), cyan(6), Shader.TileMode.CLAMP)
            c.drawRect(r.left, r.top, r.right, r.top + hh, fill); fill.shader = null
            c.restore()
            stroke.strokeWidth = 1f; stroke.color = cyan(140)
            c.drawLine(r.left, r.top + hh, r.right, r.top + hh, stroke)
            label.textAlign = Paint.Align.LEFT; label.textSize = hh * 0.55f; label.letterSpacing = 0.18f; label.color = CYAN
            c.drawText(title, r.left + hh * 0.45f, r.top + hh * 0.7f, label)
            label.letterSpacing = 0f
            for (k in 0 until 3) {
                val s = hh * 0.22f; val x = r.right - hh * 0.4f - k * s * 1.8f
                fill.color = cyan(if (k == 0) 255 else 120)
                c.drawRect(x - s, r.top + hh / 2 - s / 2, x, r.top + hh / 2 + s / 2, fill)
            }
        }
        // bright accents on the cut corners
        stroke.strokeWidth = 3f; stroke.color = Color.argb(255, 210, 245, 255)
        c.drawLine(r.left, r.top + k, r.left + k, r.top, stroke)
        c.drawLine(r.right - k, r.bottom, r.right, r.bottom - k, stroke)
        glowStatic.strokeWidth = 10f; glowStatic.color = cyan(160)
        c.drawLine(r.left, r.top + k, r.left + k, r.top, glowStatic)
        c.drawLine(r.right - k, r.bottom, r.right, r.bottom - k, glowStatic)
    }

    // ======================= FRAME =======================
    private fun ease(p: Float) = 1f - (1f - p.coerceIn(0f, 1f)).pow(3)

    private fun layoutKey() = if (workshop) "ws" else if (wide) "wide" else "narrow"
    private val prefsH by lazy { context.getSharedPreferences("hud", Context.MODE_PRIVATE) }

    /** パネルの並び（指で入れ替えた順）を反映。MODULE（ウィジェット）は固定 */
    private fun applySavedOrder() {
        val names = panels.keys.filter { it != "MODULE" }
        val saved = prefsH.getString("order:" + layoutKey(), null)?.split(",") ?: return
        if (saved.size != names.size || saved.toSet() != names.toSet()) return
        val slots = names.map { RectF(panels[it]) }
        saved.forEachIndexed { i, n -> panels[n] = slots[i] }
    }

    private fun currentOrder(): MutableList<String> {
        val names = panels.keys.filter { it != "MODULE" }
        val saved = prefsH.getString("order:" + layoutKey(), null)?.split(",")
        return if (saved != null && saved.toSet() == names.toSet()) saved.toMutableList() else names.toMutableList()
    }

    private fun swapPanels(a: String, b: String) {
        // 既定の並びで slot を求め直してから入れ替える
        val order = currentOrder()
        val ia = order.indexOf(a); val ib = order.indexOf(b)
        if (ia < 0 || ib < 0) return
        order[ia] = b; order[ib] = a
        prefsH.edit().putString("order:" + layoutKey(), order.joinToString(",")).apply()
        val now = System.currentTimeMillis()
        swapFx[a] = now; swapFx[b] = now
        relayout(width, height)
    }

    private fun flingPanel(from: String, dirX: Int, dirY: Int) {
        val r = panels[from] ?: return
        val cands = panels.filter { it.key != from && it.key != "MODULE" }
        val target = cands.minByOrNull { (_, o) ->
            val dx = o.centerX() - r.centerX(); val dy = o.centerY() - r.centerY()
            val along = dx * dirX + dy * dirY
            if (along <= unit) Float.MAX_VALUE else along + abs(if (dirX != 0) dy else dx) * 2f
        } ?: return
        val dx = target.value.centerX() - r.centerX(); val dy = target.value.centerY() - r.centerY()
        if (dx * dirX + dy * dirY <= unit) return
        SoundFx.play(SoundFx.Fx.OPEN)
        swapPanels(from, target.key)
    }

    /** 手のジェスチャー（空中スワイプ）：パネルを順に全画面で表示 */
    fun airSwipe(dir: Int) {
        wake()
        swipeFxAt = System.currentTimeMillis(); swipeDir = dir
        val order = currentOrder()
        if (order.isEmpty()) return
        val cur = expanded
        val next = if (cur == null) order[if (dir > 0) 0 else order.lastIndex]
            else order[((order.indexOf(cur) + dir) % order.size + order.size) % order.size]
        expanded = next; expandAt = System.currentTimeMillis(); collapsing = false
        invalidate()
    }

    fun closeExpanded() { if (expanded != null) collapse() }

    private fun drawSwipeFx(c: Canvas, nowMs: Long) {
        val age = nowMs - swipeFxAt
        if (swipeFxAt == 0L || age > 500) return
        val p = ease(age / 500f)
        val y = height * 0.5f
        val x = if (swipeDir > 0) width * (-0.2f + 1.4f * p) else width * (1.2f - 1.4f * p)
        val len = width * 0.35f
        val x2 = x - swipeDir * len
        fill.color = Color.WHITE
        fill.shader = LinearGradient(x2, 0f, x, 0f, Color.TRANSPARENT, cyan(120), Shader.TileMode.CLAMP)
        c.drawRect(min(x, x2), y - unit * 3, max(x, x2), y + unit * 3, fill); fill.shader = null
        stroke.strokeWidth = 3f; stroke.color = Color.WHITE; c.drawLine(x, y - unit * 3, x, y + unit * 3, stroke)
        postInvalidateOnAnimation()
    }

    private fun drawCoreTouch(c: Canvas, nowMs: Long) {
        val age = nowMs - coreTouchAt
        if (coreTouchAt == 0L || age > 600) return
        val q = age / 600f
        val x = cx + dX * 0.3f; val y = cy + dY * 0.3f
        glow.strokeWidth = 24f; glow.color = cyan((255 * (1 - q)).toInt()); c.drawCircle(x, y, coreR * (0.45f + 0.35f * ease(q)), glow)
        stroke.strokeWidth = 3f; stroke.color = Color.argb((255 * (1 - q)).toInt(), 220, 248, 255)
        c.drawCircle(x, y, coreR * (0.45f + 0.35f * ease(q)), stroke)
        postInvalidateOnAnimation()
    }

    fun incoming() { incomingAt = System.currentTimeMillis(); wake(); invalidate() }

    fun wake() { lastTouch = System.currentTimeMillis() }

    override fun onDraw(c: Canvas) {
        val bg = bgLayer ?: return; val fg = fgLayer ?: return
        val t = (System.nanoTime() - startTime) / 1e9f
        val nowMs = System.currentTimeMillis()
        val now = Calendar.getInstance()
        val boot = ((nowMs - bootStart) / 1300f).coerceIn(0f, 1f)
        val eb = ease(boot)

        // idle (screensaver) state
        val idleTarget = if (nowMs - lastTouch > idleTimeoutMs && expanded == null) 1f else 0f
        idleP += (idleTarget - idleP) * (if (idleTarget > idleP) 0.03f else 0.12f)

        // smoothing
        val s = stats
        dBatt += (s.battery - dBatt) * 0.08f; dMem += (s.memory - dMem) * 0.08f; dSto += (s.storage - dSto) * 0.08f
        dTemp += ((s.temp ?: 0f) - dTemp) * 0.08f
        speakEnv += ((if (speaking) (0.35f + 0.65f * abs(sin(t * 11f) * sin(t * 4.3f + 1f))) else voiceLevel) - speakEnv) * 0.35f
        // parallax
        val tx = ((gx - bx) * unit * 9f).coerceIn(-unit * 1.4f, unit * 1.4f)
        val ty = ((gy - by) * unit * 9f).coerceIn(-unit * 1.4f, unit * 1.4f)
        px += (tx - px) * 0.15f; py += (ty - py) * 0.15f
        dX = px + sin(t * 0.45f) * unit * 0.55f
        dY = py + cos(t * 0.37f) * unit * 0.4f

        gl?.let { g ->
            val e = ease(idleP)
            val sc = 1f + 0.12f * e
            g.coreX = cx + dX * 0.5f + (width / 2f - cx) * e
            g.coreY = cy + dY * 0.5f + (height / 2f - cy) * e
            g.coreR = coreR * sc
            if (!dragging) { globeAY += globeVY; globeAX += globeVX; globeVY *= 0.95f; globeVX *= 0.92f; globeAX *= 0.985f }
            if (abs(globeVX) > 0.0005f || abs(globeVY) > 0.0005f) postInvalidateOnAnimation()
            g.userAX = globeAX; g.userAY = globeAY
            g.globeR = coreR * 0.42f * sc * globeZoom
            g.horizon = if (workshop) hingeY else cy + dY * 0.5f + coreR * 1.38f + (height / 2f - cy) * e
            g.tiltX = dX / unit * 0.1f; g.tiltY = dY / unit * 0.1f
            g.pulse = speakEnv
            g.dim = ease(idleP)
            g.pinLat = geoLat; g.pinLon = geoLon
            g.fps = when { idleP > 0.9f -> 10; lowPower -> 18; else -> 24 }
        }
        // burn-in protection: slow pixel drift of the whole scene
        c.save()
        c.translate(sin(t / 60f) * 4f, cos(t / 47f) * 4f)

        layerPaint.alpha = (255 * min(1f, boot * 2.5f)).toInt()
        // 3D背景がまだ・または作り直し中のときは2Dの背景で埋める（真っ暗にしない）
        if (gl?.ready != true) {
            fill.color = Color.WHITE; fill.shader = RadialGradient(cx, cy, max(width, height) * 0.8f,
                intArrayOf(Color.rgb(8, 46, 76), Color.rgb(3, 18, 34), Color.rgb(1, 5, 11)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill); fill.shader = null
        }
        c.drawBitmap(bg, -bgMargin - px * 0.7f, -bgMargin - py * 0.7f, layerPaint)
        if (!lowPower) drawDataRain(c, t)
        drawWeatherFx(c, t)

        // core group
        c.save()
        c.translate(dX * 0.5f, dY * 0.5f)
        drawProjector(c, t)
        camera.save(); camera.rotateX(-dY / unit * 4f); camera.rotateY(dX / unit * 4f); camera.getMatrix(m3d); camera.restore()
        m3d.preTranslate(-cx, -cy); m3d.postTranslate(cx, cy); c.concat(m3d)
        // idle: core drifts to centre of screen and grows a little
        if (idleP > 0.001f) {
            val e = ease(idleP)
            c.translate((width / 2f - cx) * e, (height / 2f - cy) * e)
            c.scale(1f + 0.12f * e, 1f + 0.12f * e, cx, cy)
        }
        if (boot < 1f) {
            c.saveLayerAlpha(null, (255 * eb).toInt())
            c.scale(0.55f + 0.45f * eb, 0.55f + 0.45f * eb, cx, cy)
            c.rotate((1f - eb) * -140f, cx, cy)
        }
        drawCore(c, t, now)
        drawSpectrumRing(c, t)
        if (boot < 1f) c.restore()
        c.restore()

        // panels: reveal from center (and retract in idle)
        val pb = ease((boot - 0.25f) / 0.75f) * (1f - ease(idleP))
        val pa = if (expanded != null) 0f else pb
        if (abs(pa - lastPanelsAlpha) > 0.02f || (pa == 1f && lastPanelsAlpha != 1f) || (pa == 0f && lastPanelsAlpha != 0f)) {
            lastPanelsAlpha = pa; listener?.onPanelsAlpha(pa)
        }
        if (pb > 0.002f) {
            c.save()
            val half = width / 2f * pb
            c.clipRect(width / 2f - half, 0f, width / 2f + half, height.toFloat())
            val ts = RectF(0f, 0f, width.toFloat(), topStrip.bottom + unit * 0.3f)
            c.drawBitmap(fg, Rect(ts.left.toInt(), ts.top.toInt(), ts.right.toInt(), ts.bottom.toInt()), ts, null)
            drawTopStrip(c, t, now)
            var k = 0
            for ((name, r) in panels) {
                c.save()
                swapFx[name]?.let { st ->
                    val q = ((System.currentTimeMillis() - st) / 450f)
                    if (q >= 1f) swapFx.remove(name) else {
                        val e = ease(q)
                        c.clipRect(r.left - unit, r.centerY() - (r.height() / 2 + unit) * e, r.right + unit, r.centerY() + (r.height() / 2 + unit) * e)
                        postInvalidateOnAnimation()
                    }
                }
                if (name != "MODULE") c.concat(panelMatrix(r))
                drawPanelBack(c, r)
                val pad = unit * 0.25f
                val src = RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
                c.drawBitmap(fg, Rect(src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt()), src, null)
                sheen(c, r, t, k++)
                c.save(); if (name != "MODULE") c.translate(dX * 0.07f, dY * 0.07f)
                drawPanelContent(c, name, r, t, now)
                c.restore()
                c.restore()
            }
            if (!statusStrip.isEmpty) drawStatus(c, statusStrip, t)
            if (pb < 1f) {
                stroke.strokeWidth = 3f; stroke.color = CYAN
                c.drawLine(width / 2f - half, 0f, width / 2f - half, height.toFloat(), stroke)
                c.drawLine(width / 2f + half, 0f, width / 2f + half, height.toFloat(), stroke)
            }
            c.restore()
        }

        ovLayer?.let { c.drawBitmap(it, 0f, 0f, null) }
        drawRipples(c, nowMs)
        drawIncoming(c, nowMs)
        if (idleP < 0.3f && expanded == null) {
            drawAutoScan(c, t, nowMs)
            drawThinkingStreams(c, t)
            drawGlitch(c, nowMs)
        }
        drawHourly(c, now, nowMs)
        drawChargeFx(c, nowMs)
        drawLowBattery(c, t)
        drawGreeting(c, nowMs)
        drawFaceGreet(c, nowMs)
        drawSwipeFx(c, nowMs)
        drawExpanded(c, t, now, nowMs)
        drawCoreTouch(c, nowMs)
        drawFlash(c)
        val sy = ((t * 0.1f) % 1f) * height
        fill.color = Color.WHITE; fill.shader = LinearGradient(0f, sy - unit * 2, 0f, sy, Color.TRANSPARENT, cyan(22), Shader.TileMode.CLAMP)
        c.drawRect(0f, sy - unit * 2, width.toFloat(), sy, fill); fill.shader = null
        c.restore()

        // idle dimming
        if (idleP > 0.01f) {
            fill.color = Color.argb((140 * ease(idleP)).toInt(), 0, 0, 0); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            if (idleP > 0.6f) {
                label.textAlign = Paint.Align.CENTER; label.textSize = unit * 0.5f; label.letterSpacing = 0.4f
                label.color = cyan((60 + 40 * sin(t * 1.5f)).toInt())
                c.drawText("STANDBY  ―  TOUCH TO RESUME", width / 2f, height - insetBottom - unit * 1.5f, label)
                label.letterSpacing = 0f; label.color = CYAN
            }
        }
        drawBootLog(c, nowMs)
        drawOpening(c, nowMs)

        if (running) {
            val anim = boot < 1f || (openingAt != 0L && nowMs - openingAt < 3200) || expanded != null || ripples.isNotEmpty() || nowMs - incomingAt < 800 || abs(idleP - idleTarget) > 0.01f
            when { anim -> postInvalidateOnAnimation(); idleP > 0.9f -> postInvalidateDelayed(if (lowPower) 160 else 66); else -> postInvalidateDelayed(if (lowPower) 60 else 40) }
        }
    }

    private fun drawPanelContent(c: Canvas, name: String, r: RectF, t: Float, now: Calendar) {
        when (name) {
            "SYSTEM" -> drawSystem(c, r, t); "POWER" -> drawPower(c, r, t)
            "CALENDAR" -> drawCalendar(c, r, now); "WEATHER" -> drawWeather(c, r, t)
            "MEDIA" -> drawMedia(c, r, t); "MODULE" -> drawModule(c, r, t)
            "NETWORK" -> drawNetwork(c, r, t)
        }
    }

    // ===== ⑩ data rain =====
    private fun drawDataRain(c: Canvas, t: Float) {
        val cols = if (wide) 12 else 7
        mono.textAlign = Paint.Align.CENTER; mono.textSize = unit * 0.42f
        val step = mono.textSize * 1.25f
        for (k in 0 until cols) {
            val r = Random(k * 7919)
            val x = (k + 0.5f) * width / cols + (r.nextFloat() - 0.5f) * unit
            val speed = 0.6f + r.nextFloat() * 1.4f
            val len = 6 + r.nextInt(14)
            val head = ((t * speed * 3f + r.nextFloat() * 50f) % 40f) * step * 2f - step * len
            for (j in 0 until len) {
                val y = head + j * step
                if (y < 0 || y > height) continue
                val a = (j.toFloat() / len)
                mono.color = if (j == len - 1) cyan(170) else cyan((70 * a).toInt())
                val ch = "0123456789ABCDEF"[Random((k * 131 + j + (t * 8).toInt()) % 9973).nextInt(16)]
                c.drawText(ch.toString(), x, y, mono)
            }
        }
        mono.color = CYAN
    }

    // ===== ② hologram projector base =====
    private fun drawProjector(c: Canvas, t: Float) {
        val by = cy + coreR * 1.38f
        val rx = coreR * 1.05f; val ry = coreR * 0.2f
        // light cone up into the core
        val cone = Path().apply { moveTo(cx - rx * 0.55f, by); lineTo(cx - coreR * 0.95f, cy); lineTo(cx + coreR * 0.95f, cy); lineTo(cx + rx * 0.55f, by); close() }
        fill.color = Color.WHITE
        fill.shader = LinearGradient(0f, by, 0f, cy, cyan(55), cyan(0), Shader.TileMode.CLAMP)
        c.drawPath(cone, fill); fill.shader = null
        // perspective grid disc
        for (k in 1..5) {
            val f = k / 5f
            stroke.strokeWidth = if (k == 5) 3f else 1.5f; stroke.color = cyan(if (k == 5) 200 else 70)
            c.drawOval(cx - rx * f, by - ry * f, cx + rx * f, by + ry * f, stroke)
        }
        for (k in 0 until 16) {
            val a = (k / 16f) * 2 * PI.toFloat() + t * 0.2f
            stroke.strokeWidth = 1f; stroke.color = cyan(55)
            c.drawLine(cx, by, cx + cos(a) * rx, by + sin(a) * ry, stroke)
        }
        val pulse = (t * 0.6f) % 1f
        stroke.strokeWidth = 3f; stroke.color = cyan((200 * (1 - pulse)).toInt())
        c.drawOval(cx - rx * pulse, by - ry * pulse, cx + rx * pulse, by + ry * pulse, stroke)
        glow.strokeWidth = 14f; glow.color = cyan(120); c.drawOval(cx - rx, by - ry, cx + rx, by + ry, glow)
    }

    // ===== ① wireframe globe =====
    private fun drawGlobe(c: Canvas, r: Float, t: Float) {
        val tilt = Math.toRadians(22.0)
        val rot = Math.toRadians((t * 12.0) % 360.0) + globeAY
        fun proj(latD: Double, lonD: Double): FloatArray {
            val lat = Math.toRadians(latD); val lon = Math.toRadians(lonD) + rot
            val x = cos(lat) * sin(lon); val y0 = sin(lat); val z0 = cos(lat) * cos(lon)
            val y = y0 * cos(tilt) - z0 * sin(tilt); val z = y0 * sin(tilt) + z0 * cos(tilt)
            return floatArrayOf(cx + (x * r).toFloat(), cy - (y * r).toFloat(), z.toFloat())
        }
        fun line(points: List<FloatArray>) {
            for (i in 1 until points.size) {
                val a = points[i - 1]; val b = points[i]
                val front = a[2] > 0 && b[2] > 0
                stroke.strokeWidth = if (front) 1.6f else 1f; stroke.color = cyan(if (front) 95 else 22)
                c.drawLine(a[0], a[1], b[0], b[1], stroke)
            }
        }
        for (lon in 0 until 360 step 30) line((-90..90 step 10).map { proj(it.toDouble(), lon.toDouble()) })
        for (lat in -60..60 step 30) line((0..360 step 10).map { proj(lat.toDouble(), it.toDouble()) })
        // scattered "city lights"
        val rnd = Random(3)
        repeat(90) {
            val p = proj(rnd.nextDouble() * 140 - 60, rnd.nextDouble() * 360)
            if (p[2] > 0) { fill.color = cyan((60 + 120 * p[2]).toInt()); c.drawCircle(p[0], p[1], 1.8f, fill) }
        }
        // current location pin (longitude relative to rotation frame)
        val pin = proj(geoLat, geoLon - 138.48 + 0.0)
        if (pin[2] > 0) {
            val pr = unit * 0.16f * (1f + 0.5f * sin(t * 5f))
            fill.color = Color.rgb(255, 90, 90); c.drawCircle(pin[0], pin[1], unit * 0.12f, fill)
            stroke.strokeWidth = 2f; stroke.color = Color.argb(200, 255, 90, 90); c.drawCircle(pin[0], pin[1], pr * 2.2f, stroke)
            stroke.color = Color.argb(150, 255, 90, 90); c.drawLine(pin[0], pin[1], pin[0] + unit * 1.2f, pin[1] - unit * 0.8f, stroke)
            mono.textAlign = Paint.Align.LEFT; mono.textSize = unit * 0.3f; mono.color = Color.argb(220, 255, 120, 120)
            c.drawText(String.format(Locale.US, "%.2fN %.2fE", geoLat, geoLon), pin[0] + unit * 1.25f, pin[1] - unit * 0.85f, mono)
            mono.color = CYAN
        }
        stroke.strokeWidth = 2f; stroke.color = cyan(110); c.drawCircle(cx, cy, r, stroke)
    }

    // ===== ④ music-reactive ring =====
    private fun drawSpectrumRing(c: Canvas, t: Float) {
        val m = media ?: return
        if (!m.playing) return
        val sp = spectrum
        val n = 64
        val r0 = coreR * 1.025f
        for (k in 0 until n) {
            val band = if (sp != null && sp.isNotEmpty()) sp[(k % (n / 2)).coerceAtMost(sp.size - 1) * sp.size / (n / 2)]
                else 0.25f + 0.75f * abs(sin(t * (2.3f + (k % 7) * 0.4f) + k) * sin(t * 1.1f + k * 0.3f))
            val len = unit * (0.2f + 1.4f * band.coerceIn(0f, 1f))
            val a = 2 * PI.toFloat() * k / n - PI.toFloat() / 2
            stroke.strokeWidth = unit * 0.12f; stroke.color = cyan((120 + 120 * band).toInt())
            c.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * (r0 + len), cy + sin(a) * (r0 + len), stroke)
        }
    }

    // ===== ⑤ touch ripples =====
    private fun drawRipples(c: Canvas, nowMs: Long) {
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next(); val age = (nowMs - r.t0) / 700f
            if (age >= 1f) { it.remove(); continue }
            val e = ease(age)
            for (k in 0 until 2) {
                val q = (e - k * 0.15f).coerceIn(0f, 1f)
                stroke.strokeWidth = 3f; stroke.color = cyan((220 * (1 - q)).toInt())
                c.drawCircle(r.x, r.y, unit * (0.6f + 4f * q), stroke)
            }
            val b = unit * (1.6f - 0.8f * e)
            stroke.strokeWidth = 3f; stroke.color = cyan((255 * (1 - age)).toInt())
            for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                val x = r.x + sx * b; val y = r.y + sy * b
                c.drawLine(x, y, x - sx * b * 0.45f, y, stroke); c.drawLine(x, y, x, y - sy * b * 0.45f, stroke)
            }
            mono.textAlign = Paint.Align.LEFT; mono.textSize = unit * 0.32f; mono.color = cyan((200 * (1 - age)).toInt())
            c.drawText(String.format(Locale.US, "X%04d Y%04d", r.x.toInt(), r.y.toInt()), r.x + b * 1.1f, r.y - b * 1.1f, mono)
            mono.color = CYAN
        }
    }

    // ===== ⑫ incoming notification streaks =====
    private fun drawIncoming(c: Canvas, nowMs: Long) {
        val age = (nowMs - incomingAt) / 650f
        if (age < 0f || age > 1.2f) return
        val rnd = Random(incomingAt)
        repeat(10) {
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            val d0 = hypot(width.toFloat(), height.toFloat()) * 0.7f
            val delay = rnd.nextFloat() * 0.3f
            val q = ((age - delay) / 0.7f).coerceIn(0f, 1f)
            if (q <= 0f || q >= 1f) return@repeat
            val head = d0 * (1 - ease(q)); val tail = d0 * (1 - ease((q - 0.25f).coerceAtLeast(0f)))
            val hx = cx + cos(ang) * head; val hy = cy + sin(ang) * head
            val txp = cx + cos(ang) * tail; val typ = cy + sin(ang) * tail
            glow.strokeWidth = 10f; glow.color = cyan(180); c.drawLine(txp, typ, hx, hy, glow)
            stroke.strokeWidth = 3f; stroke.color = Color.WHITE; c.drawLine(txp, typ, hx, hy, stroke)
        }
        if (age > 0.7f) {
            val q = ((age - 0.7f) / 0.5f).coerceIn(0f, 1f)
            glow.strokeWidth = 20f; glow.color = cyan((255 * (1 - q)).toInt()); c.drawCircle(cx, cy, coreR * (0.5f + q), glow)
        }
    }

    // ===== ⑥ expanded panel =====
    private fun expand(name: String) { expanded = name; expandAt = System.currentTimeMillis(); collapsing = false; invalidate() }
    private fun collapse() { collapsing = true; expandAt = System.currentTimeMillis(); invalidate() }

    private fun drawExpanded(c: Canvas, t: Float, now: Calendar, nowMs: Long) {
        val name = expanded ?: return
        val src = panels[name] ?: run { expanded = null; return }
        var p = ((nowMs - expandAt) / 380f).coerceIn(0f, 1f)
        if (collapsing) { p = 1f - p; if (p <= 0f) { expanded = null; collapsing = false; return } }
        val e = ease(p)
        val m = unit * 0.8f
        val dst = RectF(m, topStrip.bottom + unit * 0.8f, width - m, height - insetBottom - unit * 0.8f)
        expandRect.set(src.left + (dst.left - src.left) * e, src.top + (dst.top - src.top) * e,
            src.right + (dst.right - src.right) * e, src.bottom + (dst.bottom - src.bottom) * e)
        fill.color = Color.argb((200 * e).toInt(), 0, 4, 10); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        panelFrame(c, expandRect, name)
        glow.strokeWidth = 14f; glow.color = cyan((150 * e).toInt()); c.drawRect(expandRect, glow)
        if (p > 0.7f) {
            val inner = RectF(expandRect.left, expandRect.top, expandRect.right, expandRect.bottom - unit * 3f)
            c.save(); c.clipRect(expandRect)
            val savedUnit = unit
            unit = savedUnit * 1.35f
            drawPanelContent(c, name, inner, t, now)
            unit = savedUnit
            c.restore()
            // footer: open + close
            val fh = unit * 1.9f
            expandOpenBtn.set(expandRect.right - unit * 9f, expandRect.bottom - unit * 0.6f - fh, expandRect.right - unit * 0.6f, expandRect.bottom - unit * 0.6f)
            fill.color = cyan(55); c.drawRect(expandOpenBtn, fill)
            stroke.strokeWidth = 2.5f; stroke.color = CYAN; c.drawRect(expandOpenBtn, stroke)
            label.textAlign = Paint.Align.CENTER; label.textSize = fh * 0.42f; label.letterSpacing = 0.25f; label.color = CYAN
            c.drawText("OPEN  ▸", expandOpenBtn.centerX(), expandOpenBtn.centerY() + label.textSize * 0.36f, label)
            label.textAlign = Paint.Align.LEFT; label.textSize = fh * 0.36f; label.color = cyan(140)
            c.drawText("◂  TAP ANYWHERE TO CLOSE", expandRect.left + unit * 0.8f, expandOpenBtn.centerY() + label.textSize * 0.36f, label)
            label.letterSpacing = 0f; label.color = CYAN
        }
    }

    // ===== ⑦ boot log =====
    private val bootLines = listOf("INITIALIZING HUD//OS", "CORE REACTOR ........ OK", "SENSOR ARRAY ........ OK", "HOLO PROJECTOR ...... OK",
        "NETWORK LINK ........ OK", "VOICE INTERFACE ..... OK", "ALL SYSTEMS ONLINE")

    // ===== extra cinematic effects =====

    /** 天気に合わせたホログラムの雨・雪 */
    private fun drawWeatherFx(c: Canvas, t: Float) {
        val w = stats.weather
        val rain = w.contains("雨"); val snow = w.contains("雪"); val storm = w.contains("雷")
        if (!rain && !snow) return
        val n = if (lowPower) 30 else 70
        for (k in 0 until n) {
            val r = Random(k * 92821)
            val x0 = r.nextFloat() * (width + height * 0.3f)
            val sp = 0.25f + r.nextFloat() * 0.35f
            val q = ((t * sp + r.nextFloat()) % 1f)
            val y = q * (height + unit * 4) - unit * 2
            if (snow) {
                val x = (x0 + sin(t * 0.8f + k) * unit * 0.6f) % width
                fill.color = cyan((60 + 80 * r.nextFloat()).toInt()); c.drawCircle(x, y, 2f + r.nextFloat() * 2f, fill)
            } else {
                val x = x0 - y * 0.25f
                stroke.strokeWidth = 1.5f; stroke.color = cyan((30 + 60 * r.nextFloat()).toInt())
                c.drawLine(x, y, x - unit * 0.25f, y + unit * 1.0f, stroke)
            }
        }
        if (storm && (t * 10).toInt() % 97 < 2) { fill.color = cyan(40); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill) }
    }

    /** 地球儀のまわりを回る衛星 */
    private fun drawSatellites(c: Canvas, r: Float, t: Float) {
        for (k in 0 until 3) {
            val a = t * (0.6f + k * 0.25f) + k * 2f
            val tilt = 0.35f + k * 0.18f
            val rr = r * (1.12f + k * 0.07f)
            stroke.strokeWidth = 1f; stroke.color = cyan(35)
            c.save(); c.rotate(k * 55f - 20f, cx, cy)
            c.drawOval(cx - rr, cy - rr * tilt, cx + rr, cy + rr * tilt, stroke)
            for (j in 0 until 8) {
                val aa = a - j * 0.06f
                val x = cx + cos(aa) * rr; val y = cy + sin(aa) * rr * tilt
                fill.color = cyan(((1 - j / 8f) * if (sin(aa) > 0) 230 else 90).toInt())
                c.drawCircle(x, y, unit * (0.1f - j * 0.01f).coerceAtLeast(0.02f), fill)
            }
            c.restore()
        }
    }

    /** 定期的にパネルを照準でロックして解析する演出 */
    private fun drawAutoScan(c: Canvas, t: Float, nowMs: Long) {
        val period = 16000L
        val phase = nowMs % period
        if (phase > 3200 || panels.isEmpty()) return
        val keys = panels.keys.filter { it != "MODULE" }
        if (keys.isEmpty()) return
        val name = keys[((nowMs / period) % keys.size).toInt()]
        val r = panels[name] ?: return
        val p = phase / 3200f
        // reticle flies from core to panel, locks, scans
        val fly = ease((p / 0.3f).coerceIn(0f, 1f))
        val tx = cx + (r.centerX() - cx) * fly; val ty = cy + (r.centerY() - cy) * fly
        val lock = ((p - 0.3f) / 0.15f).coerceIn(0f, 1f)
        val fade = if (p > 0.85f) 1f - (p - 0.85f) / 0.15f else 1f
        val hw = unit * 1.5f + (r.width() / 2 + unit * 0.3f - unit * 1.5f) * ease(lock)
        val hh = unit * 1.5f + (r.height() / 2 + unit * 0.3f - unit * 1.5f) * ease(lock)
        val a = unit * 1.3f
        stroke.strokeWidth = 4f; stroke.color = cyan((255 * fade).toInt())
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val x = tx + sx * hw; val y = ty + sy * hh
            c.drawLine(x, y, x - sx * a, y, stroke); c.drawLine(x, y, x, y - sy * a, stroke)
        }
        if (lock < 1f) {
            stroke.strokeWidth = 2f; c.drawCircle(tx, ty, unit * 0.5f, stroke)
            c.drawLine(tx - unit, ty, tx + unit, ty, stroke); c.drawLine(tx, ty - unit, tx, ty + unit, stroke)
        } else {
            val sp = ((p - 0.45f) / 0.35f).coerceIn(0f, 1f)
            val sx = r.left + r.width() * sp
            fill.color = Color.WHITE
            fill.shader = LinearGradient(sx - unit * 3, 0f, sx, 0f, Color.TRANSPARENT, cyan((90 * fade).toInt()), Shader.TileMode.CLAMP)
            c.drawRect(sx - unit * 3, r.top, sx, r.bottom, fill); fill.shader = null
            stroke.strokeWidth = 2f; stroke.color = cyan((230 * fade).toInt()); c.drawLine(sx, r.top, sx, r.bottom, stroke)
            label.textAlign = Paint.Align.LEFT; label.textSize = unit * 0.42f; label.letterSpacing = 0.25f
            val txt = if (sp < 1f) "ANALYZING $name  ${(sp * 100).toInt()}%" else "$name  ▸  NOMINAL"
            val ly = r.top - unit * 0.55f
            fill.color = Color.argb((200 * fade).toInt(), 0, 8, 16)
            c.drawRect(r.left, ly - label.textSize, r.left + label.measureText(txt) + unit * 0.4f, ly + label.textSize * 0.35f, fill)
            label.color = cyan((255 * fade).toInt()); c.drawText(txt, r.left + unit * 0.2f, ly, label)
            label.letterSpacing = 0f; label.color = CYAN
        }
    }

    /** AIが考えている間、各パネルからコアへデータが流れ込む */
    private fun drawThinkingStreams(c: Canvas, t: Float) {
        if (!thinking) return
        var k = 0
        for ((_, r) in panels) {
            val sx = r.centerX(); val sy = r.centerY()
            for (j in 0 until 4) {
                val q = ((t * 1.3f + j * 0.25f + k * 0.13f) % 1f)
                val x = sx + (cx - sx) * q; val y = sy + (cy - sy) * q
                val x2 = sx + (cx - sx) * (q - 0.06f).coerceAtLeast(0f); val y2 = sy + (cy - sy) * (q - 0.06f).coerceAtLeast(0f)
                glow.strokeWidth = 8f; glow.color = cyan(150); c.drawLine(x2, y2, x, y, glow)
                stroke.strokeWidth = 2.5f; stroke.color = Color.WHITE; c.drawLine(x2, y2, x, y, stroke)
            }
            stroke.strokeWidth = 1f; stroke.color = cyan(40); c.drawLine(sx, sy, cx, cy, stroke)
            k++
        }
        label.textAlign = Paint.Align.CENTER; label.textSize = unit * 0.5f; label.letterSpacing = 0.4f
        label.color = cyan((150 + 100 * sin(t * 6f)).toInt())
        c.drawText("PROCESSING", cx, cy - coreR * 1.45f, label)
        label.letterSpacing = 0f; label.color = CYAN
    }

    /** ときどき走るホログラムのノイズ */
    private fun drawGlitch(c: Canvas, nowMs: Long) {
        val slot = nowMs / 13000L
        val start = slot * 13000L + Random(slot).nextLong(3000, 11000)
        val age = nowMs - start
        if (age < 0 || age > 220) return
        val fg = fgLayer ?: return
        val r = Random(nowMs / 40)
        val pr = Paint().apply { colorFilter = PorterDuffColorFilter(Color.argb(150, 255, 60, 80), PorterDuff.Mode.SRC_IN); alpha = 140 }
        val pc = Paint().apply { alpha = 160 }
        repeat(5) {
            val y = r.nextInt(height.coerceAtLeast(2)); val h = r.nextInt((unit * 0.4f).toInt().coerceAtLeast(2), (unit * 2f).toInt().coerceAtLeast(3))
            val dx = r.nextInt(-(unit * 0.8f).toInt(), (unit * 0.8f).toInt() + 1)
            val src = Rect(0, y, width, (y + h).coerceAtMost(height))
            c.drawBitmap(fg, src, RectF(dx + 6f, y.toFloat(), width + dx + 6f, (y + h).toFloat()), pr)
            c.drawBitmap(fg, src, RectF(dx.toFloat(), y.toFloat(), (width + dx).toFloat(), (y + h).toFloat()), pc)
        }
        postInvalidateOnAnimation()
    }

    /** 毎正時に時報演出 */
    private fun drawHourly(c: Canvas, now: Calendar, nowMs: Long) {
        if (now.get(Calendar.MINUTE) != 0 || now.get(Calendar.SECOND) > 5) return
        val p = (now.get(Calendar.SECOND) * 1000 + now.get(Calendar.MILLISECOND)) / 6000f
        for (k in 0 until 3) {
            val q = ((p * 2f) - k * 0.2f).coerceIn(0f, 1f)
            glow.strokeWidth = 16f; glow.color = cyan((200 * (1 - q)).toInt()); c.drawCircle(cx, cy, coreR * (1f + q * 1.5f), glow)
            stroke.strokeWidth = 3f; stroke.color = cyan((255 * (1 - q)).toInt()); c.drawCircle(cx, cy, coreR * (1f + q * 1.5f), stroke)
        }
        val fade = if (p > 0.8f) (1 - p) / 0.2f else min(1f, p * 5)
        label.textAlign = Paint.Align.CENTER; label.textSize = unit * 0.7f; label.letterSpacing = 0.4f; label.color = cyan((255 * fade).toInt())
        c.drawText(String.format(Locale.US, "%02d:00  ―  HOURLY SYNC", now.get(Calendar.HOUR_OF_DAY)), cx, cy - coreR * 1.5f, label)
        label.letterSpacing = 0f; label.color = CYAN
        postInvalidateOnAnimation()
    }

    /** 充電を挿した／抜いた瞬間の演出 */
    private fun drawChargeFx(c: Canvas, nowMs: Long) {
        val age = nowMs - chargeEventAt
        if (age > 2600 || chargeEventAt == 0L) return
        val p = age / 2600f
        val col = if (chargeOn) CYAN else RED
        if (chargeOn) {
            // energy rises from the bottom edge into the core
            for (k in 0 until 7) {
                val x0 = width * (0.2f + 0.1f * k)
                val q = ((p * 2.2f) - k * 0.06f).coerceIn(0f, 1f)
                if (q <= 0f || q >= 1f) continue
                val hy = height - (height - cy) * ease(q); val ty2 = height - (height - cy) * ease((q - 0.2f).coerceAtLeast(0f))
                val hx = x0 + (cx - x0) * ease(q); val tx2 = x0 + (cx - x0) * ease((q - 0.2f).coerceAtLeast(0f))
                glow.strokeWidth = 12f; glow.color = cyan(180); c.drawLine(tx2, ty2, hx, hy, glow)
                stroke.strokeWidth = 3f; stroke.color = Color.WHITE; c.drawLine(tx2, ty2, hx, hy, stroke)
            }
            if (p > 0.45f) {
                val q = ((p - 0.45f) / 0.4f).coerceIn(0f, 1f)
                glow.strokeWidth = 30f; glow.color = cyan((220 * (1 - q)).toInt()); c.drawCircle(cx, cy, coreR * (0.5f + q * 0.8f), glow)
            }
        } else {
            fill.color = Color.argb((90 * (1 - p)).toInt(), 255, 40, 40); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
        val fade = if (p > 0.8f) (1 - p) / 0.2f else min(1f, p * 6)
        val txt = if (chargeOn) "⚡  POWER LINK ESTABLISHED" else "POWER LINK SEVERED"
        label.textAlign = Paint.Align.CENTER; label.textSize = unit * 0.75f; label.letterSpacing = 0.35f
        val ty = cy + coreR * 1.55f
        fill.color = Color.argb((200 * fade).toInt(), 0, 8, 16)
        c.drawRect(cx - label.measureText(txt) / 2 - unit, ty - label.textSize * 1.2f, cx + label.measureText(txt) / 2 + unit, ty + label.textSize * 0.6f, fill)
        stroke.strokeWidth = 2f; stroke.color = withA(col, (255 * fade).toInt())
        c.drawRect(cx - label.measureText(txt) / 2 - unit, ty - label.textSize * 1.2f, cx + label.measureText(txt) / 2 + unit, ty + label.textSize * 0.6f, stroke)
        label.color = withA(col, (255 * fade).toInt()); c.drawText(txt, cx, ty, label)
        label.letterSpacing = 0f; label.color = CYAN
        postInvalidateOnAnimation()
    }

    /** 電池が少ないときの赤い警告 */
    private fun drawLowBattery(c: Canvas, t: Float) {
        val s = stats
        if (s.charging || s.battery > 15 || s.battery <= 0) return
        val a = (0.5f + 0.5f * sin(t * 3f))
        glow.strokeWidth = unit * 1.2f; glow.color = Color.argb((140 * a).toInt(), 255, 40, 40)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
        label.textAlign = Paint.Align.CENTER; label.textSize = unit * 0.5f; label.letterSpacing = 0.4f
        label.color = Color.argb((120 + 135 * a).toInt(), 255, 80, 80)
        c.drawText("⚠ POWER CRITICAL  ${s.battery}%", width / 2f, topStrip.bottom + unit * 0.75f, label)
        label.letterSpacing = 0f; label.color = CYAN
    }

    /** 起動ログのあとに挨拶 */
    private var faceGreetAt = 0L
    fun faceGreet() { faceGreetAt = System.currentTimeMillis(); wake(); invalidate() }
    fun idleNow() { lastTouch = System.currentTimeMillis() - idleTimeoutMs - 1 }

    /** 顔を認識した瞬間の「本人確認」演出 */
    private fun drawFaceGreet(c: Canvas, nowMs: Long) {
        val age = nowMs - faceGreetAt
        if (faceGreetAt == 0L || age > 2600) return
        val p = age / 2600f
        val sweep = ease((p / 0.4f).coerceIn(0f, 1f))
        val y = height * sweep
        if (p < 0.45f) {
            fill.color = Color.WHITE
            fill.shader = LinearGradient(0f, y - unit * 5, 0f, y, Color.TRANSPARENT, cyan(90), Shader.TileMode.CLAMP)
            c.drawRect(0f, y - unit * 5, width.toFloat(), y, fill); fill.shader = null
            glow.strokeWidth = 14f; glow.color = cyan(200); c.drawLine(0f, y, width.toFloat(), y, glow)
            stroke.strokeWidth = 2.5f; stroke.color = Color.WHITE; c.drawLine(0f, y, width.toFloat(), y, stroke)
        }
        val fade = if (p > 0.8f) (1 - p) / 0.2f else ((p - 0.3f) / 0.15f).coerceIn(0f, 1f)
        if (fade > 0f) {
            val l1 = "IDENTITY CONFIRMED"; val l2 = "WELCOME BACK, $userTag"
            label.textAlign = Paint.Align.CENTER; label.letterSpacing = 0.4f
            val ty = cy - coreR * 1.5f
            label.textSize = unit * 0.45f; label.color = cyan((200 * fade).toInt()); c.drawText(l1, cx, ty - unit * 0.9f, label)
            label.textSize = unit * 0.8f; label.color = cyan((255 * fade).toInt()); c.drawText(l2, cx, ty, label)
            label.letterSpacing = 0f; label.color = CYAN
        }
        postInvalidateOnAnimation()
    }

    private fun drawGreeting(c: Canvas, nowMs: Long) {
        val age = nowMs - bootStart - 1500
        if (age < 0 || age > 3200) return
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when (h) { in 4..10 -> "GOOD MORNING"; in 11..16 -> "GOOD AFTERNOON"; in 17..21 -> "GOOD EVENING"; else -> "WELCOME BACK" }
        val txt = "$g, $userTag"
        val chars = (age / 35).toInt().coerceAtMost(txt.length)
        val fade = if (age > 2600) 1f - (age - 2600) / 600f else 1f
        label.textAlign = Paint.Align.CENTER; label.textSize = unit * 0.85f; label.letterSpacing = 0.35f
        val y = cy - coreR * 1.55f
        textGlowL.textSize = label.textSize; textGlowL.letterSpacing = 0.35f; textGlowL.color = cyan((180 * fade).toInt())
        val shown = txt.substring(0, chars)
        c.drawText(shown, cx, y, textGlowL)
        label.color = cyan((255 * fade).toInt()); c.drawText(shown, cx, y, label)
        label.letterSpacing = 0f; label.color = CYAN
        postInvalidateOnAnimation()
    }

    private val textGlowL = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE; strokeWidth = 6f
    }

    /** フルのオープニング：電源オン → コア点火 → 衝撃波 → ホロ・グリッドの波 → パネルの枠が光でなぞられて組み上がる */
    private fun drawOpening(c: Canvas, nowMs: Long) {
        if (openingAt == 0L) return
        val t = nowMs - openingAt
        if (t > 3200) { openingAt = 0L; return }
        val W = width.toFloat(); val H = height.toFloat()
        val ox = cx; val oy = cy

        // ① 電源オン（ブラウン管のように、光の点 → 横線 → 画面全体に開く）
        if (t < 420) {
            val a = (t / 200f).coerceIn(0f, 1f)
            val b = ((t - 200) / 220f).coerceIn(0f, 1f)
            val hw = W / 2 * ease(a); val hh = 2f + (H / 2) * ease(b)
            val l = ox - hw; val r = ox + hw; val tp = oy - hh; val bt = oy + hh
            fill.color = Color.BLACK
            c.drawRect(0f, 0f, W, tp, fill); c.drawRect(0f, bt, W, H, fill)
            c.drawRect(0f, tp, l, bt, fill); c.drawRect(r, tp, W, bt, fill)
            glow.strokeWidth = 30f; glow.color = cyan(255)
            c.drawLine(l, tp, r, tp, glow); c.drawLine(l, bt, r, bt, glow)
            stroke.strokeWidth = 3f; stroke.color = Color.WHITE
            c.drawLine(l, tp, r, tp, stroke); c.drawLine(l, bt, r, bt, stroke)
            if (a < 1f) { fill.color = Color.WHITE; c.drawCircle(ox, oy, unit * 0.3f * (1 + a), fill) }
        }
        // ② コア点火のフラッシュ
        if (t in 380..1100) {
            val q = (t - 380) / 720f
            fill.color = Color.WHITE
            fill.shader = RadialGradient(ox, oy, max(W, H) * (0.25f + 0.5f * q),
                intArrayOf(Color.argb((230 * (1 - q)).toInt(), 230, 250, 255), Color.argb((120 * (1 - q)).toInt(), 120, 222, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fill); fill.shader = null
            // 横に伸びるレンズフレア
            val fw = W * (0.3f + 0.7f * ease(q))
            fill.shader = LinearGradient(ox - fw, 0f, ox + fw, 0f, intArrayOf(Color.TRANSPARENT, Color.argb((200 * (1 - q)).toInt(), 200, 240, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(ox - fw, oy - unit * 0.12f, ox + fw, oy + unit * 0.12f, fill); fill.shader = null
        }
        // ③ 衝撃波リング
        for (k in 0 until 3) {
            val st = 450 + k * 140
            val q = ((t - st) / 900f)
            if (q <= 0f || q >= 1f) continue
            val r = coreR * (0.3f + 3.2f * ease(q))
            glow.strokeWidth = 26f * (1 - q); glow.color = cyan((255 * (1 - q)).toInt()); c.drawCircle(ox, oy, r, glow)
            stroke.strokeWidth = 2.5f; stroke.color = Color.argb((255 * (1 - q)).toInt(), 220, 248, 255); c.drawCircle(ox, oy, r, stroke)
        }
        // ④ ホロ・グリッドの波（画面全体に格子が一瞬浮かび上がる）
        if (t in 600..1900) {
            val q = (t - 600) / 1300f
            val front = max(W, H) * 1.1f * ease(q)
            val band = unit * 6f
            stroke.strokeWidth = 1.2f
            var gx = 0f
            while (gx < W) {
                var gy = 0f
                while (gy < H) {
                    val d = hypot(gx - ox, gy - oy)
                    val k = 1f - abs(d - front) / band
                    if (k > 0f) {
                        stroke.color = cyan((200 * k * (1 - q)).toInt())
                        c.drawLine(gx, gy, gx + unit, gy, stroke); c.drawLine(gx, gy, gx, gy + unit, stroke)
                    }
                    gy += unit
                }
                gx += unit
            }
        }
        // ⑤ パネルの枠を光がなぞる（順番に）
        var i = 0
        for ((_, r) in panels) {
            val st = 1000 + i * 120; i++
            val q = ((t - st) / 650f)
            if (q <= 0f || q >= 1.4f) continue
            val p = Path().apply { addRect(r, Path.Direction.CW) }
            val pm = PathMeasure(p, false)
            val len = pm.length
            val head = len * ease(q.coerceAtMost(1f))
            val seg = Path()
            pm.getSegment((head - len * 0.25f).coerceAtLeast(0f), head, seg, true)
            val fade = if (q > 1f) 1f - (q - 1f) / 0.4f else 1f
            glow.strokeWidth = 14f; glow.color = cyan((255 * fade).toInt()); c.drawPath(seg, glow)
            stroke.strokeWidth = 3f; stroke.color = Color.argb((255 * fade).toInt(), 230, 250, 255); c.drawPath(seg, stroke)
            val pos = FloatArray(2); pm.getPosTan(head, pos, null)
            fill.color = Color.argb((255 * fade).toInt(), 255, 255, 255); c.drawCircle(pos[0], pos[1], unit * 0.15f, fill)
        }
        // ⑥ 中央に「SYSTEM BOOT」のタイトル
        if (t in 380..1500) {
            val q = (t - 380) / 1120f
            val a = if (q < 0.2f) q / 0.2f else if (q > 0.75f) (1 - q) / 0.25f else 1f
            label.textAlign = Paint.Align.CENTER; label.textSize = unit * 1.1f; label.letterSpacing = 0.6f + 0.3f * q
            label.color = Color.argb((255 * a).toInt(), 230, 250, 255)
            c.drawText("HUD//OS", ox, oy + coreR * 1.25f, label)
            label.textSize = unit * 0.45f; label.letterSpacing = 0.5f; label.color = cyan((200 * a).toInt())
            c.drawText("SYSTEM BOOT SEQUENCE", ox, oy + coreR * 1.25f + unit * 0.9f, label)
            label.letterSpacing = 0f; label.color = CYAN
        }
        postInvalidateOnAnimation()
    }

    private fun drawBootLog(c: Canvas, nowMs: Long) {
        val age = nowMs - bootStart
        if (age > 2200) return
        val fade = if (age > 1700) 1f - (age - 1700) / 500f else 1f
        mono.textAlign = Paint.Align.LEFT; mono.textSize = unit * 0.45f
        val x = unit * 1.2f; var y = height - insetBottom - unit * 1.5f - bootLines.size * mono.textSize * 1.4f
        for ((i, line) in bootLines.withIndex()) {
            val lt = age - i * 160L
            if (lt < 0) break
            val chars = (lt / 12).toInt().coerceAtMost(line.length)
            mono.color = if (i == bootLines.lastIndex) cyan((255 * fade).toInt()) else cyan((190 * fade).toInt())
            fill.color = Color.argb((160 * fade).toInt(), 0, 6, 14)
            c.drawRect(x - 6, y - mono.textSize, x + mono.measureText(line) + 6, y + mono.textSize * 0.35f, fill)
            c.drawText(line.substring(0, chars) + if (chars < line.length) "▌" else "", x, y, mono)
            y += mono.textSize * 1.4f
        }
        mono.color = CYAN
    }

    private val polySrc = FloatArray(8)
    private val polyDst = FloatArray(8)
    private val pm = Matrix()

    /** パネルごとの遠近変形：画面の外側ほど手前に来る曲面配置＋端末の傾きに追従 */
    private fun panelMatrix(r: RectF): Matrix {
        val W = width.toFloat(); val H = height.toFloat()
        val side = ((r.centerX() - W / 2) / (W / 2)).coerceIn(-1f, 1f)
        val vert = ((r.centerY() - H / 2) / (H / 2)).coerceIn(-1f, 1f)
        val g = (-side * 0.07f + dX / unit * 0.018f).coerceIn(-0.12f, 0.12f)   // 左右の辺の高さ比
        val h = (-vert * 0.035f - dY / unit * 0.018f).coerceIn(-0.1f, 0.1f)   // 上下の辺の幅比
        val hw = r.width() / 2; val hh = r.height() / 2
        val pcx = r.centerX() + dX * 0.12f; val pcy = r.centerY() + dY * 0.12f
        var n = 0
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
            polySrc[n] = r.centerX() + sx * hw; polySrc[n + 1] = r.centerY() + sy * hh
            val wf = if (sy < 0) 1 + h else 1 - h
            val hf = if (sx < 0) 1 + g else 1 - g
            polyDst[n] = pcx + sx * hw * wf; polyDst[n + 1] = pcy + sy * hh * hf
            n += 2
        }
        pm.setPolyToPoly(polySrc, 0, polyDst, 0, 4)
        return pm
    }

    private fun drawPanelBack(c: Canvas, r: RectF) {
        val ex = -dX * 0.3f - unit * 0.15f; val ey = -dY * 0.3f + unit * 0.4f
        val b = RectF(r); b.offset(ex, ey)
        fill.color = Color.argb(210, 0, 7, 14); c.drawRect(b, fill)
        stroke.strokeWidth = 1.5f; stroke.color = cyan(50); c.drawRect(b, stroke)
        stroke.color = cyan(85)
        c.drawLine(r.left, r.top, b.left, b.top, stroke); c.drawLine(r.right, r.top, b.right, b.top, stroke)
        c.drawLine(r.left, r.bottom, b.left, b.bottom, stroke); c.drawLine(r.right, r.bottom, b.right, b.bottom, stroke)
        // side face shading
        fill.color = cyan(14)
        val side = Path().apply { moveTo(r.left, r.bottom); lineTo(r.right, r.bottom); lineTo(b.right, b.bottom); lineTo(b.left, b.bottom); close() }
        c.drawPath(side, fill)
    }

    private fun sheen(c: Canvas, r: RectF, t: Float, k: Int) {
        val ph = ((t * 0.18f + k * 0.29f) % 1.8f) - 0.4f
        if (ph < -0.2f || ph > 1.2f) return
        val x = r.left + ph * r.width(); val w = unit * 4
        c.save(); c.clipRect(r.left, r.top, r.right, r.top + headerH())
        fill.color = Color.WHITE; fill.shader = LinearGradient(x - w, 0f, x + w, 0f, intArrayOf(Color.TRANSPARENT, cyan(70), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(x - w, r.top, x + w, r.top + headerH(), fill); fill.shader = null
        c.restore()
    }

    private fun hexStream(seed: Long, len: Int): String {
        val r = Random(seed); val sb = StringBuilder()
        repeat(len) { if (it % 5 == 4) sb.append(' ') else sb.append("0123456789ABCDEF"[r.nextInt(16)]) }
        return sb.toString()
    }

    private fun drawTopStrip(c: Canvas, t: Float, now: Calendar) {
        val r = topStrip; val base = r.bottom - unit * 0.4f
        label.textAlign = Paint.Align.LEFT; label.textSize = unit * 0.75f; label.letterSpacing = 0.2f; label.color = CYAN
        val brand = if (workshop) "◆ HUD//OS  WORKSHOP" else "◆ HUD//OS"
        c.drawText(brand, r.left, base, label)
        val lw = label.measureText("$brand  ")
        label.textSize = unit * 0.45f
        label.color = if (notifLink) cyan(170) else RED
        val linkTxt = if (wide) {
            if (notifLink) "NOTIFY LINK ● ONLINE" + (if (voiceOn) "  VOICE ON" else "  VOICE OFF") else "NOTIFY LINK ○ OFFLINE  (TAP TO FIX)"
        } else {
            if (notifLink) "● LINK" + (if (voiceOn) " VOICE" else "") else "○ LINK (TAP)"
        }
        c.drawText(linkTxt, r.left + lw, base, label)
        var linkW = label.measureText(linkTxt)
        // ジェスチャーの状態
        if (gestureState == 2 || gestureState == -1) {
            val gx = r.left + lw + linkW + unit * 0.8f
            val hit = System.currentTimeMillis() - gestureHitAt < 600
            label.color = when { gestureState == -1 -> RED; hit -> Color.WHITE; else -> cyan(190) }
            val gt = if (gestureState == -1) "GESTURE ✕" else "GESTURE"
            c.drawText(gt, gx, base, label)
            val gw = label.measureText(gt)
            if (gestureState == 2) {
                // 手の動きのメーター
                val bw = unit * 1.6f; val bh = unit * 0.25f
                val bx = gx + gw + unit * 0.3f; val by = base - bh * 1.6f
                fill.color = cyan(40); c.drawRect(bx, by, bx + bw, by + bh, fill)
                fill.color = if (hit) Color.WHITE else CYAN
                c.drawRect(bx, by, bx + bw * (gestureMotion * 4f).coerceIn(0f, 1f), by + bh, fill)
                linkW += gw + bw + unit * 1.2f
            } else linkW += gw + unit * 0.8f
            label.color = CYAN
        }
        label.letterSpacing = 0f
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = unit * 0.62f; mono.color = CYAN
        val wd = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")[now.get(Calendar.DAY_OF_WEEK) - 1]
        val st = if (wide) String.format(Locale.US, "%04d.%02d.%02d %s %02d:%02d:%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH), wd, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
            else String.format(Locale.US, "%s %02d:%02d", wd, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        c.drawText(st, r.right, base, mono)
        // battery indicator
        run {
            val s = stats
            val low = s.battery <= 20 && !s.charging
            val col = if (low) RED else CYAN
            val bw = unit * 1.5f; val bh = unit * 0.72f
            val bx2 = r.right - mono.measureText(st) - unit * 0.9f
            val bx1 = bx2 - bw; val by1 = base - bh * 0.95f
            stroke.strokeWidth = 2.5f; stroke.color = col
            c.drawRoundRect(bx1, by1, bx2, by1 + bh, 4f, 4f, stroke)
            fill.color = col
            c.drawRect(bx2 + 1, by1 + bh * 0.3f, bx2 + unit * 0.14f, by1 + bh * 0.7f, fill)
            val inner = (bw - 8f) * (dBatt / 100f).coerceIn(0f, 1f)
            glow.strokeWidth = 6f; glow.color = withA(col, 120)
            c.drawRect(bx1 + 4, by1 + 4, bx1 + 4 + inner, by1 + bh - 4, glow)
            c.drawRect(bx1 + 4, by1 + 4, bx1 + 4 + inner, by1 + bh - 4, fill)
            if (s.charging) {
                label.textAlign = Paint.Align.CENTER; label.textSize = bh * 0.85f; label.color = Color.WHITE
                c.drawText("⚡", (bx1 + bx2) / 2, by1 + bh * 0.82f, label); label.color = CYAN
            }
            mono.textAlign = Paint.Align.RIGHT; mono.textSize = unit * 0.62f; mono.color = col
            c.drawText("${dBatt.roundToInt()}%", bx1 - unit * 0.25f, base, mono)
            mono.color = CYAN; mono.textAlign = Paint.Align.RIGHT
            mono.textSize = unit * 0.62f
        }
        if (wide) {
            val free = (r.right - mono.measureText(st) - unit * 5f) - (r.left + lw + linkW) - unit * 2
            mono.textAlign = Paint.Align.LEFT; mono.textSize = unit * 0.42f; mono.color = cyan(100)
            var hs = hexStream((t * 6).toLong(), 40)
            while (mono.measureText(hs) > free && hs.length > 4) hs = hs.dropLast(5)
            if (free > unit * 3) c.drawText(hs, r.left + lw + linkW + unit, base, mono)
        }
        mono.color = CYAN
    }

    // ---------- core / radial launcher ----------
    private class Seg(val app: AppEntry?, val isApps: Boolean, val isScan: Boolean = false, val isPay: Boolean = false)

    private fun segments(): List<Seg> {
        val list = ArrayList<Seg>()
        dock.take(10).forEach { list += Seg(it, false) }
        list += Seg(null, false, true)
        if (payEnabled) list += Seg(null, false, false, true)
        list += Seg(null, true)
        while (list.size < 6) list.add(list.size - 2, Seg(null, false))
        return list
    }

    private val segR1 get() = coreR * 0.78f
    private val segR2 get() = coreR

    private fun ticks(c: Canvas, r: Float, n: Int, len: Float, col: Int) {
        stroke.color = col; stroke.strokeWidth = 2f
        for (k in 0 until n) {
            val a = 2 * PI * k / n; val l = if (k % 6 == 0) len * 2 else len
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(cx + r * ca, cy + r * sa, cx + (r - l) * ca, cy + (r - l) * sa, stroke)
        }
    }

    private fun drawCore(c: Canvas, t: Float, now: Calendar) {
        // static-ish decoration (far layer)
        c.save(); c.translate(dX * 0.6f, dY * 0.6f)
        ticks(c, coreR * 1.13f, 144, unit * 0.22f, cyan(120))
        stroke.strokeWidth = 2f; stroke.color = cyan(120); c.drawCircle(cx, cy, coreR * 1.02f, stroke)
        stroke.strokeWidth = 1.5f; stroke.color = cyan(60); c.drawCircle(cx, cy, coreR * 1.24f, stroke)
        ticks(c, coreR * 0.70f, 90, unit * 0.15f, cyan(110))
        stroke.strokeWidth = 1.5f; stroke.color = cyan(70)
        c.drawLine(cx - coreR * 1.5f, cy, cx - coreR * 1.27f, cy, stroke)
        c.drawLine(cx + coreR * 1.27f, cy, cx + coreR * 1.5f, cy, stroke)

        // rotating text ring
        c.save(); c.rotate(-t * 4f, cx, cy)
        val tr = coreR * 1.185f
        val ring = Path().apply { addCircle(cx, cy, tr, Path.Direction.CW) }
        mono.textAlign = Paint.Align.LEFT; mono.textSize = unit * 0.3f; mono.color = cyan(120); mono.letterSpacing = 0.3f
        val seg = "HUD//OS  ◆  CORE ONLINE  ◆  SYS ${String.format(Locale.US, "%03d", dMem.toInt())}  ◆  PWR ${dBatt.toInt()}  ◆  "
        val circ = (2 * PI * tr).toFloat()
        var off = 0f; val segW = mono.measureText(seg)
        while (off + segW < circ) { c.drawTextOnPath(seg, ring, off, mono.textSize * 0.35f, mono); off += segW }
        mono.letterSpacing = 0f; mono.color = CYAN
        c.restore()

        c.restore()
        // radar sweep
        c.save(); c.translate(dX * 0.15f, dY * 0.15f)
        c.save(); c.rotate(t * 70f, cx, cy)
        stroke.color = Color.WHITE; stroke.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, cyan(110)), floatArrayOf(0f, 0.75f, 1f))
        stroke.strokeWidth = coreR * 0.12f
        c.drawCircle(cx, cy, coreR * 0.715f, stroke); stroke.shader = null
        stroke.strokeWidth = 3f; stroke.color = cyan(200)
        c.drawLine(cx + coreR * 0.655f, cy, cx + coreR * 0.775f, cy, stroke)
        c.restore()

        c.restore()
        // segments
        c.save(); c.translate(dX * 0.3f, dY * 0.3f)
        val segs = segments()
        val n = segs.size; val sw = 360f / n; val start = -90f - sw / 2
        val r1 = segR1; val r2 = segR2
        val outer = RectF(cx - r2, cy - r2, cx + r2, cy + r2)
        val inner = RectF(cx - r1, cy - r1, cx + r1, cy + r1)
        val mid = (r1 + r2) / 2; val midRect = RectF(cx - mid, cy - mid, cx + mid, cy + mid)
        val hl = (t * 0.5f) % n
        for ((i, sg) in segs.withIndex()) {
            val a0 = start + i * sw + 1.2f; val sweep = sw - 2.4f
            val p = Path()
            p.arcTo(outer, a0, sweep, true); p.arcTo(inner, a0 + sweep, -sweep); p.close()
            val near = 1f - min(1f, abs(hl - i).let { min(it, n - it) })
            val active = sg.app != null || sg.isApps || sg.isScan || sg.isPay
            fill.color = Color.WHITE; fill.shader = RadialGradient(cx, cy, r2, intArrayOf(cyan(0), cyan(if (sg.isApps) 70 else if (active) 35 else 10), cyan(if (sg.isApps) 120 else if (active) (55 + 60 * near).toInt() else 18)),
                floatArrayOf(0f, r1 / r2, 1f), Shader.TileMode.CLAMP)
            c.drawPath(p, fill); fill.shader = null
            if (active) { glow.strokeWidth = 6f; glow.color = cyan((60 + 80 * near).toInt()); c.drawPath(p, glow) }
            stroke.strokeWidth = 2f; stroke.color = cyan(if (active) 200 else 70); c.drawPath(p, stroke)
            val text = when { sg.isApps -> "APPS"; sg.isScan -> "◎ SCAN"; sg.isPay -> "¥ PAY"; sg.app != null -> sg.app.label.uppercase(); else -> "+" }
            label.textSize = (r2 - r1) * (if (active) (if (n > 8) 0.25f else 0.30f) else 0.4f); label.textAlign = Paint.Align.LEFT; label.letterSpacing = 0.08f
            label.color = if (active) CYAN else cyan(70)
            val center = (a0 + sweep / 2 + 360f) % 360f
            val bottomHalf = center in 0f..180f
            val arcLen = (Math.toRadians(sweep.toDouble()) * mid).toFloat()
            var shown = text
            while (label.measureText(shown) > arcLen * 0.84f && shown.length > 1) shown = shown.dropLast(1)
            if (shown != text) shown = shown.dropLast(1) + "…"
            val tp = Path()
            if (bottomHalf) tp.addArc(midRect, a0 + sweep, -sweep) else tp.addArc(midRect, a0, sweep)
            c.drawTextOnPath(shown, tp, (arcLen - label.measureText(shown)) / 2, label.textSize * 0.36f, label)
            label.letterSpacing = 0f
        }

        c.restore()
        // rotating outer accents
        c.save(); c.translate(dX * 0.75f, dY * 0.75f)
        arcAt(c, coreR * 1.08f, 4f, t * 14f, 300f, cyan(55), false)
        for (k in 0 until 4) arcAt(c, coreR * 1.08f, 7f, t * 14f + k * 90f, 28f, CYAN, true)
        arcAt(c, coreR * 1.29f, 6f, 210f + sin(t * 0.6f) * 25f, 30f, RED, true)
        for (k in 0 until 10) arcAt(c, coreR * 1.33f, 3f, k * 36f - t * 5f, 14f, cyan(150), false)

        c.restore()
        // dashed ring
        c.save(); c.translate(dX * 0.15f, dY * 0.15f)
        c.save(); c.rotate(-t * 22f, cx, cy)
        dash.strokeWidth = 4f; dash.color = cyan(170); c.drawCircle(cx, cy, coreR * 0.64f, dash)
        c.restore()
        val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        arcAt(c, coreR * 0.585f, 6f, -90f, sec / 60f * 360f, CYAN, true)
        val ha = Math.toRadians((-90f + sec / 60f * 360f).toDouble())
        fill.color = Color.WHITE
        c.drawCircle(cx + coreR * 0.585f * cos(ha).toFloat(), cy + coreR * 0.585f * sin(ha).toFloat(), unit * 0.14f, fill)

        for (k in 0 until 3) {
            val a = t * (0.9f + k * 0.35f) + k * 2.1f
            fill.color = CYAN
            c.drawCircle(cx + cos(a) * coreR * 0.72f, cy + sin(a) * coreR * 0.72f, unit * 0.12f, fill)
        }

        c.restore()
        // layered hexagon core (near layer, with shadow)
        val hr = coreR * 0.5f
        c.save(); c.translate(dX * 0.35f + unit * 0.2f, dY * 0.35f + unit * 0.5f)
        fill.color = Color.argb(110, 0, 0, 0)
        c.drawPath(hexPath(hr * 1.04f, t * 6f), fill)
        c.restore()
        c.save(); c.translate(-dX * 0.2f, -dY * 0.2f)
        fill.color = Color.WHITE; fill.shader = RadialGradient(cx, cy, hr, intArrayOf(cyan(80), cyan(20), cyan(0)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(hexPath(hr, t * 6f), fill); fill.shader = null
        val pulse = (sin(t * 2.2f) + 1) / 2
        glow.strokeWidth = 16f; glow.color = cyan((90 + 100 * pulse).toInt()); c.drawPath(hexPath(hr, t * 6f), glow)
        stroke.strokeWidth = 2.5f; stroke.color = Color.argb(255, 210, 245, 255); c.drawPath(hexPath(hr, t * 6f), stroke)
        stroke.strokeWidth = 1.5f; stroke.color = cyan(130); c.drawPath(hexPath(hr * 0.88f, -t * 10f), stroke)
        stroke.strokeWidth = 1f; stroke.color = cyan(70); c.drawPath(hexPath(hr * 0.76f, t * 15f), stroke)
        // hex vertices nodes
        for (k in 0 until 6) {
            val a = Math.toRadians((t * 6f + 30 + k * 60).toDouble())
            fill.color = cyan(230)
            c.drawCircle(cx + hr * cos(a).toFloat(), cy + hr * sin(a).toFloat(), unit * 0.1f, fill)
        }

        if (gl?.ready != true) { drawGlobe(c, hr * 0.8f, t); drawSatellites(c, hr * 0.8f, t) }
        // ③ voice pulse
        if (speakEnv > 0.02f) {
            for (k in 0 until 3) {
                val q = speakEnv * (1f - k * 0.25f)
                glow.strokeWidth = 18f; glow.color = cyan((200 * q).toInt()); c.drawPath(hexPath(hr * (1f + 0.12f * q + k * 0.08f), t * 6f), glow)
            }
            fill.color = Color.WHITE
            fill.shader = RadialGradient(cx, cy, hr, intArrayOf(cyan((120 * speakEnv).toInt()), cyan(0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, hr, fill); fill.shader = null
        }
        val hm = String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val big = coreR * 0.27f
        thin.textSize = big; textGlow.textSize = big
        c.drawText(hm, cx, cy + big * 0.33f, textGlow); c.drawText(hm, cx, cy + big * 0.33f, thin)
        mono.textAlign = Paint.Align.CENTER; mono.textSize = coreR * 0.06f; mono.color = cyan(210)
        mono.color = if (stats.battery <= 20 && !stats.charging) RED else cyan(220)
        c.drawText(String.format(Locale.US, "%02d.%02d  %sBATT %d%%", now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            if (stats.charging) "⚡" else "", dBatt.roundToInt()), cx, cy - big * 0.62f, mono)
        mono.color = cyan(210)
        val wd = now.get(Calendar.DAY_OF_WEEK) - 1
        label.textAlign = Paint.Align.CENTER; label.textSize = coreR * 0.065f; label.color = cyan(220); label.letterSpacing = 0.25f
        c.drawText(arrayOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")[wd], cx, cy + big * 0.95f, label)
        label.textSize = coreR * 0.042f; label.letterSpacing = 0.3f; label.color = cyan((120 + 80 * sin(t * 2f)).toInt())
        c.drawText(talkHint, cx, cy + big * 1.38f, label)
        c.restore()
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
        if (withGlow) { glow.strokeWidth = w * 1.8f; glow.color = withA(col, 150); c.drawArc(o, start, sweep, false, glow) }
        stroke.strokeWidth = w; stroke.color = col; c.drawArc(o, start, sweep, false, stroke)
    }

    // ---------- panel helpers ----------
    private fun body(r: RectF) = RectF(r.left + unit * 0.5f, r.top + headerH() + unit * 0.4f, r.right - unit * 0.5f, r.bottom - unit * 0.4f)

    private fun blockBar(c: Canvas, x: Float, y: Float, w: Float, h: Float, v: Float, warn: Boolean) {
        val n = 20; val gap = w * 0.008f; val bw = (w - gap * (n - 1)) / n
        val on = v.coerceIn(0f, 100f) * n / 100f
        val col = if (warn) RED else CYAN
        for (k in 0 until n) {
            val f = (on - k).coerceIn(0f, 1f)
            fill.color = cyan(26); c.drawRect(x + k * (bw + gap), y, x + k * (bw + gap) + bw, y + h, fill)
            if (f > 0f) {
                fill.color = Color.WHITE; fill.shader = LinearGradient(0f, y, 0f, y + h, withA(col, 255), withA(col, 150), Shader.TileMode.CLAMP)
                c.drawRect(x + k * (bw + gap), y + h * (1 - f), x + k * (bw + gap) + bw, y + h, fill); fill.shader = null
            }
        }
        glow.strokeWidth = 4f; glow.color = withA(col, 70)
        c.drawLine(x, y + h / 2, x + w * v.coerceIn(0f, 100f) / 100f, y + h / 2, glow)
    }

    /** autoscale graph with glow line and live endpoint */
    private fun graph(c: Canvas, r: RectF, data: List<Float>, col: Int, t: Float, fixedMax: Float? = null, grid: Boolean = true) {
        if (grid) {
            stroke.strokeWidth = 1f; stroke.color = cyan(28)
            for (k in 1..3) { val y = r.top + r.height() * k / 4; c.drawLine(r.left, y, r.right, y, stroke) }
            for (k in 1..7) { val x = r.left + r.width() * k / 8; c.drawLine(x, r.top, x, r.bottom, stroke) }
            stroke.strokeWidth = 1.5f; stroke.color = cyan(80); c.drawRect(r, stroke)
        }
        if (data.size < 2) return
        val lo: Float; val hi: Float
        if (fixedMax != null) { lo = 0f; hi = max(fixedMax, 1e-3f) }
        else {
            val mn = data.min(); val mx = data.max()
            val span = max(mx - mn, 4f)
            lo = (mn + mx) / 2 - span * 0.7f; hi = (mn + mx) / 2 + span * 0.7f
        }
        val line = Path(); val area = Path()
        val step = r.width() / (data.size - 1)
        var lx = 0f; var ly = 0f
        data.forEachIndexed { i, v ->
            val x = r.left + i * step; val y = r.bottom - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * r.height() * 0.9f - r.height() * 0.04f
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, r.bottom); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
            lx = x; ly = y
        }
        area.lineTo(r.right, r.bottom); area.close()
        fill.color = Color.WHITE; fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, withA(col, 110), withA(col, 0), Shader.TileMode.CLAMP)
        c.drawPath(area, fill); fill.shader = null
        glow.strokeWidth = 8f; glow.color = withA(col, 130); c.drawPath(line, glow)
        stroke.strokeWidth = 3f; stroke.color = col; c.drawPath(line, stroke)
        val pr = unit * 0.14f * (1.6f + sin(t * 4f) * 0.6f)
        stroke.strokeWidth = 2f; stroke.color = withA(col, 160); c.drawCircle(lx, ly, pr * 2, stroke)
        fill.color = Color.WHITE; c.drawCircle(lx, ly, unit * 0.12f, fill)
    }

    private fun kv(c: Canvas, x1: Float, x2: Float, y: Float, k: String, v: String, size: Float, vcol: Int = CYAN) {
        label.textAlign = Paint.Align.LEFT; label.textSize = size; label.color = cyan(170); label.letterSpacing = 0.1f
        c.drawText(k, x1, y, label); label.letterSpacing = 0f
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = size; mono.color = vcol
        c.drawText(v, x2, y, mono); mono.color = CYAN; label.color = CYAN
    }

    private fun bigNum(c: Canvas, text: String, x: Float, y: Float, size: Float, col: Int): Float {
        mono.textAlign = Paint.Align.LEFT; mono.textSize = size; mono.color = col
        monoGlow.textAlign = Paint.Align.LEFT; monoGlow.textSize = size; monoGlow.color = withA(col, 160)
        c.drawText(text, x, y, monoGlow); c.drawText(text, x, y, mono)
        mono.color = CYAN
        return mono.measureText(text)
    }

    private fun caption(c: Canvas, r: RectF, text: String, fs: Float) {
        label.textAlign = Paint.Align.LEFT; label.textSize = fs; label.color = cyan(150); label.letterSpacing = 0.12f
        c.drawText(text, r.left + fs * 0.4f, r.top + fs * 1.2f, label); label.letterSpacing = 0f; label.color = CYAN
    }

    // ---------- panels ----------
    private fun drawSystem(c: Canvas, r: RectF, t: Float) {
        val b = body(r); val s = stats
        val fs = min(unit * 0.62f, b.height() / 11f)
        val rows = listOf(Triple("MEMORY", dMem, s.memory > 90), Triple("STORAGE", dSto, s.storage > 90),
            Triple("BATTERY", dBatt, s.battery <= 20 && !s.charging))
        var y = b.top + fs
        for ((k, v, warn) in rows) {
            kv(c, b.left, b.right, y, k, "${v.roundToInt()}%", fs, if (warn) RED else CYAN)
            blockBar(c, b.left, y + fs * 0.4f, b.width(), fs * 0.75f, v, warn)
            y += fs * 2.6f
        }
        val g = RectF(b.left, y - fs * 0.2f, b.right, b.bottom)
        if (g.height() > fs * 2) {
            graph(c, g, s.memHist, CYAN, t)
            caption(c, g, "MEMORY LOAD / 2 MIN", fs * 0.7f)
        }
    }

    private fun drawPower(c: Canvas, r: RectF, t: Float) {
        val b = body(r); val s = stats
        val fs = min(unit * 0.62f, b.height() / 10f)
        val bigS = fs * 3.2f
        val col = if (s.battery <= 20 && !s.charging) RED else CYAN
        // liquid energy cell
        val cellW = bigS * 0.62f; val cellH = bigS * 0.95f
        val cell = RectF(b.left, b.top + fs * 0.1f, b.left + cellW, b.top + fs * 0.1f + cellH)
        liquidCell(c, cell, dBatt / 100f, col, t)
        val w = bigNum(c, "${dBatt.roundToInt()}", cell.right + fs * 0.5f, b.top + bigS * 0.85f, bigS, col)
        mono.textAlign = Paint.Align.LEFT; mono.textSize = fs * 1.2f; mono.color = col
        c.drawText("%", cell.right + fs * 0.5f + w + fs * 0.2f, b.top + bigS * 0.85f, mono); mono.color = CYAN
        label.textAlign = Paint.Align.RIGHT; label.textSize = fs * 0.8f; label.letterSpacing = 0.15f
        val blink = (t * 2).toInt() % 2 == 0
        label.color = if (s.charging) (if (blink) CYAN else cyan(120)) else cyan(160)
        c.drawText(if (s.charging) "⚡ CHARGING" else "DISCHARGING", b.right, b.top + fs, label)
        label.letterSpacing = 0f; label.color = CYAN
        var y = b.top + bigS * 0.85f + fs * 1.6f
        kv(c, b.left, b.right, y, "TEMP", String.format(Locale.US, "%.1f°C", s.battTemp), fs, if (s.battTemp >= 42) RED else CYAN); y += fs * 1.5f
        kv(c, b.left, b.right, y, "VOLTAGE", String.format(Locale.US, "%.2fV", s.voltage / 1000f), fs); y += fs * 1.5f
        val up = s.uptimeMs / 1000
        kv(c, b.left, b.right, y, "UPTIME", String.format(Locale.US, "%dd %02d:%02d", up / 86400, up / 3600 % 24, up / 60 % 60), fs); y += fs * 0.8f
        val g = RectF(b.left, y, b.right, b.bottom)
        if (g.height() > fs * 2) {
            graph(c, g, s.battHist, col, t)
            caption(c, g, "BATTERY LEVEL / 2 H", fs * 0.7f)
        }
    }

    private fun liquidCell(c: Canvas, r: RectF, level: Float, col: Int, t: Float) {
        val rr = r.width() * 0.25f
        c.save()
        val clip = Path().apply { addRoundRect(r, rr, rr, Path.Direction.CW) }
        c.clipPath(clip)
        fill.color = Color.argb(120, 0, 20, 34); c.drawRect(r, fill)
        val surface = r.bottom - r.height() * level.coerceIn(0f, 1f)
        for (layer in 0 until 2) {
            val p = Path(); p.moveTo(r.left, r.bottom)
            var x = r.left
            while (x <= r.right + 2) {
                val y = surface + sin(x / r.width() * 6.3f + t * (2.4f + layer) + layer * 2f) * r.height() * 0.035f
                p.lineTo(x, y); x += 3f
            }
            p.lineTo(r.right, r.bottom); p.close()
            fill.color = Color.WHITE
            fill.shader = LinearGradient(0f, surface, 0f, r.bottom, withA(col, if (layer == 0) 120 else 220), withA(col, if (layer == 0) 40 else 90), Shader.TileMode.CLAMP)
            c.drawPath(p, fill); fill.shader = null
        }
        // bubbles
        val rnd = Random(5)
        repeat(6) {
            val bx = r.left + rnd.nextFloat() * r.width()
            val by = r.bottom - ((t * (0.15f + rnd.nextFloat() * 0.2f) + rnd.nextFloat()) % 1f) * (r.bottom - surface)
            fill.color = Color.argb(150, 255, 255, 255); c.drawCircle(bx, by, 2.5f, fill)
        }
        c.restore()
        glow.strokeWidth = 8f; glow.color = withA(col, 110); c.drawRoundRect(r, rr, rr, glow)
        stroke.strokeWidth = 2.5f; stroke.color = col; c.drawRoundRect(r, rr, rr, stroke)
        fill.color = col; c.drawRect(r.centerX() - r.width() * 0.22f, r.top - r.width() * 0.12f, r.centerX() + r.width() * 0.22f, r.top, fill)
        for (k in 1..3) { stroke.strokeWidth = 1f; stroke.color = cyan(70); val y = r.top + r.height() * k / 4; c.drawLine(r.left, y, r.left + r.width() * 0.2f, y, stroke) }
    }

    private fun drawCalendar(c: Canvas, r: RectF, now: Calendar) {
        val b = body(r)
        val cal = now.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val first = cal.get(Calendar.DAY_OF_WEEK) - 1
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = now.get(Calendar.DAY_OF_MONTH)
        val rows = (first + days + 6) / 7
        val headH = b.height() * 0.15f
        val mn = arrayOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")
        val title = "${mn[now.get(Calendar.MONTH)]} ${now.get(Calendar.YEAR)}"
        label.textAlign = Paint.Align.LEFT; label.letterSpacing = 0.2f
        label.textSize = min(headH * 0.7f, unit * 0.9f)
        while (label.measureText(title) > b.width() && label.textSize > 8f) label.textSize *= 0.92f
        c.drawText(title, b.left, b.top + headH * 0.72f, label)
        label.letterSpacing = 0f
        val cw = b.width() / 7; val gTop = b.top + headH * 1.15f
        val ch = (b.bottom - gTop) / (rows + 1)
        mono.textAlign = Paint.Align.CENTER
        val fs = min(ch * 0.55f, cw * 0.48f)
        val wk = arrayOf("S", "M", "T", "W", "T", "F", "S")
        for (k in 0 until 7) {
            mono.textSize = fs * 0.85f; mono.color = if (k == 0) Color.argb(200, 255, 110, 110) else cyan(150)
            c.drawText(wk[k], b.left + cw * (k + 0.5f), gTop + ch * 0.65f, mono)
        }
        stroke.strokeWidth = 1f; stroke.color = cyan(50)
        c.drawLine(b.left, gTop + ch * 0.95f, b.right, gTop + ch * 0.95f, stroke)
        for (d in 1..days) {
            val idx = first + d - 1; val col = idx % 7; val row = idx / 7 + 1
            val x = b.left + cw * (col + 0.5f); val y = gTop + ch * row + ch * 0.65f
            mono.textSize = fs
            if (d == today) {
                val rr = RectF(x - cw * 0.42f, gTop + ch * row + ch * 0.08f, x + cw * 0.42f, gTop + ch * (row + 1) - ch * 0.08f)
                glow.strokeWidth = 10f; glow.color = cyan(160); c.drawRect(rr, glow)
                fill.color = CYAN; c.drawRect(rr, fill)
                mono.color = DARK
            } else mono.color = when { d < today -> cyan(70); col == 0 -> Color.argb(220, 255, 110, 110); else -> cyan(220) }
            c.drawText(d.toString(), x, y, mono)
        }
        mono.color = CYAN
    }

    private fun drawWeather(c: Canvas, r: RectF, t: Float) {
        val b = body(r); val s = stats
        val fs = min(unit * 0.6f, b.height() / (if (wide) 10f else 12f))
        label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.75f; label.color = cyan(160); label.letterSpacing = 0.15f
        c.drawText(s.place, b.left, b.top + fs * 0.8f, label); label.letterSpacing = 0f
        val bigS = fs * 3.3f
        val tt = if (s.temp != null) "${dTemp.roundToInt()}" else "--"
        val w = bigNum(c, tt, b.left, b.top + fs * 1.2f + bigS * 0.85f, bigS, CYAN)
        mono.textAlign = Paint.Align.LEFT; mono.textSize = fs * 1.3f
        c.drawText("°C", b.left + w + fs * 0.2f, b.top + fs * 1.2f + bigS * 0.4f, mono)
        label.textAlign = Paint.Align.RIGHT; label.textSize = fs * 1.25f; label.color = CYAN
        c.drawText(s.weather, b.right, b.top + fs * 1.2f + bigS * 0.8f, label)
        var y = b.top + fs * 1.2f + bigS + fs * 1.1f
        kv(c, b.left, b.right, y, "FEELS", s.feels?.let { "${it.roundToInt()}°C" } ?: "--", fs); y += fs * 1.45f
        kv(c, b.left, b.right, y, "HUMIDITY", "${s.humidity}%", fs); y += fs * 1.45f
        kv(c, b.left, b.right, y, "WIND", String.format(Locale.US, "%.1fm/s", s.wind), fs); y += fs * 1.45f
        kv(c, b.left, b.right, y, "SUN ↑↓", "${s.sunrise}  ${s.sunset}", fs); y += fs * 0.9f
        run {
            s.forecast.drop(1).take(2).forEach {
                y += fs * 1.35f
                label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.95f; label.color = cyan(210); c.drawText(it, b.left, y, label)
            }
            label.color = CYAN; y += fs * 0.6f
        }
        val g = RectF(b.left, y, b.right, b.bottom)
        if (g.height() > fs * 2.5f && s.hourly.size > 2) {
            graph(c, g, s.hourly, BLUE, t)
            caption(c, g, String.format(Locale.US, "TEMP 24H   %d° – %d°", s.hourly.min().roundToInt(), s.hourly.max().roundToInt()), fs * 0.7f)
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
        val compact = b.width() < unit * 16
        val infoW = if (compact) 0f else if (wide) b.width() * 0.16f else b.width() * 0.32f
        val g = if (compact) RectF(b.left, b.top + fs * 2.6f, b.right, b.bottom) else RectF(b.left + infoW, b.top, b.right, b.bottom)
        if (compact) {
            label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.7f; label.color = cyan(160)
            c.drawText("▼", b.left, b.top + fs * 1.9f, label)
            c.drawText("▲", b.centerX(), b.top + fs * 1.9f, label); label.color = CYAN
            bigNum(c, rate(s.rxKbps), b.left + fs, b.top + fs * 1.9f, fs * 1.05f, CYAN)
            bigNum(c, rate(s.txKbps), b.centerX() + fs, b.top + fs * 1.9f, fs * 1.05f, BLUE)
        } else {
            label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.75f; label.color = cyan(160); label.letterSpacing = 0.15f
            c.drawText("▼ DOWN", b.left, b.top + fs, label)
            c.drawText("▲ UP", b.left, b.top + b.height() * 0.55f, label)
            label.letterSpacing = 0f; label.color = CYAN
            bigNum(c, rate(s.rxKbps), b.left, b.top + fs * 2.6f, fs * 1.5f, CYAN)
            bigNum(c, rate(s.txKbps), b.left, b.top + b.height() * 0.55f + fs * 1.6f, fs * 1.5f, BLUE)
        }
        // sqrt scale so small traffic is visible
        val rx = s.rxHist.map { sqrt(it) }; val tx = s.txHist.map { sqrt(it) }
        val mx = max(max(rx.maxOrNull() ?: 0f, tx.maxOrNull() ?: 0f), sqrt(32f)) * 1.1f
        graph(c, g, rx, CYAN, t, fixedMax = mx)
        graph(c, g, tx, BLUE, t, fixedMax = mx, grid = false)
        val sx = g.left + ((t * 0.25f) % 1f) * g.width()
        fill.color = Color.WHITE; fill.shader = LinearGradient(sx - unit * 2, 0f, sx, 0f, Color.TRANSPARENT, cyan(60), Shader.TileMode.CLAMP)
        c.drawRect(sx - unit * 2, g.top, sx, g.bottom, fill); fill.shader = null
        if (s.rxKbps < 1f && s.txKbps < 1f) {
            label.textAlign = Paint.Align.CENTER; label.textSize = fs * 0.9f; label.letterSpacing = 0.4f
            label.color = cyan(if ((t * 1.2f).toInt() % 2 == 0) 160 else 70)
            c.drawText("LINK IDLE", g.centerX(), g.centerY(), label)
            label.letterSpacing = 0f; label.color = CYAN
        }
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = fs * 0.6f; mono.color = cyan(130)
        val peak = max(s.rxHist.maxOrNull() ?: 0f, s.txHist.maxOrNull() ?: 0f)
        if (!compact) c.drawText("PEAK ${rate(peak)}  /  60s", g.right - fs * 0.3f, g.top + fs * 0.9f, mono)
        mono.color = CYAN
    }

    // ---------- media ----------
    private val mediaBtns = HashMap<String, RectF>()
    private val mediaArt = RectF()

    private fun fmtTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return String.format(Locale.US, "%d:%02d", s / 60, s % 60) }

    private fun drawMedia(c: Canvas, r: RectF, t: Float) {
        val b = body(r); val m = media
        mediaBtns.clear(); mediaArt.setEmpty()
        val fs = min(unit * 0.6f, b.height() / 7f)
        if (m == null) {
            label.textAlign = Paint.Align.CENTER; label.textSize = fs * 0.9f; label.letterSpacing = 0.35f
            label.color = cyan(if ((t * 1.2f).toInt() % 2 == 0) 140 else 70)
            c.drawText("NO AUDIO SIGNAL", b.centerX(), b.centerY() - fs * 0.2f, label)
            label.textSize = fs * 0.62f; label.letterSpacing = 0.1f; label.color = cyan(110)
            c.drawText("音楽を再生するとここに表示", b.centerX(), b.centerY() + fs * 1.2f, label)
            label.letterSpacing = 0f; label.color = CYAN
            // idle flat visualizer
            stroke.strokeWidth = 2f; stroke.color = cyan(60)
            c.drawLine(b.left, b.bottom - fs * 0.5f, b.right, b.bottom - fs * 0.5f, stroke)
            return
        }
        val btnH = fs * 1.9f
        val artS = min(b.height() - btnH - fs * 0.6f, b.width() * 0.38f)
        mediaArt.set(b.left, b.top, b.left + artS, b.top + artS)
        // art with rotating ring frame
        c.save()
        val clip = Path().apply { addRoundRect(mediaArt, unit * 0.3f, unit * 0.3f, Path.Direction.CW) }
        c.clipPath(clip)
        if (m.art != null) c.drawBitmap(m.art, null, mediaArt, Paint(Paint.FILTER_BITMAP_FLAG))
        else { fill.color = cyan(30); c.drawRect(mediaArt, fill); label.textAlign = Paint.Align.CENTER; label.textSize = artS * 0.4f; c.drawText("♪", mediaArt.centerX(), mediaArt.centerY() + artS * 0.14f, label) }
        fill.color = Color.argb(40, 0, 229, 255); c.drawRect(mediaArt, fill)
        c.restore()
        glow.strokeWidth = 8f; glow.color = cyan(if (m.playing) 150 else 60); c.drawRoundRect(mediaArt, unit * 0.3f, unit * 0.3f, glow)
        stroke.strokeWidth = 2f; stroke.color = CYAN; c.drawRoundRect(mediaArt, unit * 0.3f, unit * 0.3f, stroke)

        // texts
        val tx = mediaArt.right + fs * 0.7f; val tw = b.right - tx
        label.textAlign = Paint.Align.LEFT; label.textSize = fs * 0.62f; label.color = cyan(150); label.letterSpacing = 0.2f
        c.drawText(if (m.playing) "▶ NOW PLAYING" else "❚❚ PAUSED", tx, b.top + fs * 0.7f, label)
        label.letterSpacing = 0f
        label.textSize = fs * 1.05f; label.color = CYAN
        c.drawText(android.text.TextUtils.ellipsize(m.title, android.text.TextPaint(label), tw, android.text.TextUtils.TruncateAt.END).toString(), tx, b.top + fs * 2.0f, label)
        label.textSize = fs * 0.8f; label.color = cyan(190)
        c.drawText(android.text.TextUtils.ellipsize(m.artist, android.text.TextPaint(label), tw, android.text.TextUtils.TruncateAt.END).toString(), tx, b.top + fs * 3.0f, label)
        label.color = CYAN

        // visualizer
        val vTop = b.top + fs * 3.5f; val vBot = mediaArt.bottom - fs * 1.3f
        if (vBot - vTop > fs * 0.6f) {
            val n = 24; val bw = tw / n
            for (k in 0 until n) {
                val e = if (m.playing) (0.15f + 0.85f * abs(sin(t * (3.1f + k * 0.37f) + k * 1.7f) * sin(t * 1.3f + k * 0.5f))) else 0.06f
                val h = (vBot - vTop) * e
                fill.color = Color.WHITE; fill.shader = LinearGradient(0f, vBot - h, 0f, vBot, CYAN, cyan(60), Shader.TileMode.CLAMP)
                c.drawRect(tx + k * bw + bw * 0.18f, vBot - h, tx + (k + 1) * bw - bw * 0.18f, vBot, fill); fill.shader = null
            }
        }
        // progress
        val pos = if (m.playing) m.position + ((android.os.SystemClock.elapsedRealtime() - m.updateTime) * m.speed).toLong() else m.position
        val py0 = mediaArt.bottom - fs * 0.35f
        if (m.duration > 0) {
            val f = (pos.toFloat() / m.duration).coerceIn(0f, 1f)
            fill.color = cyan(40); c.drawRect(tx, py0 - 4, b.right, py0 + 4, fill)
            fill.color = CYAN; c.drawRect(tx, py0 - 4, tx + tw * f, py0 + 4, fill)
            glow.strokeWidth = 6f; glow.color = cyan(160); c.drawLine(tx, py0, tx + tw * f, py0, glow)
            fill.color = Color.WHITE; c.drawCircle(tx + tw * f, py0, unit * 0.16f, fill)
            mono.textAlign = Paint.Align.LEFT; mono.textSize = fs * 0.6f; mono.color = cyan(170)
            c.drawText(fmtTime(pos), tx, py0 - fs * 0.45f, mono)
            mono.textAlign = Paint.Align.RIGHT; c.drawText(fmtTime(m.duration), b.right, py0 - fs * 0.45f, mono)
            mono.color = CYAN
        }
        // buttons
        val by = b.bottom - btnH
        val bw = (b.width() - fs) / 3
        listOf("prev" to "⏮", "toggle" to (if (m.playing) "❚❚" else "▶"), "next" to "⏭").forEachIndexed { i, (key, sym) ->
            val rr = RectF(b.left + i * (bw + fs / 2), by, b.left + i * (bw + fs / 2) + bw, b.bottom)
            mediaBtns[key] = rr
            val prim = key == "toggle"
            fill.color = cyan(if (prim) 60 else 20); c.drawRect(rr, fill)
            if (prim) { glow.strokeWidth = 6f; glow.color = cyan(120); c.drawRect(rr, glow) }
            stroke.strokeWidth = 2f; stroke.color = cyan(if (prim) 230 else 150); c.drawRect(rr, stroke)
            label.textAlign = Paint.Align.CENTER; label.textSize = btnH * 0.48f; label.color = CYAN
            c.drawText(sym, rr.centerX(), rr.centerY() + label.textSize * 0.36f, label)
        }
    }

    private fun drawModule(c: Canvas, r: RectF, t: Float) {
        val b = moduleBody() ?: return
        label.textAlign = Paint.Align.RIGHT; label.textSize = headerH() * 0.42f; label.color = cyan(140); label.letterSpacing = 0.15f
        c.drawText(if (moduleLinked) "LONG-PRESS HEADER TO EDIT" else "", r.right - headerH() * 1.6f, r.top + headerH() * 0.66f, label)
        label.letterSpacing = 0f
        if (moduleLinked) return
        val fs = min(unit * 0.7f, b.height() / 4f)
        dash.strokeWidth = 2f; dash.color = cyan(110); c.drawRect(b, dash)
        label.textAlign = Paint.Align.CENTER; label.textSize = fs * 1.1f; label.letterSpacing = 0.3f
        label.color = cyan(if ((t * 1.2f).toInt() % 2 == 0) 220 else 120)
        c.drawText("+  LINK MODULE", b.centerX(), b.centerY() - fs * 0.1f, label)
        label.textSize = fs * 0.75f; label.letterSpacing = 0.05f; label.color = cyan(150)
        c.drawText("タップしてウィジェットを追加（テスラなど）", b.centerX(), b.centerY() + fs * 1.3f, label)
        label.letterSpacing = 0f; label.color = CYAN
    }

    private fun drawFlash(c: Canvas) {
        val r = flashRect ?: return
        val age = System.currentTimeMillis() - flashStart
        if (age > 700) { flashRect = null; return }
        val p = age / 450f
        val fx = r.centerX(); val fy = r.centerY()
        val maxR = hypot(width.toFloat(), height.toFloat())
        for (k in 0 until 3) {
            val q = (p - k * 0.12f).coerceIn(0f, 1f)
            if (q <= 0f) continue
            glow.strokeWidth = 14f; glow.color = cyan((200 * (1 - q)).toInt()); c.drawCircle(fx, fy, maxR * 0.6f * ease(q), glow)
            stroke.strokeWidth = 3f; stroke.color = cyan((255 * (1 - q)).toInt()); c.drawCircle(fx, fy, maxR * 0.6f * ease(q), stroke)
        }
        // targeting brackets
        val a = unit * 1.4f * (1.6f - min(1f, p) * 0.6f)
        stroke.strokeWidth = 5f; stroke.color = CYAN
        for ((dx, dy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val x = fx + dx * a; val y = fy + dy * a
            c.drawLine(x, y, x - dx * a * 0.5f, y, stroke); c.drawLine(x, y, x, y - dy * a * 0.5f, stroke)
        }
        label.textAlign = Paint.Align.CENTER; label.textSize = unit * 0.8f; label.letterSpacing = 0.3f; label.color = CYAN
        val ty = if (fy > height * 0.3f) fy - a * 1.4f else fy + a * 1.8f
        c.drawText("LAUNCHING  ${flashLabel.uppercase()}", fx, ty, label); label.letterSpacing = 0f
        postInvalidateOnAnimation()
    }

    private fun rate(kbps: Float) = if (kbps >= 1024) String.format(Locale.US, "%.1f MB/s", kbps / 1024) else String.format(Locale.US, "%.0f KB/s", kbps)

    private fun drawStatus(c: Canvas, r: RectF, t: Float) {
        val s = stats
        val y1 = r.top + r.height() * 0.42f; val y2 = r.top + r.height() * 0.82f
        label.textAlign = Paint.Align.LEFT; label.textSize = r.height() * 0.26f; label.letterSpacing = 0.2f
        label.color = if ((t * 1.5f).toInt() % 2 == 0) CYAN else cyan(110)
        c.drawText("● ALL SYSTEMS NOMINAL", r.left + unit * 0.6f, y1, label)
        label.letterSpacing = 0f; label.color = CYAN
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = r.height() * 0.24f; mono.color = cyan(200)
        c.drawText(String.format(Locale.US, "MEM %02d%%  PWR %02d%%", dMem.roundToInt(), dBatt.roundToInt()), r.right - unit * 0.6f, y1, mono)
        mono.textAlign = Paint.Align.LEFT; mono.textSize = r.height() * 0.2f; mono.color = cyan(100)
        var hs = hexStream((t * 4).toLong() + 99, 60)
        while (mono.measureText(hs) > r.width() - unit * 1.2f && hs.length > 4) hs = hs.dropLast(5)
        c.drawText(hs, r.left + unit * 0.6f, y2, mono)
        mono.color = CYAN
    }

    // ======================= INPUT =======================
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        scaler.onTouchEvent(e)
        if (scaler.isInProgress) { lastTouch = System.currentTimeMillis(); return true }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
        val wasIdle = idleP > 0.3f
        if (e.action == MotionEvent.ACTION_DOWN) { lastTouch = System.currentTimeMillis(); listener?.onUserActivity(); invalidate() }
        if (wasIdle) {
            if (e.action == MotionEvent.ACTION_UP) { SoundFx.play(SoundFx.Fx.TAP); bootStart = System.currentTimeMillis() - 300 }
            return true
        }
        if (expanded != null) {
            if (e.action == MotionEvent.ACTION_UP && !collapsing) {
                val name = expanded!!
                if (expandOpenBtn.contains(e.x, e.y)) { SoundFx.play(SoundFx.Fx.OPEN); listener?.onPanelTap(name); collapse() }
                else if (name == "MEDIA" && media != null && mediaBtns.entries.firstOrNull { it.value.contains(e.x, e.y) }?.let { SoundFx.play(SoundFx.Fx.TAP); listener?.onMediaAction(it.key); true } == true) { }
                else { SoundFx.play(SoundFx.Fx.BACK); collapse() }
            }
            return true
        }
        return gestures.onTouchEvent(e)
    }

    private fun segmentAt(x0: Float, y0: Float): Seg? {
        val x = x0 - dX * 0.8f; val y = y0 - dY * 0.8f
        val d = hypot(x - cx, y - cy)
        if (d < segR1 || d > segR2 * 1.05f) return null
        val segs = segments(); val n = segs.size; val sw = 360f / n
        var ang = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        ang = (ang - (-90f - sw / 2) + 720f) % 360f
        return segs[(ang / sw).toInt().coerceIn(0, n - 1)]
    }

    private fun tap(x: Float, y: Float) {
        if (y < topStrip.bottom + unit * 0.5f && x < width * 0.5f) { SoundFx.play(SoundFx.Fx.TAP); listener?.onTopStripTap(); return }
        segmentAt(x, y)?.let { s ->
            when {
                s.isScan -> { SoundFx.play(SoundFx.Fx.OPEN); listener?.onScan() }
                s.isPay -> { SoundFx.play(SoundFx.Fx.OPEN); listener?.onPay() }
                s.isApps || s.app == null -> { SoundFx.play(SoundFx.Fx.OPEN); listener?.onOpenDrawer() }
                else -> {
                    SoundFx.play(SoundFx.Fx.OPEN)
                    val src = Rect((x - unit * 2).toInt(), (y - unit * 2).toInt(), (x + unit * 2).toInt(), (y + unit * 2).toInt())
                    flashLaunch(src, s.app.label)
                    listener?.onLaunch(s.app, src)
                }
            }
            return
        }
        panels["MODULE"]?.let { if (it.contains(x, y)) { if (!moduleLinked) listener?.onModuleEdit(); return } }
        if (panels["MEDIA"]?.contains(x, y) == true && media != null) {
            for ((k, r) in mediaBtns) if (r.contains(x, y)) { SoundFx.play(SoundFx.Fx.TAP); listener?.onMediaAction(k); return }
            if (mediaArt.contains(x, y)) { listener?.onMediaAction("open"); return }
            return
        }
        if (inCore(x, y)) { SoundFx.play(SoundFx.Fx.LISTEN); listener?.onClockTap(); return }
        for ((name, r) in panels) if (r.contains(x, y)) {
            if (name == "MODULE") return
            SoundFx.play(SoundFx.Fx.OPEN); expand(name); return
        }
        ripples += Ripple(x, y, System.currentTimeMillis()); SoundFx.play(SoundFx.Fx.TAP); invalidate()
    }

    private fun longPress(x: Float, y: Float) {
        segmentAt(x, y)?.app?.let { SoundFx.play(SoundFx.Fx.DONE); listener?.onDockLongPress(it); return }
        panels["MODULE"]?.let { if (it.contains(x, y)) { listener?.onModuleEdit(); return } }
        if (inCore(x, y)) { SoundFx.play(SoundFx.Fx.TAP); listener?.onCoreLongPress(); return }
        if (y < topStrip.bottom + unit) listener?.onHomeSettings()
    }
}

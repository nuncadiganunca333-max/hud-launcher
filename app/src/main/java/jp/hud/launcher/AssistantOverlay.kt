package jp.hud.launcher

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Claude アシスタントの対話パネル */
class AssistantOverlay(context: Context) : View(context) {

    enum class State { LISTENING, THINKING, SPEAKING, IDLE, ERROR }

    interface Listener {
        fun onAssistantMic()
        fun onAssistantClose()
    }

    var listener: Listener? = null
    var title = "ATLAS"
    var state = State.IDLE
        set(v) { field = v; invalidate() }
    var level = 0f          // mic level 0..1
    var heard = ""
        set(v) { field = v; invalidate() }
    var reply = ""
        set(v) { field = v; invalidate() }

    private val CYAN = Color.rgb(0, 229, 255)
    private val RED = Color.rgb(255, 70, 70)
    private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 0, 229, 255)
    private val start = System.nanoTime()
    private var shownAt = 0L
    private var lvl = 0f

    private val panel = RectF()
    private val micR = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(215, 248, 255) }
    private val heardP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 229, 255) }

    init { visibility = GONE }

    val isShowing get() = visibility == VISIBLE

    fun open() { shownAt = System.currentTimeMillis(); heard = ""; reply = ""; visibility = VISIBLE; invalidate() }
    fun close() { visibility = GONE; state = State.IDLE }

    override fun onDraw(c: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        val t = (System.nanoTime() - start) / 1e9f
        val appear = min(1f, (System.currentTimeMillis() - shownAt) / 300f)
        lvl += (level - lvl) * 0.3f

        fill.shader = LinearGradient(0f, H * 0.3f, 0f, H, Color.TRANSPARENT, Color.argb((220 * appear).toInt(), 0, 6, 14), Shader.TileMode.CLAMP)
        fill.color = Color.WHITE
        c.drawRect(0f, 0f, W, H, fill); fill.shader = null

        val u = min(W, H) / 30f
        val pw = min(W * 0.92f, u * 26f); val ph = min(H * 0.46f, u * 17f)
        val cx = W / 2
        val bottom = H - u * 2.2f
        panel.set(cx - pw / 2, bottom - ph * (0.4f + 0.6f * appear), cx + pw / 2, bottom)

        fill.color = Color.argb(235, 2, 20, 36); c.drawRect(panel, fill)
        glow.strokeWidth = 10f; glow.color = cyan(120); c.drawRect(panel, glow)
        stroke.strokeWidth = 2.5f; stroke.color = CYAN; c.drawRect(panel, stroke)
        stroke.strokeWidth = 7f
        val a = u * 1.5f; val o = u * 0.45f
        for ((x, y, dx, dy) in listOf(Q(panel.left - o, panel.top - o, 1f, 1f), Q(panel.right + o, panel.top - o, -1f, 1f),
                Q(panel.left - o, panel.bottom + o, 1f, -1f), Q(panel.right + o, panel.bottom + o, -1f, -1f))) {
            c.drawLine(x, y, x + dx * a, y, stroke); c.drawLine(x, y, x, y + dy * a, stroke)
        }

        // header
        val hh = u * 1.5f
        fill.color = cyan(40); c.drawRect(panel.left, panel.top, panel.right, panel.top + hh, fill)
        label.textAlign = Paint.Align.LEFT; label.textSize = hh * 0.5f; label.letterSpacing = 0.25f; label.color = CYAN
        c.drawText("◆ $title // ASSISTANT", panel.left + u * 0.7f, panel.top + hh * 0.68f, label)
        label.textAlign = Paint.Align.RIGHT
        val st = when (state) {
            State.LISTENING -> "LISTENING"; State.THINKING -> "PROCESSING"; State.SPEAKING -> "RESPONDING"
            State.ERROR -> "ERROR"; State.IDLE -> "STANDBY"
        }
        label.color = if (state == State.ERROR) RED else if ((t * 2).toInt() % 2 == 0) CYAN else cyan(130)
        c.drawText("● $st", panel.right - u * 0.7f, panel.top + hh * 0.68f, label)
        label.letterSpacing = 0f; label.color = CYAN

        // mic orb (left)
        val orbR = u * 2.4f
        val ox = panel.left + u * 1.2f + orbR; val oy = panel.top + hh + u * 1.2f + orbR
        micR.set(ox - orbR * 1.3f, oy - orbR * 1.3f, ox + orbR * 1.3f, oy + orbR * 1.3f)
        when (state) {
            State.LISTENING -> {
                val n = 48
                for (k in 0 until n) {
                    val ang = 2 * PI * k / n
                    val amp = orbR * (0.15f + lvl * 0.55f * (0.5f + 0.5f * sin(t * 9f + k * 0.9f)))
                    stroke.strokeWidth = 3f; stroke.color = cyan(220)
                    c.drawLine(ox + cos(ang).toFloat() * orbR, oy + sin(ang).toFloat() * orbR,
                        ox + cos(ang).toFloat() * (orbR + amp), oy + sin(ang).toFloat() * (orbR + amp), stroke)
                }
            }
            State.THINKING -> {
                val rr = RectF(ox - orbR * 1.15f, oy - orbR * 1.15f, ox + orbR * 1.15f, oy + orbR * 1.15f)
                stroke.strokeWidth = 5f; stroke.color = CYAN
                for (k in 0 until 3) c.drawArc(rr, t * 260f + k * 120f, 60f, false, stroke)
                val r2 = RectF(ox - orbR * 1.35f, oy - orbR * 1.35f, ox + orbR * 1.35f, oy + orbR * 1.35f)
                stroke.strokeWidth = 2f; stroke.color = cyan(150)
                for (k in 0 until 4) c.drawArc(r2, -t * 140f + k * 90f, 40f, false, stroke)
            }
            State.SPEAKING -> {
                for (k in 0 until 3) {
                    val q = ((t * 0.9f + k / 3f) % 1f)
                    stroke.strokeWidth = 3f; stroke.color = cyan((220 * (1 - q)).toInt())
                    c.drawCircle(ox, oy, orbR * (1f + q * 0.5f), stroke)
                }
            }
            else -> {}
        }
        fill.shader = RadialGradient(ox, oy, orbR, intArrayOf(cyan(150), cyan(40), cyan(10)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        fill.color = Color.WHITE; c.drawCircle(ox, oy, orbR, fill); fill.shader = null
        glow.strokeWidth = 12f; glow.color = cyan(150); c.drawCircle(ox, oy, orbR, glow)
        stroke.strokeWidth = 3f; stroke.color = CYAN; c.drawCircle(ox, oy, orbR, stroke)
        // mic glyph
        val mw = orbR * 0.28f; val mh = orbR * 0.62f
        fill.color = if (state == State.LISTENING) Color.WHITE else CYAN
        c.drawRoundRect(ox - mw / 2, oy - mh * 0.75f, ox + mw / 2, oy + mh * 0.2f, mw / 2, mw / 2, fill)
        stroke.strokeWidth = 4f; stroke.color = fill.color
        c.drawArc(RectF(ox - mw, oy - mh * 0.5f, ox + mw, oy + mh * 0.45f), 0f, 180f, false, stroke)
        c.drawLine(ox, oy + mh * 0.45f, ox, oy + mh * 0.65f, stroke)
        label.textAlign = Paint.Align.CENTER; label.textSize = u * 0.5f; label.color = cyan(150); label.letterSpacing = 0.15f
        c.drawText(if (state == State.LISTENING) "話してください" else "タップで話す", ox, oy + orbR * 1.75f, label)
        label.letterSpacing = 0f; label.color = CYAN

        // texts (right)
        val tx = ox + orbR * 1.75f; val tw = (panel.right - u * 0.8f - tx).toInt().coerceAtLeast(10)
        var y = panel.top + hh + u * 0.9f
        if (heard.isNotEmpty()) {
            heardP.textSize = u * 0.68f
            val hl = StaticLayout.Builder.obtain("› $heard", 0, heard.length + 2, heardP, tw).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate(tx, y); hl.draw(c); c.restore()
            y += hl.height + u * 0.5f
        }
        val shown = when {
            reply.isNotEmpty() -> reply
            state == State.THINKING -> "考えています" + ".".repeat(((t * 3).toInt() % 4))
            state == State.LISTENING && heard.isEmpty() -> "ご用件をどうぞ。"
            else -> ""
        }
        if (shown.isNotEmpty()) {
            body.textSize = u * 0.85f
            body.color = if (state == State.ERROR) RED else Color.rgb(215, 248, 255)
            val maxLines = max(2, ((panel.bottom - u * 1.2f - y) / (u * 1.15f)).toInt())
            val bl = StaticLayout.Builder.obtain(shown, 0, shown.length, body, tw).setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            c.save(); c.translate(tx, y); bl.draw(c); c.restore()
        }
        // close hint
        label.textAlign = Paint.Align.RIGHT; label.textSize = u * 0.45f; label.color = cyan(120)
        c.drawText("外側タップで閉じる", panel.right - u * 0.6f, panel.bottom - u * 0.5f, label)
        label.color = CYAN

        if (isShowing) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            when {
                micR.contains(e.x, e.y) -> listener?.onAssistantMic()
                !panel.contains(e.x, e.y) -> listener?.onAssistantClose()
            }
        }
        return true
    }

    private data class Q(val a: Float, val b: Float, val c: Float, val d: Float)
}

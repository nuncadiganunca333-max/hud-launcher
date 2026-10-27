package jp.hud.launcher

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** 通知が届いたときに出る、ジャービス風の確認パネル */
class AlertOverlay(context: Context) : View(context) {

    interface Listener {
        fun onAlertOpen(item: NotifItem, src: android.graphics.Rect?)
        fun onAlertDismiss(item: NotifItem)
    }

    var listener: Listener? = null
    private var item: NotifItem? = null
    private var icon: Drawable? = null
    private var shownAt = 0L
    private var queued = 0
    private val timeoutMs = 20000L

    private val CYAN = Color.rgb(120, 222, 255)
    private val RED = Color.rgb(255, 70, 70)
    private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 120, 222, 255)

    private val panel = RectF()
    private val iconRect = RectF()
    private val openR = RectF()
    private val dismissR = RectF()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS;
        style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 200, 245, 255) }

    init { visibility = GONE }

    val isShowing get() = item != null
    var listening = false
        set(v) { field = v; invalidate() }
    var micLevel = 0f
    fun iconSrc() = Rect(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())

    fun show(n: NotifItem, waiting: Int) {
        item = n; queued = waiting; shownAt = System.currentTimeMillis()
        icon = try { context.packageManager.getApplicationIcon(n.pkg) } catch (e: Exception) { null }
        visibility = VISIBLE
        invalidate()
    }

    fun hide() { item = null; visibility = GONE }

    override fun onDraw(c: Canvas) {
        val n = item ?: return
        val W = width.toFloat(); val H = height.toFloat()
        val age = System.currentTimeMillis() - shownAt
        if (age > timeoutMs) { listener?.onAlertDismiss(n); return }
        val t = age / 1000f
        val zoomMs = 750f
        if (age < zoomMs) {
            val p = age / zoomMs
            fill.color = Color.argb((170 * min(1f, p * 3)).toInt(), 0, 4, 10); c.drawRect(0f, 0f, W, H, fill)
            val u0 = min(W, H)
            val sz = u0 * (0.12f + 0.28f * sin(min(1f, p * 1.4f) * Math.PI.toFloat() / 2))
            val zx = W / 2; val zy = H * 0.42f
            for (k in 0 until 3) {
                val q = ((p * 1.6f) - k * 0.2f).coerceIn(0f, 1f)
                glow.strokeWidth = 12f; glow.color = cyan((200 * (1 - q)).toInt()); c.drawCircle(zx, zy, sz * (0.6f + q * 1.4f), glow)
                stroke.strokeWidth = 3f; stroke.color = cyan((255 * (1 - q)).toInt()); c.drawCircle(zx, zy, sz * (0.6f + q * 1.4f), stroke)
            }
            stroke.strokeWidth = 4f; stroke.color = CYAN
            val rr = RectF(zx - sz * 0.75f, zy - sz * 0.75f, zx + sz * 0.75f, zy + sz * 0.75f)
            c.drawArc(rr, p * 400f, 80f, false, stroke); c.drawArc(rr, p * 400f + 180f, 80f, false, stroke)
            icon?.let { it.setBounds((zx - sz / 2).toInt(), (zy - sz / 2).toInt(), (zx + sz / 2).toInt(), (zy + sz / 2).toInt()); it.draw(c) }
            label.textAlign = Paint.Align.CENTER; label.textSize = u0 / 30f * 1.1f; label.letterSpacing = 0.3f; label.color = CYAN
            c.drawText(n.appLabel.uppercase(), zx, zy + sz * 0.95f, label); label.letterSpacing = 0f
            postInvalidateOnAnimation(); return
        }
        val appear = min(1f, (age - zoomMs) / 300f)

        fill.color = Color.argb((170 * appear).toInt(), 0, 4, 10); c.drawRect(0f, 0f, W, H, fill)

        val u = min(W, H) / 30f
        val pw = min(W * 0.86f, u * 24f); val ph = u * 13.5f
        val cx = W / 2; val cy = H * 0.45f
        val ew = pw * (0.3f + 0.7f * appear)
        panel.set(cx - ew / 2, cy - ph / 2, cx + ew / 2, cy + ph / 2)

        // panel body
        fill.color = Color.argb(225, 2, 22, 38); c.drawRect(panel, fill)
        glow.strokeWidth = 10f; glow.color = cyan(120); c.drawRect(panel, glow)
        stroke.strokeWidth = 2.5f; stroke.color = CYAN; c.drawRect(panel, stroke)
        // brackets
        stroke.strokeWidth = 7f
        val a = u * 1.6f; val o = u * 0.5f
        for ((x, y, dx, dy) in listOf(
            Quad(panel.left - o, panel.top - o, 1f, 1f), Quad(panel.right + o, panel.top - o, -1f, 1f),
            Quad(panel.left - o, panel.bottom + o, 1f, -1f), Quad(panel.right + o, panel.bottom + o, -1f, -1f))) {
            c.drawLine(x, y, x + dx * a, y, stroke); c.drawLine(x, y, x, y + dy * a, stroke)
        }
        if (appear < 1f) { postInvalidateOnAnimation(); return }

        // header
        val hh = u * 1.6f
        val blink = (t * 3).toInt() % 2 == 0
        fill.color = if (blink) cyan(70) else cyan(35)
        c.drawRect(panel.left, panel.top, panel.right, panel.top + hh, fill)
        label.textAlign = Paint.Align.LEFT; label.textSize = hh * 0.5f; label.letterSpacing = 0.25f; label.color = CYAN
        c.drawText("▲ INCOMING TRANSMISSION", panel.left + u * 0.7f, panel.top + hh * 0.68f, label)
        label.letterSpacing = 0f
        if (queued > 0) {
            label.textAlign = Paint.Align.RIGHT; label.color = cyan(200)
            c.drawText("+$queued QUEUED", panel.right - u * 0.7f, panel.top + hh * 0.68f, label)
        }

        // icon with rotating ring
        val ir = u * 1.9f
        val icx = panel.left + u * 0.9f + ir; val icy = panel.top + hh + u * 0.9f + ir
        iconRect.set(icx - ir, icy - ir, icx + ir, icy + ir)
        stroke.strokeWidth = 2f; stroke.color = cyan(160); c.drawCircle(icx, icy, ir, stroke)
        stroke.strokeWidth = 5f; stroke.color = CYAN
        val rr = RectF(icx - ir * 1.18f, icy - ir * 1.18f, icx + ir * 1.18f, icy + ir * 1.18f)
        c.drawArc(rr, t * 120f, 70f, false, stroke); c.drawArc(rr, t * 120f + 180f, 70f, false, stroke)
        icon?.let {
            val s = (ir * 1.25f).toInt()
            it.setBounds((icx - s / 2).toInt(), (icy - s / 2).toInt(), (icx + s / 2).toInt(), (icy + s / 2).toInt())
            it.draw(c)
        }

        // texts
        val tx = icx + ir * 1.5f
        val tw = panel.right - u * 0.8f - tx
        label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.65f; label.color = cyan(170); label.letterSpacing = 0.15f
        c.drawText(n.appLabel.uppercase(), tx, panel.top + hh + u * 1.4f, label)
        label.letterSpacing = 0f; label.textSize = u * 1.0f; label.color = CYAN
        c.drawText(TextUtils.ellipsize(n.title, TextPaint(label), tw, TextUtils.TruncateAt.END).toString(), tx, panel.top + hh + u * 2.7f, label)
        body.textSize = u * 0.72f
        val lay = StaticLayout.Builder.obtain(n.text, 0, n.text.length, body, tw.toInt().coerceAtLeast(10))
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save(); c.translate(tx, panel.top + hh + u * 3.3f); lay.draw(c); c.restore()

        // question
        val qy = panel.bottom - u * 3.6f
        label.textAlign = Paint.Align.CENTER; label.textSize = u * 0.8f; label.letterSpacing = 0.1f
        label.color = cyan((180 + 75 * sin(t * 4f)).toInt())
        c.drawText("アプリを起動しますか？", cx, qy, label)
        if (listening) {
            label.textSize = u * 0.52f; label.color = cyan(200)
            c.drawText("◉ LISTENING ―「はい」で開く ／「いいえ」で閉じる", cx, qy + u * 0.95f, label)
            val n = 20; val bw = u * 0.22f
            for (k in 0 until n) {
                val h = u * (0.1f + micLevel * 0.7f * (0.5f + 0.5f * sin(t * 12f + k)))
                fill.color = cyan(200)
                c.drawRect(cx - n * bw / 2 + k * bw + 2, qy - u * 1.6f - h, cx - n * bw / 2 + (k + 1) * bw - 2, qy - u * 1.6f, fill)
            }
        }
        label.letterSpacing = 0f; label.color = CYAN

        // buttons
        val bw = (panel.width() - u * 3f) / 2; val bh = u * 1.9f; val by = panel.bottom - u * 0.8f - bh
        openR.set(panel.left + u, by, panel.left + u + bw, by + bh)
        dismissR.set(panel.right - u - bw, by, panel.right - u, by + bh)
        button(c, openR, "OPEN", CYAN, true)
        button(c, dismissR, "DISMISS", cyan(200), false)

        // countdown
        val left = 1f - age.toFloat() / timeoutMs
        fill.color = cyan(40); c.drawRect(panel.left, panel.bottom - 6f, panel.right, panel.bottom, fill)
        fill.color = if (left < 0.25f) RED else CYAN
        c.drawRect(panel.left, panel.bottom - 6f, panel.left + panel.width() * left, panel.bottom, fill)
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = u * 0.5f; mono.color = cyan(150)
        c.drawText(String.format("AUTO-DISMISS %02ds", ((timeoutMs - age) / 1000).toInt()), panel.right, panel.bottom + u * 0.9f, mono)

        postInvalidateDelayed(33)
    }

    private fun button(c: Canvas, r: RectF, text: String, col: Int, primary: Boolean) {
        fill.color = if (primary) cyan(70) else cyan(20); c.drawRect(r, fill)
        if (primary) { glow.strokeWidth = 8f; glow.color = cyan(140); c.drawRect(r, glow) }
        stroke.strokeWidth = 2.5f; stroke.color = col; c.drawRect(r, stroke)
        label.textAlign = Paint.Align.CENTER; label.textSize = r.height() * 0.42f; label.letterSpacing = 0.3f; label.color = col
        c.drawText(text, r.centerX(), r.centerY() + label.textSize * 0.36f, label)
        label.letterSpacing = 0f; label.color = CYAN
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val n = item ?: return false
        if (e.action == MotionEvent.ACTION_UP) {
            when {
                openR.contains(e.x, e.y) -> { SoundFx.play(SoundFx.Fx.OPEN); listener?.onAlertOpen(n, Rect(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())) }
                dismissR.contains(e.x, e.y) -> { SoundFx.play(SoundFx.Fx.BACK); listener?.onAlertDismiss(n) }
                !panel.contains(e.x, e.y) -> listener?.onAlertDismiss(n)
            }
        }
        return true
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
}

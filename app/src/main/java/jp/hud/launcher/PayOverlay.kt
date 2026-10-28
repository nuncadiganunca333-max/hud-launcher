package jp.hud.launcher

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.*

/**
 * 支払いモジュール：決済アプリ（PayPayなど）の「支払う」「スキャン」などを
 * ホログラム風の大きなボタンで直接呼び出す。
 */
class PayOverlay(context: Context) : View(context) {

    data class Action(val id: String, val label: String, val icon: Drawable?)

    interface Listener {
        fun onPayAction(action: Action, src: Rect)
        fun onPayClose()
    }

    var listener: Listener? = null
    private var appName = ""
    private var appIcon: Drawable? = null
    private var actions: List<Action> = emptyList()
    private var shownAt = 0L
    private val btns = ArrayList<Pair<Action, RectF>>()
    private val closeR = RectF()
    private var pressed: Action? = null; private var pressedAt = 0L

    private val CYAN = Color.rgb(120, 222, 255)
    private val ACC = Color.rgb(255, 70, 90)          // 決済用のアクセント（赤）
    private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 120, 222, 255)
    private fun acc(a: Int) = Color.argb(a.coerceIn(0, 255), 255, 70, 90)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; blendMode = BlendMode.PLUS; strokeCap = Paint.Cap.ROUND
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0f)))
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val camera = Camera(); private val m = Matrix()
    private val start = System.nanoTime()

    init { visibility = GONE }
    val isShowing get() = visibility == VISIBLE

    fun open(name: String, icon: Drawable?, list: List<Action>) {
        appName = name; appIcon = icon; actions = list
        shownAt = System.currentTimeMillis(); pressed = null
        visibility = VISIBLE; invalidate()
    }
    fun close() { visibility = GONE }

    private fun ease(p: Float) = 1f - (1f - p.coerceIn(0f, 1f)).pow(3)

    override fun onDraw(c: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        val t = (System.nanoTime() - start) / 1e9f
        val age = (System.currentTimeMillis() - shownAt) / 1000f
        val ap = ease(age / 0.45f)
        val u = min(W, H) / 26f
        btns.clear()

        fill.color = Color.argb((215 * ap).toInt(), 0, 4, 10); c.drawRect(0f, 0f, W, H, fill)
        // 走査線
        fill.color = Color.argb(18, 0, 0, 0); var yy = 0f
        while (yy < H) { c.drawRect(0f, yy, W, yy + 2f, fill); yy += 5f }

        val cx = W / 2
        val wide = W / H > 0.75f
        val cardW = min(W * (if (wide) 0.42f else 0.78f), u * 15f); val cardH = cardW * 0.62f
        val cardCy = if (wide) H * 0.36f else H * 0.27f

        // ---- 回転して現れるホログラムのカード
        c.save()
        camera.save()
        camera.rotateY((1 - ap) * 90f + sin(t * 0.9f) * 9f)
        camera.rotateX(cos(t * 0.7f) * 5f)
        camera.getMatrix(m); camera.restore()
        m.preTranslate(-cx, -cardCy); m.postTranslate(cx, cardCy)
        c.concat(m)
        val card = RectF(cx - cardW / 2, cardCy - cardH / 2, cx + cardW / 2, cardCy + cardH / 2)
        val rr = u * 0.9f
        fill.color = Color.WHITE
        fill.shader = LinearGradient(card.left, card.top, card.right, card.bottom,
            intArrayOf(Color.argb(200, 10, 40, 70), Color.argb(200, 30, 10, 30), Color.argb(200, 4, 18, 34)), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(card, rr, rr, fill); fill.shader = null
        // ホログラムの光沢が流れる
        val sx = card.left + card.width() * (((t * 0.35f) % 1.6f) - 0.3f)
        c.save(); c.clipPath(Path().apply { addRoundRect(card, rr, rr, Path.Direction.CW) })
        fill.shader = LinearGradient(sx - u * 3, card.top, sx + u * 3, card.bottom, intArrayOf(Color.TRANSPARENT, Color.argb(70, 220, 240, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(card, fill); fill.shader = null
        // 細かい回路の線
        stroke.strokeWidth = 1f; stroke.color = cyan(40)
        var gx = card.left; while (gx < card.right) { c.drawLine(gx, card.top, gx + card.height() * 0.4f, card.bottom, stroke); gx += u * 0.8f }
        c.restore()
        glow.strokeWidth = 12f; glow.color = acc(200); c.drawRoundRect(card, rr, rr, glow)
        stroke.strokeWidth = 2f; stroke.color = Color.argb(230, 255, 160, 170); c.drawRoundRect(card, rr, rr, stroke)
        // アプリのアイコンと名前
        val ir = cardH * 0.22f
        val icx = card.left + cardH * 0.34f; val icy = card.top + cardH * 0.36f
        stroke.strokeWidth = 2f; stroke.color = cyan(200); c.drawCircle(icx, icy, ir * 1.25f, stroke)
        stroke.strokeWidth = 4f; stroke.color = CYAN
        val ro = RectF(icx - ir * 1.45f, icy - ir * 1.45f, icx + ir * 1.45f, icy + ir * 1.45f)
        c.drawArc(ro, t * 120f, 70f, false, stroke); c.drawArc(ro, t * 120f + 180f, 70f, false, stroke)
        appIcon?.let { it.setBounds((icx - ir).toInt(), (icy - ir).toInt(), (icx + ir).toInt(), (icy + ir).toInt()); it.draw(c) }
        label.textAlign = Paint.Align.LEFT; label.textSize = cardH * 0.13f; label.letterSpacing = 0.15f; label.color = Color.WHITE
        c.drawText(appName, icx + ir * 1.8f, icy - ir * 0.1f, label)
        label.textSize = cardH * 0.07f; label.letterSpacing = 0.35f; label.color = acc(230)
        c.drawText("PAYMENT MODULE", icx + ir * 1.8f, icy + ir * 0.6f, label)
        mono.textAlign = Paint.Align.LEFT; mono.textSize = cardH * 0.075f; mono.color = cyan(200)
        c.drawText("¥  ••••  ••••  ••••   READY", card.left + cardH * 0.12f, card.bottom - cardH * 0.16f, mono)
        val chip = RectF(card.right - cardH * 0.42f, card.bottom - cardH * 0.36f, card.right - cardH * 0.12f, card.bottom - cardH * 0.14f)
        stroke.strokeWidth = 1.5f; stroke.color = Color.argb(200, 255, 200, 120); c.drawRoundRect(chip, 6f, 6f, stroke)
        c.drawLine(chip.left, chip.centerY(), chip.right, chip.centerY(), stroke); c.drawLine(chip.centerX(), chip.top, chip.centerX(), chip.bottom, stroke)
        label.letterSpacing = 0f
        c.restore()

        // ---- アクションボタン
        if (age > 0.25f) {
            val list = actions
            val bw = min(W * (if (wide) 0.5f else 0.86f), u * 18f); val bh = u * 2.6f
            var by = cardCy + cardH / 2 + u * 2f
            label.textAlign = Paint.Align.CENTER; label.textSize = u * 0.5f; label.letterSpacing = 0.4f; label.color = cyan(170)
            c.drawText("SELECT TRANSACTION", cx, by - u * 0.6f, label); label.letterSpacing = 0f
            list.forEachIndexed { i, a ->
                val bp = ease((age - 0.25f - i * 0.07f) / 0.35f)
                if (bp <= 0f) return@forEachIndexed
                val r = RectF(cx - bw / 2, by, cx + bw / 2, by + bh)
                c.save(); c.translate((1 - bp) * u * 4 * (if (i % 2 == 0) -1 else 1), 0f)
                val prim = i == 0
                val hit = pressed == a && System.currentTimeMillis() - pressedAt < 400
                val k = u * 0.6f
                val path = Path().apply {
                    moveTo(r.left + k, r.top); lineTo(r.right, r.top); lineTo(r.right, r.bottom - k)
                    lineTo(r.right - k, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.top + k); close()
                }
                fill.color = if (hit) Color.argb(160, 255, 255, 255) else if (prim) acc(70) else cyan(30)
                c.drawPath(path, fill)
                glow.strokeWidth = 10f; glow.color = if (prim) acc(220) else cyan(150); c.drawPath(path, glow)
                stroke.strokeWidth = 2f; stroke.color = if (prim) Color.argb(255, 255, 150, 160) else CYAN; c.drawPath(path, stroke)
                // アイコン
                val isz = bh * 0.5f
                a.icon?.let { d -> d.setBounds((r.left + bh * 0.3f).toInt(), (r.centerY() - isz / 2).toInt(), (r.left + bh * 0.3f + isz).toInt(), (r.centerY() + isz / 2).toInt()); d.draw(c) }
                label.textAlign = Paint.Align.LEFT; label.textSize = bh * 0.34f; label.color = Color.WHITE
                c.drawText(a.label, r.left + bh * 1.05f, r.centerY() + label.textSize * 0.36f, label)
                label.textAlign = Paint.Align.RIGHT; label.textSize = bh * 0.22f; label.letterSpacing = 0.3f; label.color = if (prim) acc(230) else cyan(180)
                c.drawText(if (prim) "EXECUTE ▸" else "▸", r.right - bh * 0.35f, r.centerY() + label.textSize * 0.36f, label)
                label.letterSpacing = 0f
                c.restore()
                btns += a to r
                by += bh + u * 0.6f
            }
            // 閉じる
            val cw = u * 6f; val ch = u * 1.8f
            closeR.set(cx - cw / 2, by + u * 0.6f, cx + cw / 2, by + u * 0.6f + ch)
            stroke.strokeWidth = 1.5f; stroke.color = cyan(150); c.drawRect(closeR, stroke)
            label.textAlign = Paint.Align.CENTER; label.textSize = ch * 0.4f; label.letterSpacing = 0.3f; label.color = cyan(200)
            c.drawText("CLOSE", closeR.centerX(), closeR.centerY() + label.textSize * 0.36f, label)
            label.letterSpacing = 0f; label.color = CYAN
        }
        if (isShowing) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        for ((a, r) in btns) if (r.contains(e.x, e.y)) {
            pressed = a; pressedAt = System.currentTimeMillis()
            performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            listener?.onPayAction(a, Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
            return true
        }
        if (closeR.contains(e.x, e.y) || btns.isNotEmpty()) listener?.onPayClose()
        return true
    }
}

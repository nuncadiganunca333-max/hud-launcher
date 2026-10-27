package jp.hud.launcher

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.*

/** 車のBluetoothにつながったときの運転用HUD。大きな文字・大きなボタンだけ。 */
class DriveView(context: Context) : View(context) {

    interface Listener {
        fun onDriveAction(action: String)   // talk / nav / phone / music / prev / toggle / next / exit
    }

    var listener: Listener? = null
    var stats = Stats()
    var media: MediaInfo? = null
    var speedKmh = -1f
    var carName = ""
    var assistantName = "ジャービス"
    var lastNotice = ""
        set(v) { field = v; noticeAt = System.currentTimeMillis() }
    private var noticeAt = 0L

    private val CYAN = Color.rgb(120, 222, 255)
    private val RED = Color.rgb(255, 70, 70)
    private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 120, 222, 255)
    private val start = System.nanoTime()
    private var shownAt = 0L
    private var dSpeed = 0f

    private val btns = LinkedHashMap<String, RectF>()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS; style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val monoGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL) }

    init { visibility = GONE }

    val isShowing get() = visibility == VISIBLE
    fun open() { shownAt = System.currentTimeMillis(); visibility = VISIBLE; invalidate() }
    fun close() { visibility = GONE }

    override fun onDraw(c: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        val t = (System.nanoTime() - start) / 1e9f
        val appear = min(1f, (System.currentTimeMillis() - shownAt) / 500f)
        val wide = W / H > 0.75f
        val u = if (wide) min(W, H) / 30f else W / 20f
        btns.clear()

        // background
        fill.color = Color.WHITE
        fill.shader = RadialGradient(W / 2, H * 0.4f, max(W, H) * 0.8f, intArrayOf(Color.rgb(6, 34, 58), Color.rgb(1, 8, 16)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill); fill.shader = null
        // perspective road grid
        val hor = H * (if (wide) 0.38f else 0.32f)
        stroke.strokeWidth = 1.5f
        for (k in -10..10) {
            stroke.color = cyan(40)
            c.drawLine(W / 2 + k * u * 0.4f, hor, W / 2 + k * W * 0.18f, H, stroke)
        }
        for (k in 0 until 12) {
            val q = ((k / 12f) + t * 0.35f) % 1f
            val y = hor + (H - hor) * q * q
            stroke.color = cyan((20 + 60 * q).toInt()); c.drawLine(0f, y, W, y, stroke)
        }

        c.save()
        c.translate(0f, (1 - appear) * u * 3)

        // header
        val top = u * 2.2f
        label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.9f; label.letterSpacing = 0.3f; label.color = CYAN
        c.drawText("◆ DRIVE MODE", u, top, label)
        label.textSize = u * 0.55f; label.color = cyan(170); label.letterSpacing = 0.15f
        if (carName.isNotEmpty()) c.drawText("LINKED: $carName", u, top + u * 0.9f, label)
        label.letterSpacing = 0f
        val exit = RectF(W - u * 5.2f, top - u * 1.1f, W - u, top + u * 0.5f)
        btn(c, exit, "EXIT", false, u); btns["exit"] = exit

        // speed + clock
        val now = Calendar.getInstance()
        val hm = String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val gcx: Float; val gcy: Float; val gr: Float
        if (wide) { gcx = W * 0.3f; gcy = H * 0.4f; gr = min(W * 0.2f, H * 0.26f) }
        else { gcx = W / 2; gcy = top + u * 1.8f + W * 0.3f; gr = W * 0.28f }
        dSpeed += ((if (speedKmh < 0) 0f else speedKmh) - dSpeed) * 0.15f
        val maxS = 140f
        val rr = RectF(gcx - gr, gcy - gr, gcx + gr, gcy + gr)
        stroke.strokeWidth = u * 0.5f; stroke.color = cyan(35); c.drawArc(rr, 135f, 270f, false, stroke)
        val sweep = 270f * (dSpeed / maxS).coerceIn(0f, 1f)
        val sc = if (dSpeed > 100) RED else CYAN
        glow.strokeWidth = u * 0.9f; glow.color = Color.argb(150, Color.red(sc), Color.green(sc), Color.blue(sc)); c.drawArc(rr, 135f, sweep, false, glow)
        stroke.strokeWidth = u * 0.5f; stroke.color = sc; c.drawArc(rr, 135f, sweep, false, stroke)
        for (k in 0..14) {
            val a = Math.toRadians(135.0 + 270.0 * k / 14)
            val r1 = gr + u * 0.55f; val r2 = gr + u * (if (k % 2 == 0) 1.05f else 0.8f)
            stroke.strokeWidth = 2.5f; stroke.color = cyan(150)
            c.drawLine(gcx + cos(a).toFloat() * r1, gcy + sin(a).toFloat() * r1, gcx + cos(a).toFloat() * r2, gcy + sin(a).toFloat() * r2, stroke)
        }
        val sp = if (speedKmh < 0) "--" else dSpeed.roundToInt().toString()
        mono.textAlign = Paint.Align.CENTER; mono.textSize = gr * 0.62f; mono.color = sc
        monoGlow.textAlign = Paint.Align.CENTER; monoGlow.textSize = gr * 0.62f; monoGlow.color = Color.argb(160, Color.red(sc), Color.green(sc), Color.blue(sc))
        c.drawText(sp, gcx, gcy + gr * 0.2f, monoGlow); c.drawText(sp, gcx, gcy + gr * 0.2f, mono)
        label.textAlign = Paint.Align.CENTER; label.textSize = gr * 0.13f; label.color = cyan(180); label.letterSpacing = 0.3f
        c.drawText("km/h", gcx, gcy + gr * 0.45f, label)
        mono.textSize = gr * 0.2f; mono.color = CYAN
        c.drawText(hm, gcx, gcy + gr * 0.85f, mono)
        label.letterSpacing = 0f

        // info column: battery / weather
        val ix = if (wide) W * 0.55f else u
        var iy = if (wide) H * 0.16f else gcy + gr + u * 2.2f
        val iw = if (wide) W * 0.42f else W - u * 2
        val s = stats
        label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.75f
        label.color = if (s.battery <= 20 && !s.charging) RED else CYAN
        c.drawText("PHONE  ${s.battery}%${if (s.charging) " ⚡" else ""}", ix, iy, label)
        label.color = CYAN; label.textAlign = Paint.Align.RIGHT
        c.drawText("${s.temp?.roundToInt() ?: "--"}°C  ${s.weather}", ix + iw, iy, label)
        iy += u * 1.2f

        // media card
        val m = media
        val mh = u * 4.6f
        val card = RectF(ix, iy, ix + iw, iy + mh)
        fill.color = Color.argb(170, 0, 22, 38); c.drawRect(card, fill)
        stroke.strokeWidth = 2f; stroke.color = cyan(150); c.drawRect(card, stroke)
        if (m != null) {
            val art = RectF(card.left + u * 0.3f, card.top + u * 0.3f, card.left + u * 0.3f + mh - u * 0.6f, card.bottom - u * 0.3f)
            if (m.art != null) c.drawBitmap(m.art, null, art, Paint(Paint.FILTER_BITMAP_FLAG))
            else { fill.color = cyan(40); c.drawRect(art, fill) }
            val tx = art.right + u * 0.5f; val tw = card.right - u * 0.4f - tx
            label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.95f; label.color = CYAN
            c.drawText(TextUtils.ellipsize(m.title, TextPaint(label), tw, TextUtils.TruncateAt.END).toString(), tx, card.top + u * 1.4f, label)
            label.textSize = u * 0.65f; label.color = cyan(180)
            c.drawText(TextUtils.ellipsize(m.artist, TextPaint(label), tw, TextUtils.TruncateAt.END).toString(), tx, card.top + u * 2.3f, label)
            label.color = CYAN
            val bw = tw / 3 - u * 0.2f; val by = card.bottom - u * 1.85f
            listOf("prev" to "⏮", "toggle" to (if (m.playing) "❚❚" else "▶"), "next" to "⏭").forEachIndexed { i, (k, sym) ->
                val r = RectF(tx + i * (bw + u * 0.3f), by, tx + i * (bw + u * 0.3f) + bw, card.bottom - u * 0.3f)
                btn(c, r, sym, k == "toggle", u); btns[k] = r
            }
        } else {
            label.textAlign = Paint.Align.CENTER; label.textSize = u * 0.7f; label.color = cyan(120)
            c.drawText("NO AUDIO  ―  MUSICで音楽アプリを開く", card.centerX(), card.centerY() + u * 0.25f, label); label.color = CYAN
        }
        iy = card.bottom + u * 0.6f

        // last spoken notice
        if (lastNotice.isNotEmpty() && System.currentTimeMillis() - noticeAt < 20000) {
            label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.6f; label.color = cyan(200)
            c.drawText(TextUtils.ellipsize("▲ $lastNotice", TextPaint(label), iw, TextUtils.TruncateAt.END).toString(), ix, iy + u * 0.4f, label)
            label.color = CYAN
        }

        // big action buttons
        val bh = u * 3.0f
        val by2 = H - u * 1.2f - bh
        val acts = listOf("talk" to "話しかける", "nav" to "ナビ", "phone" to "電話", "music" to "MUSIC")
        val bw2 = (W - u * 2 - u * 0.5f * 3) / 4
        acts.forEachIndexed { i, (k, txt) ->
            val r = RectF(u + i * (bw2 + u * 0.5f), by2, u + i * (bw2 + u * 0.5f) + bw2, by2 + bh)
            btn(c, r, if (k == "talk") "◉ $txt" else txt, k == "talk", u * 1.2f); btns[k] = r
        }
        label.textAlign = Paint.Align.CENTER; label.textSize = u * 0.5f; label.color = cyan(140); label.letterSpacing = 0.2f
        c.drawText("「$assistantName」と呼びかけても話しかけられます", W / 2, by2 - u * 0.5f, label)
        label.letterSpacing = 0f; label.color = CYAN
        c.restore()

        if (isShowing) postInvalidateDelayed(if (appear < 1f) 16 else 50)
    }

    private fun btn(c: Canvas, r: RectF, text: String, primary: Boolean, u: Float) {
        fill.color = if (primary) cyan(70) else cyan(22); c.drawRect(r, fill)
        if (primary) { glow.strokeWidth = 10f; glow.color = cyan(150); c.drawRect(r, glow) }
        stroke.strokeWidth = 2.5f; stroke.color = if (primary) CYAN else cyan(170); c.drawRect(r, stroke)
        label.textAlign = Paint.Align.CENTER; label.textSize = min(r.height() * 0.38f, u * 0.9f); label.color = CYAN
        c.drawText(text, r.centerX(), r.centerY() + label.textSize * 0.36f, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            for ((k, r) in btns) if (r.contains(e.x, e.y)) { SoundFx.play(SoundFx.Fx.TAP); listener?.onDriveAction(k); break }
        }
        return true
    }
}

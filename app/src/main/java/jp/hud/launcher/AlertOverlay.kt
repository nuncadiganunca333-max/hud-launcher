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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/** 通知の種類。種類ごとに色・見出し・効果音が変わる */
enum class NotifKind(val tag: String, val r: Int, val g: Int, val b: Int) {
    MESSAGE("INCOMING MESSAGE", 120, 222, 255),
    MAIL("MAIL RECEIVED", 255, 205, 110),
    SCHEDULE("SCHEDULE ALERT", 130, 255, 190),
    CALL("PRIORITY SIGNAL", 255, 95, 95),
    SYSTEM("SYSTEM NOTICE", 175, 185, 255),
    OTHER("INCOMING TRANSMISSION", 120, 222, 255);

    val color get() = Color.rgb(r, g, b)

    companion object {
        private val CHAT = listOf("line", "slack", "whatsapp", "messag", "telegram", "discord", "teams", "chatwork", "mms", "sms", "signal")
        fun of(n: NotifItem): NotifKind {
            val cat = n.category ?: ""
            val p = n.pkg.lowercase()
            return when {
                cat == "call" || cat == "alarm" || p.contains("dialer") || p.contains("incallui") -> CALL
                cat == "email" || p.endsWith(".gm") || p.contains("mail") || p.contains("outlook") -> MAIL
                cat == "event" || cat == "reminder" || p.contains("calendar") -> SCHEDULE
                cat == "msg" || CHAT.any { p.contains(it) } -> MESSAGE
                cat == "sys" || cat == "err" || cat == "status" || p.startsWith("com.android.") || p.startsWith("com.samsung.") || p.startsWith("com.sec.") -> SYSTEM
                else -> OTHER
            }
        }
    }
}

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
    private var kind = NotifKind.OTHER
    private var variant = 0
    private var seq = 0

    // 閉じるときの余韻
    private var ghostAt = 0L
    private var ghostOpened = false
    private val ghostPanel = RectF()
    private var ghostKind = NotifKind.OTHER

    private var accR = 120; private var accG = 222; private var accB = 255
    private val RED = Color.rgb(255, 70, 70)
    private fun acc(a: Int) = Color.argb(a.coerceIn(0, 255), accR, accG, accB)
    private val ACC get() = Color.rgb(accR, accG, accB)

    private val panel = RectF()
    private val iconRect = RectF()
    private val openR = RectF()
    private val dismissR = RectF()
    private val tmp = RectF()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS;
        style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 220, 245, 255) }
    private val ellip = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val SCRAMBLE = "アイウエオカキクケコサシスセソ0123456789#%&<>/=+"

    private val INTROS = 8

    init { visibility = GONE }

    val isShowing get() = item != null
    var listening = false
        set(v) { field = v; invalidate() }
    var micLevel = 0f
    fun iconSrc() = Rect(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())

    private fun setKind(k: NotifKind) { kind = k; accR = k.r; accG = k.g; accB = k.b }

    fun show(n: NotifItem, waiting: Int) {
        val same = item?.key == n.key
        item = n; queued = waiting
        if (!same) {
            shownAt = System.currentTimeMillis()
            variant = seq++ % INTROS
            icon = try { context.packageManager.getApplicationIcon(n.pkg) } catch (e: Exception) { null }
        }
        setKind(NotifKind.of(n))
        ghostAt = 0L
        visibility = VISIBLE
        invalidate()
    }

    fun hide(opened: Boolean = false) {
        if (item != null && !panel.isEmpty) {
            ghostAt = System.currentTimeMillis(); ghostOpened = opened; ghostPanel.set(panel); ghostKind = kind
            item = null
            invalidate()
        } else { item = null; ghostAt = 0L; visibility = GONE }
    }

    private fun ease(p: Float) = 1f - (1f - p.coerceIn(0f, 1f)).let { it * it * it }

    private fun brackets(c: Canvas, r: RectF, o: Float, a: Float, w: Float, col: Int) {
        stroke.strokeWidth = w; stroke.color = col
        val l = r.left - o; val t = r.top - o; val rr = r.right + o; val b = r.bottom + o
        c.drawLine(l, t, l + a, t, stroke); c.drawLine(l, t, l, t + a, stroke)
        c.drawLine(rr, t, rr - a, t, stroke); c.drawLine(rr, t, rr, t + a, stroke)
        c.drawLine(l, b, l + a, b, stroke); c.drawLine(l, b, l, b - a, stroke)
        c.drawLine(rr, b, rr - a, b, stroke); c.drawLine(rr, b, rr, b - a, stroke)
    }

    private fun drawIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        icon?.let { it.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt()); it.draw(c) }
    }

    // ---------------- 登場の演出（4種類を順番に） ----------------
    private fun drawIntro(c: Canvas, n: NotifItem, p: Float, W: Float, H: Float) {
        val u0 = min(W, H)
        val zx = W / 2; val zy = H * 0.42f
        fill.color = Color.argb((175 * min(1f, p * 3)).toInt(), 0, 4, 10); c.drawRect(0f, 0f, W, H, fill)
        val sz = u0 * (0.12f + 0.28f * sin(min(1f, p * 1.4f) * PI.toFloat() / 2))
        when (variant) {
            0 -> { // 波紋：光の輪が広がる
                for (k in 0 until 3) {
                    val q = ((p * 1.6f) - k * 0.2f).coerceIn(0f, 1f)
                    glow.strokeWidth = 12f; glow.color = acc((200 * (1 - q)).toInt()); c.drawCircle(zx, zy, sz * (0.6f + q * 1.4f), glow)
                    stroke.strokeWidth = 3f; stroke.color = acc((255 * (1 - q)).toInt()); c.drawCircle(zx, zy, sz * (0.6f + q * 1.4f), stroke)
                }
                stroke.strokeWidth = 4f; stroke.color = ACC
                tmp.set(zx - sz * 0.75f, zy - sz * 0.75f, zx + sz * 0.75f, zy + sz * 0.75f)
                c.drawArc(tmp, p * 400f, 80f, false, stroke); c.drawArc(tmp, p * 400f + 180f, 80f, false, stroke)
                drawIcon(c, zx, zy, sz)
            }
            1 -> { // ロックオン：画面の四隅から照準が締まる
                val e = ease(p * 1.25f)
                val hw = W / 2 * (1 - e) + sz * 0.8f * e; val hh = H / 2 * (1 - e) + sz * 0.8f * e
                val ccx = W / 2; val ccy = H / 2 + (zy - H / 2) * e
                tmp.set(ccx - hw, ccy - hh, ccx + hw, ccy + hh)
                brackets(c, tmp, 0f, u0 * 0.06f, 6f, ACC)
                glow.strokeWidth = 10f; glow.color = acc(120); c.drawRect(tmp, glow)
                // 十字線と座標
                stroke.strokeWidth = 1.5f; stroke.color = acc((160 * e).toInt())
                c.drawLine(0f, ccy, W, ccy, stroke); c.drawLine(ccx, 0f, ccx, H, stroke)
                mono.textAlign = Paint.Align.LEFT; mono.textSize = u0 / 46f; mono.color = acc(200)
                c.drawText(String.format("TRK %04d : %04d", (hw * 3).toInt(), (hh * 3).toInt()), tmp.left, tmp.top - mono.textSize * 0.6f, mono)
                if (e > 0.55f) {
                    c.saveLayerAlpha(null, (255 * ((e - 0.55f) / 0.45f)).toInt()); drawIcon(c, zx, zy, sz); c.restore()
                }
                if (p > 0.8f && ((p * 40).toInt() % 2 == 0)) {
                    label.textAlign = Paint.Align.CENTER; label.textSize = u0 / 34f; label.letterSpacing = 0.3f; label.color = Color.WHITE
                    c.drawText("TARGET LOCKED", zx, zy - sz * 1.05f, label); label.letterSpacing = 0f
                }
            }
            2 -> { // 実体化：走査線がアイコンを上から描き出す
                val e = ease(p * 1.2f)
                val top = zy - sz / 2; val line = top + sz * e
                c.save(); c.clipRect(zx - sz, top, zx + sz, line); drawIcon(c, zx, zy, sz); c.restore()
                // まだ描かれていない部分は、ずれた横スライスのノイズ
                val rnd = Random((p * 30).toInt())
                for (k in 0 until 7) {
                    val y = line + rnd.nextFloat() * (zy + sz / 2 - line)
                    val dx = (rnd.nextFloat() - 0.5f) * sz * 0.5f
                    fill.color = acc(40 + rnd.nextInt(90)); c.drawRect(zx - sz / 2 + dx, y, zx + sz / 2 + dx, y + 3f, fill)
                }
                glow.strokeWidth = 10f; glow.color = acc(220); c.drawLine(zx - sz * 0.9f, line, zx + sz * 0.9f, line, glow)
                stroke.strokeWidth = 3f; stroke.color = Color.WHITE; c.drawLine(zx - sz * 0.9f, line, zx + sz * 0.9f, line, stroke)
                tmp.set(zx - sz * 0.7f, zy - sz * 0.7f, zx + sz * 0.7f, zy + sz * 0.7f)
                brackets(c, tmp, 0f, sz * 0.25f, 4f, acc(200))
                mono.textAlign = Paint.Align.LEFT; mono.textSize = u0 / 50f; mono.color = acc(170)
                for (k in 0 until 6) {
                    val hx = Integer.toHexString(Random(k * 31 + (p * 20).toInt()).nextInt(0x10000, 0xFFFFF)).uppercase()
                    c.drawText("0x$hx", zx + sz * 0.85f, zy - sz * 0.5f + k * mono.textSize * 1.4f, mono)
                }
                mono.textAlign = Paint.Align.RIGHT
                c.drawText(String.format("RENDER %3d%%", (e * 100).toInt()), zx - sz * 0.85f, zy + sz * 0.5f, mono)
            }
            3 -> { // レーダー：回る光が発信元を捉える
                val rr = sz * 1.25f
                stroke.strokeWidth = 1.5f; stroke.color = acc(110)
                for (k in 1..3) c.drawCircle(zx, zy, rr * k / 3f, stroke)
                c.drawLine(zx - rr, zy, zx + rr, zy, stroke); c.drawLine(zx, zy - rr, zx, zy + rr, stroke)
                val ang = p * 540f - 90f
                fill.shader = SweepGradient(zx, zy, intArrayOf(Color.TRANSPARENT, acc(150)), floatArrayOf(0.8f, 1f))
                c.save(); c.rotate(ang, zx, zy); c.drawCircle(zx, zy, rr, fill); c.restore(); fill.shader = null
                stroke.strokeWidth = 3f; stroke.color = Color.WHITE
                c.drawLine(zx, zy, zx + rr * cos(Math.toRadians(ang.toDouble())).toFloat(), zy + rr * sin(Math.toRadians(ang.toDouble())).toFloat(), stroke)
                if (p > 0.35f) {
                    val q = ((p - 0.35f) / 0.65f)
                    c.saveLayerAlpha(null, (255 * min(1f, q * 2)).toInt()); drawIcon(c, zx, zy, sz * 0.8f); c.restore()
                    glow.strokeWidth = 8f; glow.color = acc((220 * (1 - q)).toInt()); c.drawCircle(zx, zy, sz * (0.5f + q), glow)
                }
                mono.textAlign = Paint.Align.CENTER; mono.textSize = u0 / 46f; mono.color = acc(200)
                c.drawText("SIGNAL SOURCE ACQUIRED", zx, zy - rr - mono.textSize, mono)
            }
            4 -> { // グリッチ：横に裂けた映像が、ずれながら揃っていく
                val e = ease(p * 1.3f)
                val slices = 14
                val rnd = Random((p * 40).toInt())
                val top = zy - sz / 2
                for (k in 0 until slices) {
                    val y0 = top + sz * k / slices; val y1 = top + sz * (k + 1) / slices
                    val off = (rnd.nextFloat() - 0.5f) * sz * 0.9f * (1f - e)
                    c.save(); c.clipRect(zx - sz, y0, zx + sz, y1)
                    // 赤と青にずれた残像
                    c.saveLayerAlpha(null, (110 * (1f - e)).toInt()); c.translate(off - sz * 0.06f, 0f); drawIcon(c, zx, zy, sz); c.restore()
                    c.translate(off, 0f); drawIcon(c, zx, zy, sz)
                    c.restore()
                }
                for (k in 0 until 5) {
                    val y = top + rnd.nextFloat() * sz
                    fill.color = if (k % 2 == 0) Color.argb((160 * (1f - e)).toInt(), 255, 60, 90) else acc((180 * (1f - e)).toInt())
                    c.drawRect(zx - sz * (0.6f + rnd.nextFloat()), y, zx + sz * (0.6f + rnd.nextFloat()), y + 2f + rnd.nextFloat() * 6f, fill)
                }
                tmp.set(zx - sz * 0.7f, zy - sz * 0.7f, zx + sz * 0.7f, zy + sz * 0.7f)
                brackets(c, tmp, 0f, sz * 0.22f, 4f, acc((255 * e).toInt()))
                mono.textAlign = Paint.Align.CENTER; mono.textSize = u0 / 44f
                mono.color = if (e < 0.9f) Color.argb(220, 255, 90, 110) else ACC
                c.drawText(if (e < 0.9f) "SIGNAL CORRUPTED… RECOVERING" else "SIGNAL RESTORED", zx, zy - sz * 0.95f, mono)
            }
            5 -> { // ワープ：光の線が中心へ吸い込まれ、閃光とともに現れる
                val rnd = Random(77)
                val reach = hypot(W, H) * 0.6f
                for (k in 0 until 60) {
                    val ang = rnd.nextFloat() * 2 * PI.toFloat()
                    val sp = 0.6f + rnd.nextFloat() * 0.8f
                    val q = ((p * 1.4f * sp) + rnd.nextFloat() * 0.3f) % 1f
                    val d1 = reach * (1f - q); val d0 = d1 + reach * 0.12f * (0.3f + q)
                    stroke.strokeWidth = 1.5f + q * 2.5f; stroke.color = acc((60 + 190 * q).toInt())
                    c.drawLine(zx + cos(ang) * d0, zy + sin(ang) * d0, zx + cos(ang) * d1, zy + sin(ang) * d1, stroke)
                }
                if (p > 0.55f) {
                    val q = (p - 0.55f) / 0.45f
                    fill.color = Color.argb((230 * (1f - q)).toInt(), 255, 255, 255); c.drawCircle(zx, zy, sz * (0.2f + q * 1.6f), fill)
                    c.saveLayerAlpha(null, (255 * min(1f, q * 2)).toInt())
                    c.save(); c.scale(0.6f + 0.4f * ease(q), 0.6f + 0.4f * ease(q), zx, zy); drawIcon(c, zx, zy, sz); c.restore()
                    c.restore()
                }
                mono.textAlign = Paint.Align.CENTER; mono.textSize = u0 / 46f; mono.color = acc(210)
                c.drawText(String.format("INBOUND  %04d m/s", (9999 * (1f - p)).toInt()), zx, zy - sz * 1.0f, mono)
            }
            6 -> { // ハニカム：六角形のタイルが外から集まって組み上がる
                val e = ease(p * 1.25f)
                val hexR = sz * 0.2f
                val dxh = hexR * 1.732f
                for (row in -3..3) for (colI in -3..3) {
                    val hx = zx + colI * dxh + (if (row % 2 != 0) dxh / 2 else 0f)
                    val hy = zy + row * hexR * 1.5f
                    val dist = hypot(hx - zx, hy - zy)
                    if (dist > sz * 0.95f) continue
                    val delay = dist / (sz * 0.95f) * 0.5f
                    val q = ((e - delay) / 0.5f).coerceIn(0f, 1f)
                    if (q <= 0f) continue
                    val fly = (1f - ease(q)) * sz * 1.4f
                    val ang = atan2(hy - zy, hx - zx)
                    val px = hx + cos(ang) * fly; val py = hy + sin(ang) * fly
                    val hp = Path()
                    for (v in 0..6) {
                        val a = Math.toRadians((30 + v * 60).toDouble())
                        val x = px + hexR * 0.92f * cos(a).toFloat(); val y = py + hexR * 0.92f * sin(a).toFloat()
                        if (v == 0) hp.moveTo(x, y) else hp.lineTo(x, y)
                    }
                    hp.close()
                    fill.color = acc((40 * q).toInt()); c.drawPath(hp, fill)
                    stroke.strokeWidth = 2f; stroke.color = acc((220 * q).toInt()); c.drawPath(hp, stroke)
                }
                if (e > 0.7f) {
                    val q = (e - 0.7f) / 0.3f
                    c.saveLayerAlpha(null, (255 * q).toInt()); drawIcon(c, zx, zy, sz * 0.75f); c.restore()
                }
                mono.textAlign = Paint.Align.CENTER; mono.textSize = u0 / 46f; mono.color = acc(210)
                c.drawText(String.format("ASSEMBLING  %3d%%", (e * 100).toInt()), zx, zy - sz * 1.05f, mono)
            }
            else -> { // ホログラム投影：下から光の円錐が伸びて、映像が浮かび上がる
                val e = ease(p * 1.3f)
                val baseY = H * 0.86f
                val topY = zy - sz * 0.6f
                val beamTop = baseY + (topY - baseY) * e
                fill.shader = LinearGradient(0f, baseY, 0f, beamTop, acc(150), acc(10), Shader.TileMode.CLAMP)
                val cone = Path().apply {
                    moveTo(zx - sz * 0.08f, baseY); lineTo(zx - sz * 0.75f, beamTop); lineTo(zx + sz * 0.75f, beamTop); lineTo(zx + sz * 0.08f, baseY); close()
                }
                c.drawPath(cone, fill); fill.shader = null
                // 投影機
                tmp.set(zx - sz * 0.5f, baseY - sz * 0.06f, zx + sz * 0.5f, baseY + sz * 0.06f)
                glow.strokeWidth = 10f; glow.color = acc(200); c.drawOval(tmp, glow)
                stroke.strokeWidth = 2.5f; stroke.color = ACC; c.drawOval(tmp, stroke)
                if (e > 0.5f) {
                    val q = (e - 0.5f) / 0.5f
                    val flick = if ((p * 60).toInt() % 7 == 0) 0.5f else 1f
                    c.saveLayerAlpha(null, (230 * q * flick).toInt()); drawIcon(c, zx, zy, sz); c.restore()
                    // ホログラムの走査線
                    c.save(); c.clipRect(zx - sz / 2, zy - sz / 2, zx + sz / 2, zy + sz / 2)
                    var y = zy - sz / 2 + ((p * 300) % 6f)
                    stroke.strokeWidth = 1f; stroke.color = acc((90 * q).toInt())
                    while (y < zy + sz / 2) { c.drawLine(zx - sz / 2, y, zx + sz / 2, y, stroke); y += 6f }
                    c.restore()
                }
                mono.textAlign = Paint.Align.CENTER; mono.textSize = u0 / 46f; mono.color = acc(210)
                c.drawText("HOLOGRAPHIC PROJECTION", zx, zy - sz * 1.0f, mono)
            }
        }
        label.textAlign = Paint.Align.CENTER; label.textSize = u0 / 30f * 1.1f; label.letterSpacing = 0.3f; label.color = ACC
        c.drawText(n.appLabel.uppercase(), zx, zy + sz * 1.0f + label.textSize, label); label.letterSpacing = 0f
    }

    // ---------------- 閉じるときの余韻 ----------------
    private fun drawGhost(c: Canvas): Boolean {
        val age = System.currentTimeMillis() - ghostAt
        val dur = if (ghostOpened) 320f else 260f
        if (ghostAt == 0L || age > dur) return false
        val p = age / dur
        val k = ghostKind
        fun col(a: Int) = Color.argb(a.coerceIn(0, 255), k.r, k.g, k.b)
        val r = ghostPanel
        fill.color = Color.argb((170 * (1 - p)).toInt(), 0, 4, 10); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (ghostOpened) {
            // 開く：パネルが白く光って外へ弾ける
            val g = 1f + p * 0.25f
            tmp.set(r.centerX() - r.width() / 2 * g, r.centerY() - r.height() / 2 * g, r.centerX() + r.width() / 2 * g, r.centerY() + r.height() / 2 * g)
            fill.color = Color.argb((120 * (1 - p)).toInt(), 255, 255, 255); c.drawRect(tmp, fill)
            glow.strokeWidth = 18f; glow.color = col((255 * (1 - p)).toInt()); c.drawRect(tmp, glow)
            stroke.strokeWidth = 3f; stroke.color = col((255 * (1 - p)).toInt()); c.drawRect(tmp, stroke)
        } else {
            // 閉じる：横一本の線に潰れて消える
            val e = ease(p)
            val h = r.height() * (1 - e)
            tmp.set(r.left, r.centerY() - h / 2, r.right, r.centerY() + h / 2)
            fill.color = Color.argb((200 * (1 - p)).toInt(), 2, 22, 38); c.drawRect(tmp, fill)
            stroke.strokeWidth = 2.5f; stroke.color = col((255 * (1 - p * 0.5f)).toInt()); c.drawRect(tmp, stroke)
            val w = r.width() * (1 - ease((p - 0.6f) / 0.4f))
            glow.strokeWidth = 12f; glow.color = col((255 * (1 - p)).toInt())
            c.drawLine(r.centerX() - w / 2, r.centerY(), r.centerX() + w / 2, r.centerY(), glow)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val n = item
        if (n == null) {
            if (drawGhost(c)) postInvalidateOnAnimation() else { ghostAt = 0L; visibility = GONE }
            return
        }
        val W = width.toFloat(); val H = height.toFloat()
        val age = System.currentTimeMillis() - shownAt
        if (age > timeoutMs) { listener?.onAlertDismiss(n); return }
        val t = age / 1000f
        val zoomMs = 850f
        if (age < zoomMs) { drawIntro(c, n, age / zoomMs, W, H); postInvalidateOnAnimation(); return }
        val appear = min(1f, (age - zoomMs) / 300f)
        val urgent = kind == NotifKind.CALL

        fill.color = Color.argb((175 * appear).toInt(), 0, 4, 10); c.drawRect(0f, 0f, W, H, fill)
        if (urgent) {
            // 緊急：画面のふちが赤く脈打つ
            glow.strokeWidth = 40f; glow.color = acc((90 + 70 * sin(t * 7f)).toInt()); c.drawRect(0f, 0f, W, H, glow)
        }

        val u = min(W, H) / 30f
        val pw = min(W * 0.86f, u * 24f); val ph = u * 13.5f
        val cx = W / 2; val cy = H * 0.45f
        val ew = pw * (0.3f + 0.7f * ease(appear))
        panel.set(cx - ew / 2, cy - ph / 2, cx + ew / 2, cy + ph / 2)

        // 待っている通知があるときは、後ろに重なったパネルの影
        if (appear >= 1f) for (k in min(queued, 3) downTo 1) {
            val o = u * 0.55f * k
            tmp.set(panel.left + o, panel.top - o, panel.right + o, panel.bottom - o)
            fill.color = Color.argb(120 - k * 25, 2, 22, 38); c.drawRect(tmp, fill)
            stroke.strokeWidth = 1.5f; stroke.color = acc(130 - k * 30); c.drawRect(tmp, stroke)
        }

        // panel body
        fill.color = Color.argb(228, 2, 22, 38); c.drawRect(panel, fill)
        glow.strokeWidth = 10f; glow.color = acc(120); c.drawRect(panel, glow)
        stroke.strokeWidth = 2.5f; stroke.color = ACC; c.drawRect(panel, stroke)
        // 呼吸するブラケット
        brackets(c, panel, u * (0.5f + 0.12f * sin(t * 3f)), u * 1.6f, 7f, ACC)
        if (appear < 1f) {
            // 開く瞬間は、中を白い光が横切る
            val x = panel.left + panel.width() * appear
            glow.strokeWidth = 16f; glow.color = Color.argb(200, 255, 255, 255); c.drawLine(x, panel.top, x, panel.bottom, glow)
            postInvalidateOnAnimation(); return
        }
        val ct = (age - zoomMs - 300f) / 1000f   // パネルが開ききってからの秒数

        // 背景の細いグリッドと、ゆっくり降りる走査線
        c.save(); c.clipRect(panel)
        stroke.strokeWidth = 1f; stroke.color = acc(16)
        var gx = panel.left + u; while (gx < panel.right) { c.drawLine(gx, panel.top, gx, panel.bottom, stroke); gx += u }
        val sy = panel.top + ((ct * 0.35f) % 1f) * panel.height()
        fill.shader = LinearGradient(0f, sy - u * 1.6f, 0f, sy, Color.TRANSPARENT, acc(34), Shader.TileMode.CLAMP)
        c.drawRect(panel.left, sy - u * 1.6f, panel.right, sy, fill); fill.shader = null
        c.restore()

        // header
        val hh = u * 1.6f
        val blink = (t * 3).toInt() % 2 == 0
        fill.color = if (blink) acc(70) else acc(35)
        c.drawRect(panel.left, panel.top, panel.right, panel.top + hh, fill)
        if (urgent) {
            // 緊急：見出しに斜めの警告ストライプ
            c.save(); c.clipRect(panel.left, panel.top, panel.right, panel.top + hh)
            fill.color = acc(60)
            var sx = panel.left - hh + ((t * u * 2f) % (hh * 1.2f))
            while (sx < panel.right) {
                val pth = Path().apply { moveTo(sx, panel.top + hh); lineTo(sx + hh * 0.6f, panel.top); lineTo(sx + hh * 1.0f, panel.top); lineTo(sx + hh * 0.4f, panel.top + hh); close() }
                c.drawPath(pth, fill); sx += hh * 1.2f
            }
            c.restore()
        }
        label.textAlign = Paint.Align.LEFT; label.textSize = hh * 0.5f; label.letterSpacing = 0.25f; label.color = ACC
        val head = "▲ " + kind.tag
        c.drawText(head, panel.left + u * 0.7f, panel.top + hh * 0.68f, label)
        val headW = label.measureText(head)
        label.letterSpacing = 0f
        var rightEdge = panel.right - u * 0.7f
        if (queued > 0) {
            label.textAlign = Paint.Align.RIGHT; label.color = acc(220)
            c.drawText("+$queued QUEUED", rightEdge, panel.top + hh * 0.68f, label)
            rightEdge -= label.measureText("+$queued QUEUED") + u * 0.6f
        }
        // 見出しの右：信号の波形
        val wx0 = panel.left + u * 1.4f + headW; val wn = ((rightEdge - wx0) / (u * 0.22f)).toInt().coerceIn(0, 40)
        fill.color = acc(190)
        for (k in 0 until wn) {
            val h = hh * (0.12f + 0.5f * (0.5f + 0.5f * sin(t * 9f + k * 0.9f)) * (0.5f + 0.5f * sin(t * 2.3f + k * 0.31f)))
            val x = rightEdge - (k + 1) * u * 0.22f
            c.drawRect(x, panel.top + hh / 2 - h / 2, x + u * 0.11f, panel.top + hh / 2 + h / 2, fill)
        }

        // icon with rotating ring
        val ir = u * 1.9f
        val icx = panel.left + u * 0.9f + ir; val icy = panel.top + hh + u * 0.9f + ir
        iconRect.set(icx - ir, icy - ir, icx + ir, icy + ir)
        stroke.strokeWidth = 2f; stroke.color = acc(160); c.drawCircle(icx, icy, ir, stroke)
        stroke.strokeWidth = 5f; stroke.color = ACC
        tmp.set(icx - ir * 1.18f, icy - ir * 1.18f, icx + ir * 1.18f, icy + ir * 1.18f)
        c.drawArc(tmp, t * 120f, 70f, false, stroke); c.drawArc(tmp, t * 120f + 180f, 70f, false, stroke)
        // 逆回りの細い輪と、周回する光点
        stroke.strokeWidth = 1.5f; stroke.color = acc(140)
        tmp.set(icx - ir * 1.34f, icy - ir * 1.34f, icx + ir * 1.34f, icy + ir * 1.34f)
        for (k in 0 until 6) c.drawArc(tmp, -t * 70f + k * 60f, 22f, false, stroke)
        val oa = t * 2.6f
        fill.color = Color.WHITE; c.drawCircle(icx + cos(oa) * ir * 1.34f, icy + sin(oa) * ir * 1.34f, u * 0.13f, fill)
        // 開いた直後は、アイコンから輪がひとつ広がる
        if (ct < 0.7f) { val q = ct / 0.7f; glow.strokeWidth = 8f; glow.color = acc((230 * (1 - q)).toInt()); c.drawCircle(icx, icy, ir * (1f + q * 1.6f), glow) }
        drawIcon(c, icx, icy, ir * 1.25f)

        // texts
        val tx = icx + ir * 1.6f
        val tw = panel.right - u * 0.8f - tx
        label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.65f; label.color = acc(180); label.letterSpacing = 0.15f
        c.drawText(n.appLabel.uppercase(), tx, panel.top + hh + u * 1.4f, label)
        label.letterSpacing = 0f; label.textSize = u * 1.0f; label.color = Color.argb(255, 235, 250, 255)
        // 送り主：暗号が解けるように1文字ずつ確定する
        ellip.set(label)
        val full = TextUtils.ellipsize(n.title, ellip, tw, TextUtils.TruncateAt.END).toString()
        val solved = ((ct / 0.55f) * full.length).toInt().coerceIn(0, full.length)
        val title = if (solved >= full.length) full else {
            val rnd = Random((ct * 25).toInt())
            val sb = StringBuilder(full.substring(0, solved))
            for (k in solved until full.length) sb.append(if (full[k] == ' ') ' ' else SCRAMBLE[rnd.nextInt(SCRAMBLE.length)])
            ellip.set(label); TextUtils.ellipsize(sb, ellip, tw, TextUtils.TruncateAt.END).toString()
        }
        c.drawText(title, tx, panel.top + hh + u * 2.7f, label)
        // 本文：タイプされるように現れる
        body.textSize = u * 0.72f
        val typed = ((ct - 0.35f) / 0.9f).coerceIn(0f, 1f)
        val shown = n.text.take((n.text.length.coerceAtMost(140) * typed).toInt().coerceAtLeast(0)).let { if (typed >= 1f) n.text else it }
        val bodyText = if (typed < 1f && (ct * 8).toInt() % 2 == 0) "$shown▌" else shown
        val lay = StaticLayout.Builder.obtain(bodyText, 0, bodyText.length, body, tw.toInt().coerceAtLeast(10))
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save(); c.translate(tx, panel.top + hh + u * 3.3f); lay.draw(c); c.restore()

        // question
        val qy = panel.bottom - u * 3.6f
        label.textAlign = Paint.Align.CENTER; label.textSize = u * 0.8f; label.letterSpacing = 0.1f
        label.color = acc((180 + 75 * sin(t * 4f)).toInt())
        c.drawText("アプリを起動しますか？", cx, qy, label)
        if (listening) {
            label.textSize = u * 0.52f; label.color = acc(200)
            c.drawText("◉ LISTENING ―「はい」で開く ／「いいえ」で閉じる", cx, qy + u * 0.95f, label)
            val nb = 20; val bw = u * 0.22f
            for (k in 0 until nb) {
                val h = u * (0.1f + micLevel * 0.7f * (0.5f + 0.5f * sin(t * 12f + k)))
                fill.color = acc(200)
                c.drawRect(cx - nb * bw / 2 + k * bw + 2, qy - u * 1.6f - h, cx - nb * bw / 2 + (k + 1) * bw - 2, qy - u * 1.6f, fill)
            }
        }
        label.letterSpacing = 0f

        // buttons（開いた直後に下からせり上がる）
        val rise = (1f - ease(ct / 0.5f)) * u * 0.8f
        val bw = (panel.width() - u * 3f) / 2; val bh = u * 1.9f; val by = panel.bottom - u * 0.8f - bh + rise
        openR.set(panel.left + u, by, panel.left + u + bw, by + bh)
        dismissR.set(panel.right - u - bw, by, panel.right - u, by + bh)
        button(c, openR, "OPEN", ACC, true, t)
        button(c, dismissR, "DISMISS", acc(200), false, t)

        // countdown
        val left = 1f - age.toFloat() / timeoutMs
        fill.color = acc(40); c.drawRect(panel.left, panel.bottom - 6f, panel.right, panel.bottom, fill)
        fill.color = if (left < 0.25f) RED else ACC
        c.drawRect(panel.left, panel.bottom - 6f, panel.left + panel.width() * left, panel.bottom, fill)
        mono.textAlign = Paint.Align.RIGHT; mono.textSize = u * 0.5f; mono.color = acc(150)
        c.drawText(String.format("AUTO-DISMISS %02ds", ((timeoutMs - age) / 1000).toInt()), panel.right, panel.bottom + u * 0.9f, mono)
        // 左下：受信時刻と通し番号
        mono.textAlign = Paint.Align.LEFT
        c.drawText(String.format("RX %tT  #%03d", n.time, seq), panel.left, panel.bottom + u * 0.9f, mono)

        postInvalidateDelayed(if (ct < 1.4f) 16 else 33)
    }

    private fun button(c: Canvas, r: RectF, text: String, col: Int, primary: Boolean, t: Float) {
        fill.color = if (primary) acc(70) else acc(20); c.drawRect(r, fill)
        if (primary) {
            glow.strokeWidth = 8f; glow.color = acc((120 + 50 * sin(t * 4f)).toInt()); c.drawRect(r, glow)
            // 主ボタンは光が左から右へ流れる
            c.save(); c.clipRect(r)
            val x = r.left + ((t * 0.6f) % 1f) * (r.width() + r.height() * 2) - r.height()
            fill.shader = LinearGradient(x - r.height(), 0f, x, 0f, Color.TRANSPARENT, Color.argb(70, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRect(x - r.height(), r.top, x, r.bottom, fill); fill.shader = null
            c.restore()
        }
        stroke.strokeWidth = 2.5f; stroke.color = col; c.drawRect(r, stroke)
        label.textAlign = Paint.Align.CENTER; label.textSize = r.height() * 0.42f; label.letterSpacing = 0.3f; label.color = col
        c.drawText(text, r.centerX(), r.centerY() + label.textSize * 0.36f, label)
        label.letterSpacing = 0f
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
}

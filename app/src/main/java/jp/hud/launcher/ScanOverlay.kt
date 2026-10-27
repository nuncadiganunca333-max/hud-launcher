package jp.hud.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

/** カメラを向けて解析するスキャンモード（映画のHUDスキャン風） */
class ScanOverlay(context: Context, private val owner: LifecycleOwner) : FrameLayout(context) {

    interface Listener {
        fun onScanAnalyze(frame: Bitmap, question: String?)
        fun onScanAsk()
        fun onScanClose()
    }

    enum class State { LIVE, ANALYZING, RESULT, LISTENING }

    var listener: Listener? = null
    var state = State.LIVE
        set(v) { field = v; hudLayer.invalidate() }
    var result = ""
        set(v) { field = v; resultAt = System.currentTimeMillis(); hudLayer.invalidate() }
    var heard = ""
    private var resultAt = 0L

    private val preview = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    private val hudLayer = HudLayer(context)
    private var useCases: List<UseCase> = emptyList()
    private val exec = Executors.newSingleThreadExecutor()
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects().enableClassification().build())

    // tracked objects in view coordinates
    private class Track(val id: Int, var box: RectF, var target: RectF, var label: String, var conf: Float, var seen: Long, val born: Long)
    private val tracks = HashMap<Int, Track>()
    private var imgW = 1; private var imgH = 1

    init {
        visibility = GONE
        addView(preview, LayoutParams(-1, -1))
        addView(hudLayer, LayoutParams(-1, -1))
    }

    val isShowing get() = visibility == VISIBLE

    @SuppressLint("UnsafeOptInUsageError")
    fun open() {
        visibility = VISIBLE; state = State.LIVE; result = ""; heard = ""; tracks.clear()
        hudLayer.openedAt = System.currentTimeMillis()
        Cam.withProvider(context) { p ->
            if (!isShowing) return@withProvider
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            @Suppress("DEPRECATION")
            val an = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            an.setAnalyzer(exec) { img ->
                val media = img.image
                if (media == null) { img.close(); return@setAnalyzer }
                val rot = img.imageInfo.rotationDegrees
                val w = if (rot % 180 == 0) img.width else img.height
                val h = if (rot % 180 == 0) img.height else img.width
                detector.process(InputImage.fromMediaImage(media, rot))
                    .addOnSuccessListener { objs ->
                        imgW = w; imgH = h
                        val now = System.currentTimeMillis()
                        for (o in objs) {
                            val id = o.trackingId ?: continue
                            val r = mapBox(o.boundingBox)
                            val lab = o.labels.maxByOrNull { it.confidence }
                            val name = when (lab?.text) {
                                "Fashion good" -> "FASHION"; "Food" -> "FOOD"; "Home good" -> "HOME ITEM"
                                "Place" -> "PLACE"; "Plant" -> "PLANT"; null -> "OBJECT"; else -> lab.text.uppercase()
                            }
                            val t = tracks[id]
                            if (t == null) tracks[id] = Track(id, RectF(r), r, name, lab?.confidence ?: 0f, now, now)
                            else { t.target = r; t.label = name; t.conf = lab?.confidence ?: t.conf; t.seen = now }
                        }
                        tracks.entries.removeAll { now - it.value.seen > 700 }
                    }
                    .addOnCompleteListener { img.close() }
            }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
                useCases = listOf(prev, an)
            } catch (e: Exception) { }
        }
    }

    fun close() {
        useCases.forEach { u -> try { Cam.provider?.unbind(u) } catch (e: Exception) { } }
        useCases = emptyList()
        visibility = GONE
    }

    /** 解析用に今の映像を切り出す（region を指定するとその部分だけ） */
    fun grab(region: RectF? = null): Bitmap? {
        val bmp = preview.bitmap ?: return null
        if (region == null) return bmp
        val r = Rect(region.left.toInt().coerceAtLeast(0), region.top.toInt().coerceAtLeast(0),
            region.right.toInt().coerceAtMost(bmp.width), region.bottom.toInt().coerceAtMost(bmp.height))
        if (r.width() < 40 || r.height() < 40) return bmp
        return Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
    }

    private fun mapBox(b: Rect): RectF {
        val vw = width.toFloat(); val vh = height.toFloat()
        val s = max(vw / imgW, vh / imgH)
        val ox = (vw - imgW * s) / 2; val oy = (vh - imgH * s) / 2
        return RectF(b.left * s + ox, b.top * s + oy, b.right * s + ox, b.bottom * s + oy)
    }

    private inner class HudLayer(ctx: Context) : View(ctx) {
        var openedAt = 0L
        private val CYAN = Color.rgb(120, 222, 255)
        private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 120, 222, 255)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
        private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 248, 255) }
        private val btns = LinkedHashMap<String, RectF>()
        private val start = System.nanoTime()

        override fun onDraw(c: Canvas) {
            val W = width.toFloat(); val H = height.toFloat()
            val t = (System.nanoTime() - start) / 1e9f
            val u = min(W, H) / 28f
            val age = (System.currentTimeMillis() - openedAt) / 600f
            btns.clear()

            // tint + scanlines + vignette
            fill.color = Color.argb(60, 0, 40, 70); c.drawRect(0f, 0f, W, H, fill)
            fill.color = Color.argb(22, 0, 0, 0); var y = 0f
            while (y < H) { c.drawRect(0f, y, W, y + 2f, fill); y += 5f }
            fill.color = Color.WHITE
            fill.shader = RadialGradient(W / 2, H / 2, max(W, H) * 0.7f, intArrayOf(Color.TRANSPARENT, Color.argb(170, 0, 6, 14)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fill); fill.shader = null

            // opening wipe
            if (age < 1f) {
                val yy = H * age
                glow.strokeWidth = 16f; glow.color = cyan(200); c.drawLine(0f, yy, W, yy, glow)
                stroke.strokeWidth = 3f; stroke.color = Color.WHITE; c.drawLine(0f, yy, W, yy, stroke)
                fill.color = Color.argb(200, 0, 8, 16); c.drawRect(0f, yy, W, H, fill)
            }

            // frame brackets
            val m = u * 0.8f; val a = u * 2.5f
            stroke.strokeWidth = 4f; stroke.color = CYAN
            for ((x0, y0, dx, dy) in listOf(Q(m, m + u * 2, 1f, 1f), Q(W - m, m + u * 2, -1f, 1f), Q(m, H - m, 1f, -1f), Q(W - m, H - m, -1f, -1f))) {
                c.drawLine(x0, y0, x0 + dx * a, y0, stroke); c.drawLine(x0, y0, x0, y0 + dy * a, stroke)
            }
            label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.75f; label.letterSpacing = 0.3f; label.color = CYAN
            c.drawText("◆ SCAN MODE", m, m + u * 1.2f, label)
            label.textAlign = Paint.Align.RIGHT; label.textSize = u * 0.5f; label.color = cyan(170)
            c.drawText(when (state) { State.LIVE -> "TRACKING ${tracks.size} OBJECT(S)"; State.ANALYZING -> "ANALYZING"; State.RESULT -> "ANALYSIS COMPLETE"; State.LISTENING -> "LISTENING" },
                W - m, m + u * 1.2f, label)
            label.letterSpacing = 0f

            // center reticle
            val cx = W / 2; val cy = H * 0.42f; val rr = u * 3f
            stroke.strokeWidth = 1.5f; stroke.color = cyan(150); c.drawCircle(cx, cy, rr, stroke)
            stroke.strokeWidth = 3f; stroke.color = CYAN
            val ro = RectF(cx - rr * 1.25f, cy - rr * 1.25f, cx + rr * 1.25f, cy + rr * 1.25f)
            for (k in 0 until 4) c.drawArc(ro, t * 60f + k * 90f, 30f, false, stroke)
            stroke.strokeWidth = 1.5f
            c.drawLine(cx - rr * 0.4f, cy, cx - rr * 0.1f, cy, stroke); c.drawLine(cx + rr * 0.1f, cy, cx + rr * 0.4f, cy, stroke)
            c.drawLine(cx, cy - rr * 0.4f, cx, cy - rr * 0.1f, stroke); c.drawLine(cx, cy + rr * 0.1f, cx, cy + rr * 0.4f, stroke)
            mono.textAlign = Paint.Align.LEFT; mono.textSize = u * 0.4f; mono.color = cyan(160)
            c.drawText(String.format(Locale.US, "AZ %03d  EL %+03d", ((t * 7) % 360).toInt(), (sin(t * 0.7f) * 12).toInt()), cx + rr * 1.4f, cy - rr * 0.9f, mono)

            // tracked objects
            val now = System.currentTimeMillis()
            for (tr in tracks.values) {
                tr.box.left += (tr.target.left - tr.box.left) * 0.35f; tr.box.top += (tr.target.top - tr.box.top) * 0.35f
                tr.box.right += (tr.target.right - tr.box.right) * 0.35f; tr.box.bottom += (tr.target.bottom - tr.box.bottom) * 0.35f
                val b = tr.box
                val lockP = ((now - tr.born) / 400f).coerceIn(0f, 1f)
                val pad = u * 1.5f * (1 - lockP)
                val bb = RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                val k = min(bb.width(), bb.height()) * 0.18f
                stroke.strokeWidth = 3f; stroke.color = CYAN
                glow.strokeWidth = 10f; glow.color = cyan(140)
                for ((x0, y0, dx, dy) in listOf(Q(bb.left, bb.top, 1f, 1f), Q(bb.right, bb.top, -1f, 1f), Q(bb.left, bb.bottom, 1f, -1f), Q(bb.right, bb.bottom, -1f, -1f))) {
                    c.drawLine(x0, y0, x0 + dx * k, y0, glow); c.drawLine(x0, y0, x0, y0 + dy * k, glow)
                    c.drawLine(x0, y0, x0 + dx * k, y0, stroke); c.drawLine(x0, y0, x0, y0 + dy * k, stroke)
                }
                stroke.strokeWidth = 1f; stroke.color = cyan(70); c.drawRect(bb, stroke)
                val sy = bb.top + bb.height() * ((t * 0.8f + tr.id * 0.3f) % 1f)
                stroke.color = cyan(120); c.drawLine(bb.left, sy, bb.right, sy, stroke)
                val txt = String.format(Locale.US, "%s  #%02d  %d%%", tr.label, tr.id % 100, (tr.conf * 100).toInt())
                label.textAlign = Paint.Align.LEFT; label.textSize = u * 0.5f; label.letterSpacing = 0.15f
                fill.color = Color.argb(200, 0, 10, 20)
                c.drawRect(bb.left, bb.top - u * 0.95f, bb.left + label.measureText(txt) + u * 0.5f, bb.top - u * 0.1f, fill)
                label.color = CYAN; c.drawText(txt, bb.left + u * 0.25f, bb.top - u * 0.3f, label)
                label.letterSpacing = 0f
                if (state == State.LIVE) {
                    mono.textSize = u * 0.36f; mono.color = cyan(150)
                    c.drawText("TAP TO ANALYZE", bb.left, bb.bottom + u * 0.6f, mono)
                }
            }

            // analyzing sweep
            if (state == State.ANALYZING) {
                val sy = H * ((t * 0.6f) % 1f)
                fill.color = Color.WHITE
                fill.shader = LinearGradient(0f, sy - u * 4, 0f, sy, Color.TRANSPARENT, cyan(110), Shader.TileMode.CLAMP)
                c.drawRect(0f, sy - u * 4, W, sy, fill); fill.shader = null
                stroke.strokeWidth = 2.5f; stroke.color = Color.WHITE; c.drawLine(0f, sy, W, sy, stroke)
                mono.textAlign = Paint.Align.CENTER; mono.textSize = u * 0.45f; mono.color = cyan(200)
                c.drawText(hex(t), cx, cy + rr * 2f, mono)
            }

            // result / heard panel
            val bh = u * 2.2f
            val panelBottom = H - m - bh - u * 0.6f
            val shown = when {
                state == State.ANALYZING -> "解析しています" + ".".repeat(((t * 3).toInt() % 4))
                state == State.LISTENING -> if (heard.isEmpty()) "質問をどうぞ…" else "› $heard"
                result.isNotEmpty() -> result.take(((now - resultAt) / 28).toInt().coerceAtMost(result.length))
                else -> "物体をタップすると詳しく解析します。ANALYZEで画面全体を解析。"
            }
            body.textSize = u * 0.72f
            val tw = (W - m * 2 - u * 1.2f).toInt()
            val lay = StaticLayout.Builder.obtain(shown, 0, shown.length, body, tw).setMaxLines(6).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            val pr = RectF(m, panelBottom - lay.height - u * 1.2f, W - m, panelBottom)
            fill.color = Color.argb(215, 2, 18, 32); c.drawRect(pr, fill)
            stroke.strokeWidth = 1.5f; stroke.color = cyan(170); c.drawRect(pr, stroke)
            glow.strokeWidth = 8f; glow.color = cyan(90); c.drawRect(pr, glow)
            c.save(); c.translate(pr.left + u * 0.6f, pr.top + u * 0.6f); lay.draw(c); c.restore()

            // buttons
            val bw = (W - m * 2 - u) / 3
            listOf("analyze" to "ANALYZE", "ask" to "◉ ASK", "close" to "CLOSE").forEachIndexed { i, (key, txt) ->
                val r = RectF(m + i * (bw + u / 2), H - m - bh, m + i * (bw + u / 2) + bw, H - m)
                btns[key] = r
                val prim = key == "analyze"
                fill.color = if (prim) cyan(70) else cyan(25); c.drawRect(r, fill)
                if (prim) { glow.strokeWidth = 10f; glow.color = cyan(150); c.drawRect(r, glow) }
                stroke.strokeWidth = 2f; stroke.color = if (prim) CYAN else cyan(170); c.drawRect(r, stroke)
                label.textAlign = Paint.Align.CENTER; label.textSize = bh * 0.36f; label.letterSpacing = 0.25f; label.color = CYAN
                c.drawText(txt, r.centerX(), r.centerY() + label.textSize * 0.36f, label)
                label.letterSpacing = 0f
            }
            if (isShowing) postInvalidateOnAnimation()
        }

        private fun hex(t: Float): String {
            val r = kotlin.random.Random((t * 10).toLong()); val sb = StringBuilder()
            repeat(24) { if (it % 5 == 4) sb.append(' ') else sb.append("0123456789ABCDEF"[r.nextInt(16)]) }
            return sb.toString()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            for ((k, r) in btns) if (r.contains(e.x, e.y)) {
                when (k) {
                    "analyze" -> if (state != State.ANALYZING) grab()?.let { listener?.onScanAnalyze(it, null) }
                    "ask" -> if (state != State.ANALYZING) listener?.onScanAsk()
                    "close" -> listener?.onScanClose()
                }
                return true
            }
            if (state != State.ANALYZING) {
                tracks.values.firstOrNull { RectF(it.box).apply { inset(-30f, -30f) }.contains(e.x, e.y) }?.let { tr ->
                    grab(tr.box)?.let { listener?.onScanAnalyze(it, null) }
                }
            }
            return true
        }
    }

    private data class Q(val a: Float, val b: Float, val c: Float, val d: Float)
}

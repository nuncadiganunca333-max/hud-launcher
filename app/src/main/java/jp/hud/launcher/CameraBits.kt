package jp.hud.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs

/** 普通の Activity でも CameraX を使うための簡易ライフサイクル */
class CamOwner : LifecycleOwner {
    private val reg = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = reg
    fun resume() { reg.currentState = Lifecycle.State.RESUMED }
    fun pause() { reg.currentState = Lifecycle.State.CREATED }
}

/** カメラの取り合いを避けるための共通ヘルパー */
object Cam {
    var provider: ProcessCameraProvider? = null
    fun withProvider(ctx: Context, block: (ProcessCameraProvider) -> Unit) {
        provider?.let { block(it); return }
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({ try { provider = f.get(); block(provider!!) } catch (e: Exception) { } }, ContextCompat.getMainExecutor(ctx))
    }
}

/**
 * インカメラで
 *  - 顔があるか（約1.5秒ごと）
 *  - 手のジェスチャー（gestures=true のとき毎フレーム）：空中スワイプ・手でカメラを覆う
 * を判定する。画像は端末内で処理し、保存も送信もしない。
 */
class FaceWatcher(private val ctx: Context, private val owner: LifecycleOwner, private val onChange: (Boolean) -> Unit) {
    var onSwipe: ((Int) -> Unit)? = null     // +1 = 右へ、-1 = 左へ（ユーザーから見た向き）
    var onCover: (() -> Unit)? = null
    private var useCase: UseCase? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.12f).build())
    @Volatile private var lastRun = 0L
    var present = false; private set
    var running = false; private set
    var gestures = false; private set

    // gesture state
    private val GW = 32; private val GH = 24
    private var prev: IntArray? = null
    private val track = ArrayDeque<Pair<Long, Float>>()
    private var lastGesture = 0L
    private var coverStart = 0L
    private var lastBright = 0L

    @SuppressLint("UnsafeOptInUsageError")
    fun start(withGestures: Boolean) {
        if (running && gestures == withGestures) return
        if (running) stop()
        running = true; gestures = withGestures
        Cam.withProvider(ctx) { p ->
            if (!running) return@withProvider
            @Suppress("DEPRECATION")
            val b = ImageAnalysis.Builder().setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            try {
                androidx.camera.camera2.interop.Camera2Interop.Extender(b).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    if (withGestures) android.util.Range(12, 15) else android.util.Range(5, 8))
            } catch (e: Throwable) { }
            val a = b.build()
            a.setAnalyzer(exec) { img ->
                val now = System.currentTimeMillis()
                val media = img.image
                if (media == null) { img.close(); return@setAnalyzer }
                if (gestures) try { gesture(img, now) } catch (e: Exception) { }
                if (now - lastRun < 1500) { img.close(); return@setAnalyzer }
                lastRun = now
                val input = InputImage.fromMediaImage(media, img.imageInfo.rotationDegrees)
                detector.process(input)
                    .addOnSuccessListener { faces ->
                        val has = faces.isNotEmpty()
                        if (has != present) { present = has; onChange(has) }
                    }
                    .addOnCompleteListener { img.close() }
            }
            try {
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                useCase = a
            } catch (e: Exception) { running = false }
        }
    }

    private fun gesture(img: androidx.camera.core.ImageProxy, now: Long) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        val w = img.width; val h = img.height
        val cur = IntArray(GW * GH)
        var sum = 0L
        for (gy in 0 until GH) {
            val y = gy * h / GH
            for (gx in 0 until GW) {
                val x = gx * w / GW
                val v = buf.get(y * rs + x * ps).toInt() and 0xFF
                cur[gy * GW + gx] = v; sum += v
            }
        }
        val mean = sum / (GW * GH)
        // --- 手でカメラを覆う
        if (mean < 22) {
            if (coverStart == 0L) coverStart = now
            if (now - coverStart > 1100 && now - lastBright < 5000 && now - lastGesture > 2500) {
                lastGesture = now; coverStart = 0L
                main.post { onCover?.invoke() }
            }
        } else {
            coverStart = 0L
            if (mean > 55) lastBright = now
        }
        // --- 空中スワイプ（動いている部分の重心を追う）
        val p = prev; prev = cur
        if (p == null || mean < 30) { track.clear(); return }
        var cnt = 0; var sx = 0f; var sy = 0f
        for (i in cur.indices) if (abs(cur[i] - p[i]) > 28) { cnt++; sx += (i % GW).toFloat(); sy += (i / GW).toFloat() }
        val energy = cnt.toFloat() / (GW * GH)
        if (energy < 0.06f) { if (track.isNotEmpty() && now - track.last().first > 300) track.clear(); return }
        val mx = sx / cnt / (GW - 1); val my = sy / cnt / (GH - 1)
        // 画像の回転を補正して「画面の横方向」の位置にする
        val ux = when (img.imageInfo.rotationDegrees) { 90 -> 1f - my; 180 -> 1f - mx; 270 -> my; else -> mx }
        track.addLast(now to ux)
        while (track.isNotEmpty() && now - track.first().first > 650) track.removeFirst()
        if (track.size >= 3 && now - lastGesture > 900) {
            val first = track.first().second; val last = track.last().second
            val mn = track.minOf { it.second }; val mxv = track.maxOf { it.second }
            if (abs(last - first) > 0.38f && mxv - mn > 0.4f) {
                lastGesture = now; track.clear()
                // インカメラは鏡写しではない映像なので、ユーザーの右手方向は画像の左
                val dir = if (last < first) 1 else -1
                main.post { onSwipe?.invoke(dir) }
            }
        }
    }

    fun stop() {
        running = false
        useCase?.let { u -> try { Cam.provider?.unbind(u) } catch (e: Exception) { } }
        useCase = null
        present = false
        prev = null; track.clear()
    }
}

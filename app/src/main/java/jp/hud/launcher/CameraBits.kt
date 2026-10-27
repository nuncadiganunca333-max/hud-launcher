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
 * インカメラで顔があるかだけを約1秒ごとに確認する（画像は端末内で処理し、保存も送信もしない）。
 */
class FaceWatcher(private val ctx: Context, private val owner: LifecycleOwner, private val onChange: (Boolean) -> Unit) {
    private var useCase: UseCase? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.12f).build())
    @Volatile private var lastRun = 0L
    var present = false; private set
    var running = false; private set

    @SuppressLint("UnsafeOptInUsageError")
    fun start() {
        if (running) return
        running = true
        Cam.withProvider(ctx) { p ->
            if (!running) return@withProvider
            @Suppress("DEPRECATION")
            val a = ImageAnalysis.Builder().setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            a.setAnalyzer(exec) { img ->
                val now = System.currentTimeMillis()
                val media = img.image
                if (now - lastRun < 1000 || media == null) { img.close(); return@setAnalyzer }
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

    fun stop() {
        running = false
        useCase?.let { u -> try { Cam.provider?.unbind(u) } catch (e: Exception) { } }
        useCase = null
        present = false
    }
}

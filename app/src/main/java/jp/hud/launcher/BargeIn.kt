package jp.hud.launcher

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ジャービスが話している間もマイクを小さく開けておき、
 * こちらが話し始めたら onVoice を呼ぶ（返事を止めて聞き取りに切り替えるため）。
 *
 * スピーカーから出ているジャービス自身の声を拾わないよう、
 * 話し始めの0.4秒で「自分の声の大きさ」を測り、それよりはっきり大きい声が
 * 続いたときだけ反応する。
 */
class BargeIn(private val onVoice: () -> Unit) {
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "barge-in") { loop() }
    }

    fun stop() { running = false }

    private fun loop() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192))
        } catch (e: Exception) { running = false; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); running = false; return }
        val aec = try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null } catch (e: Exception) { null }
        val ns = try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null } catch (e: Exception) { null }
        try {
            rec.startRecording()
            val buf = ShortArray(512)            // 約32ミリ秒ぶん
            val t0 = System.currentTimeMillis()
            var floor = 0.0; var learned = 0
            var hot = 0
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n)
                if (System.currentTimeMillis() - t0 < 400) { floor += rms; learned++; continue }
                if (learned > 0) { floor /= learned; learned = 0 }
                val threshold = max(1400.0, floor * 2.6)
                if (rms > threshold) hot++ else {
                    hot = max(0, hot - 1)
                    floor = floor * 0.96 + rms * 0.04   // ジャービスの声の大きさの変化に合わせる
                }
                if (hot >= 7) {                         // 約0.2秒、はっきりした声が続いた
                    running = false
                    onVoice()
                }
            }
        } catch (e: Exception) {
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release(); aec?.release(); ns?.release()
            running = false
        }
    }
}

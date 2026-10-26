package jp.hud.launcher

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 読み上げ音声（WAV, 16bit PCM）に「映画のAIっぽい」加工をかける。
 *  - 低音を足して声に厚み
 *  - ごく短い遅延で声を重ねる（コーラス）→ わずかに金属的な響き
 *  - 軽いサチュレーションで芯を出す
 */
object VoiceFx {

    /** strength: 1 = AI, 2 = AI 強め */
    fun process(src: File, dst: File, strength: Int): Boolean {
        return try {
            val bytes = src.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // find "fmt " and "data" chunks
            var pos = 12
            var rate = 22050; var channels = 1; var bits = 16
            var dataOff = -1; var dataLen = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val len = bb.getInt(pos + 4)
                if (id == "fmt ") {
                    channels = bb.getShort(pos + 10).toInt()
                    rate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                } else if (id == "data") { dataOff = pos + 8; dataLen = minOf(len, bytes.size - dataOff); break }
                pos += 8 + len + (len and 1)
            }
            if (dataOff < 0 || bits != 16 || channels != 1) return false
            val n = dataLen / 2
            val x = FloatArray(n) { bb.getShort(dataOff + it * 2) / 32768f }

            val k = if (strength >= 2) 1.4f else 1f
            val out = FloatArray(n + rate / 4)
            // low-pass for body
            var lp = 0f
            val a = 0.06f
            val d2 = (rate * 0.017f).toInt()
            for (i in 0 until n) {
                lp += a * (x[i] - lp)
                // chorus: delay ~9ms ± 1.5ms
                val mod = (rate * (0.009f + 0.0015f * sin(2 * PI.toFloat() * 0.6f * i / rate))).toInt()
                val c1 = if (i - mod >= 0) x[i - mod] else 0f
                val c2 = if (i - d2 >= 0) x[i - d2] else 0f
                var y = x[i] * 0.9f + c1 * 0.28f * k + c2 * 0.14f * k + lp * 0.55f * k
                y = tanh(y * 1.25f) / 1.25f
                out[i] += y
            }
            // normalize
            var peak = 0.0001f
            for (v in out) peak = max(peak, abs(v))
            val g = 0.92f / peak
            val pcm = ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in out) pcm.putShort((v * g * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            writeWav(dst, pcm.array(), rate)
            true
        } catch (e: Exception) { false }
    }

    private fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        RandomAccessFile(f, "rw").use { it.setLength(0); it.write(h.array()); it.write(pcm) }
    }
}

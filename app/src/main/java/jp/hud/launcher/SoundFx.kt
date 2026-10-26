package jp.hud.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** 合成した小さな電子音と振動 */
object SoundFx {
    enum class Fx { TAP, OPEN, BACK, ALERT, LISTEN, DONE, ERROR }

    private const val RATE = 22050
    private val cache = HashMap<Fx, ShortArray>()
    private var app: Context? = null
    var enabled = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (cache.isNotEmpty()) return
        cache[Fx.TAP] = chirp(2600f, 3400f, 0.035f, 0.25f)
        cache[Fx.OPEN] = concat(chirp(900f, 1800f, 0.07f, 0.25f), chirp(1800f, 2700f, 0.06f, 0.2f))
        cache[Fx.BACK] = chirp(2200f, 1100f, 0.07f, 0.22f)
        cache[Fx.ALERT] = concat(tone(1320f, 0.07f, 0.28f), silence(0.04f), tone(1760f, 0.09f, 0.28f), silence(0.04f), tone(2350f, 0.12f, 0.24f))
        cache[Fx.LISTEN] = chirp(700f, 1900f, 0.13f, 0.28f)
        cache[Fx.DONE] = chirp(1900f, 900f, 0.11f, 0.24f)
        cache[Fx.ERROR] = concat(tone(420f, 0.09f, 0.3f), silence(0.03f), tone(330f, 0.12f, 0.3f))
    }

    fun play(fx: Fx, haptic: Boolean = true) {
        val ctx = app ?: return
        if (haptic) vibrate(ctx, fx)
        if (!enabled) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val pcm = cache[fx] ?: return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (e: Exception) { }
    }

    private fun vibrate(ctx: Context, fx: Fx) {
        try {
            val v = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            val eff = when (fx) {
                Fx.TAP, Fx.BACK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                Fx.OPEN, Fx.DONE, Fx.LISTEN -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                Fx.ALERT -> VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30, 60, 50), -1)
                Fx.ERROR -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
            v.vibrate(eff)
        } catch (e: Exception) { }
    }

    private fun env(i: Int, n: Int): Float {
        val a = min(1f, i / (RATE * 0.004f))
        val r = exp(-4f * i / n)
        return a * r
    }

    private fun chirp(f0: Float, f1: Float, dur: Float, vol: Float): ShortArray {
        val n = (RATE * dur).toInt(); var ph = 0.0
        return ShortArray(n) { i ->
            val f = f0 + (f1 - f0) * i / n
            ph += 2 * PI * f / RATE
            (sin(ph) * env(i, n) * vol * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun tone(f: Float, dur: Float, vol: Float) = chirp(f, f, dur, vol)
    private fun silence(dur: Float) = ShortArray((RATE * dur).toInt())
    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }
}

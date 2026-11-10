package com.tilcayo.fat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Маленький синтезатор: все звуки и музыка рождаются прямо в коде, без аудиофайлов.
 *
 * Звук стал мягче: осцилляторы сглажены (band-limited square/saw), у нот полноценная
 * атака и затухание, высокие частоты подрезаются фильтром, а для музыки добавлены
 * пэд, арпеджио, бас, барабаны и реверб — вместо резкого «пищащего» чиптюна.
 */
private object Synth {
    const val SR = 32000
    const val SINE = 0
    const val TRI = 1
    const val SQUARE = 2
    const val SAW = 3
    const val NOISE = 4
    const val TWO_PI = 6.2831855f

    fun hz(semitones: Int) = 440f * 2f.pow(semitones / 12f)

    /** Сглаживание разрыва осциллятора — убирает цифровую «грязь» на высоких нотах. */
    private fun blep(t: Float, dt: Float): Float {
        if (t < dt) { val x = t / dt; return x + x - x * x - 1f }
        if (t > 1f - dt) { val x = (t - 1f) / dt; return x * x + x + x + 1f }
        return 0f
    }

    private fun waveAt(phase: Float, dt: Float, wave: Int, duty: Float): Float = when (wave) {
        SINE -> sin(phase * TWO_PI)
        TRI -> 4f * abs(phase - 0.5f) - 1f
        SQUARE -> {
            var s = if (phase < duty) 1f else -1f
            s += blep(phase, dt)
            s -= blep((phase - duty + 1f) % 1f, dt)
            s
        }
        SAW -> 2f * phase - 1f - blep(phase, dt)
        else -> 0f
    }

    /**
     * Одна нота или свип с мягкой атакой, затуханием, вибрато и опциональным фильтром.
     * lp / lpTo — срез фильтра в начале и в конце (0 = фильтр выключен).
     */
    fun tone(
        f0: Float, f1: Float, dur: Float, wave: Int = TRI, vol: Float = 0.4f,
        duty: Float = 0.5f, decay: Float = 2f, attack: Float = 0.004f, vibrato: Float = 0f,
        lp: Float = 0f, lpTo: Float = 0f, detune: Float = 0f,
    ): FloatArray {
        val n = max(1, (dur * SR).toInt())
        val out = FloatArray(n)
        var phase = 0f
        var phase2 = 0f
        var hold = 0f
        var y = 0f
        val rnd = Random(7)
        val atk = max(1f, attack * SR)
        for (i in 0 until n) {
            val t = i / n.toFloat()
            var f = f0 + (f1 - f0) * t
            if (vibrato > 0f) f *= 1f + 0.03f * sin(i / SR.toFloat() * vibrato * TWO_PI)
            if (f < 1f) f = 1f
            val dt = f / SR
            phase += dt; if (phase >= 1f) phase -= 1f
            var s: Float
            if (wave == NOISE) {
                if (i % max(1, (SR / max(f, 80f) / 2).toInt()) == 0) hold = rnd.nextFloat() * 2f - 1f
                s = hold
            } else {
                s = waveAt(phase, dt, wave, duty)
                if (detune > 0f) {
                    phase2 += dt * (1f + detune); if (phase2 >= 1f) phase2 -= 1f
                    s = 0.62f * s + 0.38f * waveAt(phase2, dt, wave, duty)
                }
            }
            val env = min(1f, i / atk) * (1f - t).pow(decay)
            var v = s * env * vol
            val cut = if (lp > 0f) lp + (lpTo - lp) * t else 0f
            if (cut > 0f) {
                val a = 1f - exp(-TWO_PI * cut / SR)
                y += a * (v - y)
                v = y
            }
            out[i] = v
        }
        return out
    }

    /** Колокольчик/маримба: сумма синусоид с быстрым затуханием верхних обертонов. */
    fun bell(freq: Float, dur: Float, vol: Float = 0.5f, bright: Float = 0.4f): FloatArray {
        val n = max(1, (dur * SR).toInt())
        val out = FloatArray(n)
        val partials = floatArrayOf(1f, 2f, 3f, 4.2f, 5.6f)
        val amps = floatArrayOf(1f, 0.45f * bright, 0.24f * bright, 0.12f * bright, 0.07f * bright)
        val decays = floatArrayOf(1.1f, 1.6f, 2.2f, 2.8f, 3.4f)
        val atk = max(1f, 0.003f * SR)
        for (i in 0 until n) {
            val t = i / n.toFloat()
            var s = 0f
            for (p in partials.indices) {
                s += amps[p] * sin(TWO_PI * freq * partials[p] * i / SR) * (1f - t).pow(decays[p])
            }
            out[i] = s * min(1f, i / atk) * vol
        }
        return out
    }

    /** Мягкий «плавающий» аккорд: несколько синусоид с медленной атакой и лёгким расстроем. */
    fun pad(freqs: FloatArray, dur: Float, vol: Float = 0.16f, attack: Float = 0.3f, release: Float = 0.5f, lp: Float = 2200f): FloatArray {
        val n = max(1, (dur * SR).toInt())
        val out = FloatArray(n)
        val atk = max(1f, attack * SR)
        val rel = max(1f, release * SR)
        val ph = FloatArray(freqs.size) { 0f }
        var y = 0f
        val a = 1f - exp(-TWO_PI * lp / SR)
        for (i in 0 until n) {
            var s = 0f
            for (k in freqs.indices) {
                val f = freqs[k] * (1f + 0.0022f * k)
                ph[k] += f / SR; if (ph[k] >= 1f) ph[k] -= 1f
                s += sin(ph[k] * TWO_PI) + 0.22f * sin(ph[k] * 2f * TWO_PI)
            }
            s /= freqs.size
            val env = min(1f, i / atk) * min(1f, (n - i).toFloat() / rel)
            y += a * (s * env * vol - y)
            out[i] = y
        }
        return out
    }

    fun kick(dur: Float = 0.18f, vol: Float = 0.75f): FloatArray {
        val n = max(1, (dur * SR).toInt())
        val out = FloatArray(n)
        var ph = 0f
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val f = 132f * (1f - t).pow(2.4f) + 44f
            ph += f / SR; if (ph >= 1f) ph -= 1f
            val click = if (i < 50) (1f - i / 50f) * 0.22f else 0f
            out[i] = (sin(ph * TWO_PI) * (1f - t).pow(2f) + click) * vol
        }
        return out
    }

    fun snare(dur: Float = 0.2f, vol: Float = 0.34f): FloatArray {
        val n = max(1, (dur * SR).toInt())
        val out = FloatArray(n)
        val rnd = Random(3)
        var y = 0f
        val a = 1f - exp(-TWO_PI * 2400f / SR)
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            y += a * (noise - y)
            val body = sin(TWO_PI * 190f * i / SR)
            out[i] = (y * 0.72f + body * 0.28f) * (1f - t).pow(3f) * vol
        }
        return out
    }

    fun hat(dur: Float = 0.05f, vol: Float = 0.12f): FloatArray {
        val n = max(1, (dur * SR).toInt())
        val out = FloatArray(n)
        val rnd = Random(11)
        var lp = 0f
        val a = 1f - exp(-TWO_PI * 6500f / SR)
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            lp += a * (noise - lp)
            out[i] = (noise - lp) * (1f - t).pow(2.4f) * vol
        }
        return out
    }

    fun mix(len: Float, vararg parts: Pair<Float, FloatArray>): FloatArray {
        val out = FloatArray(max(1, (len * SR).toInt()))
        for ((at, buf) in parts) addAt(out, at, buf)
        return out
    }

    /** Аккуратно подмешивает буфер в нужную позицию (с отсечением по краям). */
    fun addAt(out: FloatArray, at: Float, buf: FloatArray) {
        val o = (at * SR).toInt()
        var i = 0
        while (i < buf.size && o + i < out.size) {
            if (o + i >= 0) out[o + i] += buf[i]
            i++
        }
    }

    fun arp(notes: IntArray, step: Float, wave: Int, vol: Float, noteDur: Float = step * 1.6f, duty: Float = 0.25f): FloatArray {
        val parts = notes.mapIndexed { i, n -> i * step to tone(hz(n), hz(n), noteDur, wave, vol, duty, 1.6f) }
        return mix(notes.size * step + noteDur, *parts.toTypedArray())
    }

    /** Арпеджио из колокольчиков — «магические» звуки наград и щитов. */
    fun bellArp(notes: IntArray, step: Float, vol: Float, dur: Float = 0.5f): FloatArray {
        val parts = notes.mapIndexed { i, n -> i * step to bell(hz(n), dur, vol, 0.4f) }
        return mix(notes.size * step + dur, *parts.toTypedArray())
    }

    /** Простой реверб (гребёнки + всепропускающие) — добавляет объём и «дороговизну» звучания. */
    fun reverb(input: FloatArray, wet: Float = 0.26f): FloatArray {
        val combs = intArrayOf(1291, 1489, 1663, 1897)
        val feeds = floatArrayOf(0.76f, 0.74f, 0.72f, 0.70f)
        val out = FloatArray(input.size)
        for (c in combs.indices) {
            val d = combs[c]
            val buf = FloatArray(d)
            var idx = 0
            var lp = 0f
            for (i in input.indices) {
                val y = buf[idx]
                lp += 0.32f * (y - lp)
                buf[idx] = input[i] + lp * feeds[c]
                idx++; if (idx >= d) idx = 0
                out[i] += y
            }
        }
        val inv = 1f / combs.size
        for (i in out.indices) out[i] *= inv
        for (d in intArrayOf(225, 556)) {
            val buf = FloatArray(d)
            var idx = 0
            for (i in out.indices) {
                val bufout = buf[idx]
                val y = bufout - out[i]
                buf[idx] = out[i] + bufout * 0.5f
                idx++; if (idx >= d) idx = 0
                out[i] = y
            }
        }
        for (i in out.indices) out[i] = input[i] + out[i] * wet
        return out
    }

    /** Мягкое ограничение пиков — громко, но без цифрового «треска». */
    fun softClip(x: Float): Float {
        val v = x.coerceIn(-3f, 3f)
        return (v * (27f + v * v)) / (27f + 9f * v * v)
    }

    fun pcm(f: FloatArray, gain: Float = 1f): ShortArray {
        var peak = 0.0001f
        for (v in f) peak = max(peak, abs(v))
        val k = if (peak > 0.99f) 0.99f / peak else 1f
        return ShortArray(f.size) {
            val v = softClip(f[it] * k * gain)
            (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun wav(s: ShortArray): ByteArray {
        val bo = ByteArrayOutputStream()
        fun i32(v: Int) { bo.write(v and 255); bo.write(v shr 8 and 255); bo.write(v shr 16 and 255); bo.write(v shr 24 and 255) }
        fun i16(v: Int) { bo.write(v and 255); bo.write(v shr 8 and 255) }
        bo.write("RIFF".toByteArray()); i32(36 + s.size * 2)
        bo.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
        bo.write("data".toByteArray()); i32(s.size * 2)
        for (v in s) i16(v.toInt())
        return bo.toByteArray()
    }
}

class Sfx(private val ctx: Context) {
    enum class S { JUMP, AIR, LAND, COIN, EAT, CAN_IN, CAN_FIRE, SHIELD, SMASH, DEATH, RECORD, CLICK, BUY, LEVEL, BREED, BUMP, LASER, HOP, BOSS_ROAR, BOSS_HIT, BOSS_DEAD, WARN, BOX, REVEAL_C, REVEAL_R, REVEAL_E, REVEAL_L, THROW, SLIP }

    var enabled = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()
    private val ids = HashMap<S, Int>()

    init {
        try {
            for (s in S.values()) {
                val f = File(ctx.cacheDir, "sfx_${s.name}.wav")
                f.writeBytes(Synth.wav(Synth.pcm(build(s))))
                ids[s] = pool.load(f.absolutePath, 1)
            }
        } catch (_: Exception) {
            // без звука игра всё равно работает
        }
    }

    fun play(s: S, rate: Float = 1f, vol: Float = 1f) {
        if (!enabled) return
        ids[s]?.let { pool.play(it, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f)) }
    }

    fun release() = pool.release()

    private fun build(s: S): FloatArray = with(Synth) {
        when (s) {
            S.JUMP -> mix(0.22f,
                0f to tone(300f, 640f, 0.14f, TRI, 0.52f, decay = 1.6f),
                0.012f to tone(640f, 920f, 0.08f, SINE, 0.18f, decay = 2.6f, lp = 4000f))
            S.AIR -> mix(0.30f,
                0f to tone(520f, 1000f, 0.16f, TRI, 0.46f, decay = 1.4f, vibrato = 5f),
                0f to tone(1040f, 1560f, 0.12f, SINE, 0.14f, decay = 2.2f, lp = 6000f))
            S.LAND -> mix(0.22f,
                0f to tone(150f, 62f, 0.12f, SINE, 0.6f, decay = 1.8f),
                0f to tone(900f, 900f, 0.05f, NOISE, 0.10f, decay = 3f, lp = 1600f))
            S.COIN -> mix(0.40f,
                0f to bell(hz(19), 0.16f, 0.5f, 0.35f),
                0.055f to bell(hz(26), 0.26f, 0.55f, 0.5f))
            S.EAT -> mix(0.60f,
                0f to tone(240f, 150f, 0.10f, SINE, 0.5f, decay = 1.5f),
                0.13f to tone(220f, 140f, 0.10f, SINE, 0.5f, decay = 1.5f),
                0.26f to tone(230f, 120f, 0.12f, SINE, 0.5f, decay = 1.5f),
                0.02f to tone(520f, 520f, 0.05f, NOISE, 0.10f, decay = 2f, lp = 1100f),
                0.15f to tone(500f, 500f, 0.05f, NOISE, 0.10f, decay = 2f, lp = 1100f))
            S.CAN_IN -> mix(0.40f,
                0f to tone(180f, 82f, 0.22f, TRI, 0.55f, decay = 1.4f, lp = 1800f),
                0.02f to tone(540f, 260f, 0.14f, SINE, 0.2f, decay = 2f))
            S.CAN_FIRE -> mix(0.80f,
                0f to tone(420f, 420f, 0.55f, NOISE, 0.42f, decay = 2.4f, lp = 1500f, lpTo = 320f),
                0f to tone(150f, 36f, 0.55f, SINE, 0.8f, decay = 1.6f))
            S.SHIELD -> bellArp(intArrayOf(3, 7, 10, 15), 0.05f, 0.42f, 0.5f)
            S.SMASH -> mix(0.30f,
                0f to tone(720f, 720f, 0.16f, NOISE, 0.4f, decay = 2.6f, lp = 2800f, lpTo = 800f),
                0f to tone(900f, 180f, 0.18f, TRI, 0.26f, decay = 2f))
            S.DEATH -> mix(0.90f,
                0f to tone(520f, 82f, 0.75f, SAW, 0.3f, decay = 1.3f, vibrato = 6f, lp = 2600f, lpTo = 600f),
                0f to tone(300f, 300f, 0.45f, NOISE, 0.16f, decay = 2f, lp = 900f))
            S.RECORD -> bellArp(intArrayOf(3, 7, 10, 15, 19, 22), 0.08f, 0.4f, 0.45f)
            S.CLICK -> tone(880f, 720f, 0.045f, SINE, 0.3f, decay = 2.6f)
            S.BUY -> bellArp(intArrayOf(7, 14), 0.06f, 0.45f, 0.35f)
            S.LEVEL -> bellArp(intArrayOf(0, 4, 7, 12, 16, 19, 24), 0.06f, 0.4f, 0.5f)
            S.BREED -> mix(0.70f,
                0f to tone(hz(19), hz(19), 0.14f, SINE, 0.5f, vibrato = 16f),
                0.15f to tone(hz(23), hz(23), 0.14f, SINE, 0.5f, vibrato = 16f),
                0.30f to tone(hz(26), hz(26), 0.32f, SINE, 0.5f, vibrato = 16f))
            S.BUMP -> tone(220f, 700f, 0.22f, SINE, 0.6f, decay = 1.4f, vibrato = 12f)
            S.LASER -> tone(1300f, 880f, 0.09f, TRI, 0.22f, decay = 2.2f, lp = 4200f)
            S.HOP -> tone(340f, 520f, 0.10f, TRI, 0.45f, decay = 1.4f)
            S.BOSS_ROAR -> mix(1.10f,
                0f to tone(120f, 58f, 1.0f, SAW, 0.3f, decay = 1.1f, vibrato = 18f, lp = 720f),
                0f to tone(220f, 220f, 0.9f, NOISE, 0.2f, decay = 1.7f, lp = 780f))
            S.BOSS_HIT -> mix(0.50f,
                0f to tone(280f, 80f, 0.3f, SINE, 0.7f, decay = 1.8f),
                0f to tone(500f, 500f, 0.1f, NOISE, 0.28f, decay = 3f, lp = 2400f),
                0.05f to tone(150f, 420f, 0.2f, TRI, 0.34f, decay = 2f))
            S.BOSS_DEAD -> mix(1.80f,
                0f to bellArp(intArrayOf(19, 15, 10, 7, 3), 0.11f, 0.34f, 0.5f),
                0.7f to bellArp(intArrayOf(0, 4, 7, 12, 16, 19, 24, 28), 0.07f, 0.34f, 0.5f))
            S.BOX -> mix(1.7f, *Array(10) { i ->
                val at = 1.5f * (i / 10f) * (i / 10f) + i * 0.02f
                at to tone(140f - i * 4f, 70f, 0.1f, SINE, 0.45f + i * 0.035f, decay = 2f)
            })
            S.REVEAL_C -> bellArp(intArrayOf(0, 7, 12), 0.07f, 0.4f, 0.4f)
            S.REVEAL_R -> bellArp(intArrayOf(0, 4, 7, 12, 16), 0.065f, 0.4f, 0.45f)
            S.REVEAL_E -> bellArp(intArrayOf(0, 4, 7, 12, 16, 19, 24), 0.06f, 0.4f, 0.5f)
            S.REVEAL_L -> mix(2.2f,
                0f to bellArp(intArrayOf(0, 4, 7, 12, 16, 19, 24, 28, 31), 0.055f, 0.4f, 0.5f),
                0.55f to bellArp(intArrayOf(0, 7, 12, 19), 0.1f, 0.3f, 0.7f))
            S.THROW -> tone(220f, 820f, 0.22f, TRI, 0.3f, decay = 1.4f, lp = 2600f)
            S.SLIP -> mix(0.30f,
                0f to tone(700f, 700f, 0.16f, NOISE, 0.2f, decay = 2f, lp = 2600f, lpTo = 900f),
                0.01f to tone(900f, 240f, 0.18f, TRI, 0.2f, decay = 2f))
            S.WARN -> mix(0.5f,
                0f to bell(hz(12), 0.12f, 0.42f, 0.3f),
                0.18f to bell(hz(12), 0.12f, 0.42f, 0.3f))
        }
    }
}

/**
 * Бесконечная музыка: Am7 – Fmaj7 – Cmaj7 – G6 в спокойном темпе, с пэдом, басом,
 * арпеджио, мелодией и мягкими барабанами. Генерируется один раз и крутится по кругу.
 */
class Music {
    private var track: AudioTrack? = null
    private var wantPlay = false

    fun start() {
        Thread {
            try {
                val data = Synth.pcm(compose(), 0.82f)
                val t = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Synth.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    data.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
                t.write(data, 0, data.size)
                t.setLoopPoints(0, data.size, -1)
                t.setVolume(0.5f)
                synchronized(this) {
                    track = t
                    if (wantPlay) t.play()
                }
            } catch (_: Exception) {
                // музыка не критична
            }
        }.start()
    }

    @Synchronized
    fun setPlaying(on: Boolean) {
        wantPlay = on
        try {
            val t = track ?: return
            if (on) t.play() else t.pause()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun release() {
        wantPlay = false
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    private fun compose(): FloatArray = with(Synth) {
        val beat = 60f / 108f
        val bar = beat * 4f
        val eighth = beat / 2f
        val sixteenth = beat / 4f
        val bars = 12
        val len = bar * bars
        val dry = FloatArray((len * SR).toInt() + SR)
        val wet = FloatArray(dry.size)

        // Аккорды: A m7, F maj7, C maj7, G6 (тоны — полутоны от A4).
        val chords = arrayOf(
            intArrayOf(0, 3, 7, 10),
            intArrayOf(-4, 0, 4, 7),
            intArrayOf(3, 7, 10, 14),
            intArrayOf(-2, 2, 5, 9),
        )
        val roots = intArrayOf(0, -4, 3, -2)
        // Порядок по тактам: A A F C | G G ... (12 тактов, чуть разный во второй половине).
        val order = intArrayOf(0, 1, 2, 3, 0, 1, 2, 3, 1, 3, 0, 0)

        // Мелодия (A-минорная пентатоника), по восьмым: индекс в scale или -1 (пауза).
        val scale = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17)
        val phraseA = intArrayOf(
            0, -1, 2, -1, 3, -1, 2, 0,  2, -1, -1, -1, 4, -1, 3, -1,
            5, -1, 4, -1, 3, -1, 2, -1,  3, 2, 1, -1, 0, -1, -1, -1,
        )
        val phraseB = intArrayOf(
            5, -1, 5, -1, 6, -1, 5, 4,  3, -1, 4, -1, 5, -1, -1, -1,
            7, -1, 6, -1, 5, -1, 4, -1,  3, -1, 2, -1, 0, -1, -1, -1,
        )

        for (b in 0 until bars) {
            val ci = order[b]
            val chord = chords[ci]
            val root = roots[ci]
            val at = b * bar

            // Пэд (тихий, тёплый) — держит гармонию.
            addAt(wet, at, pad(FloatArray(chord.size) { hz(chord[it] - 12) }, bar + 0.35f, 0.16f, 0.3f, 0.5f, 2200f))

            // Бас — качается на сильных долях.
            val r = root - 24
            addAt(dry, at + 0f * beat, tone(hz(r), hz(r), beat * 1.9f, TRI, 0.5f, decay = 1.1f))
            addAt(dry, at + 1.5f * beat, tone(hz(r + 7), hz(r + 7), beat * 0.5f, TRI, 0.32f, decay = 1.7f))
            addAt(dry, at + 2f * beat, tone(hz(r), hz(r), beat * 1.9f, TRI, 0.5f, decay = 1.1f))
            addAt(dry, at + 3.5f * beat, tone(hz(r + 12), hz(r + 12), beat * 0.5f, TRI, 0.3f, decay = 1.7f))

            // Арпеджио по шестнадцатым — мягкое, тихое.
            for (s in 0 until 16) {
                val n = chord[s % chord.size] + 12
                addAt(wet, at + s * sixteenth, tone(hz(n), hz(n), sixteenth * 1.8f, SINE, 0.085f, decay = 2.6f))
            }

            // Мелодия.
            val phrase = if (b < 8) phraseA else phraseB
            val pb = if (b < 8) b % 4 else (b - 8) % 4
            for (e in 0 until 8) {
                val m = phrase[pb * 8 + e]
                if (m >= 0) {
                    val n = scale[m] + 12
                    addAt(wet, at + e * eighth, tone(hz(n), hz(n), eighth * 1.7f, TRI, 0.16f, decay = 1.8f, attack = 0.02f, vibrato = 5f))
                }
            }

            // Барабаны — тихие и мягкие.
            addAt(dry, at + 0f * beat, kick(0.18f, 0.7f))
            addAt(dry, at + 2f * beat, kick(0.18f, 0.62f))
            addAt(dry, at + 1f * beat, snare(0.2f, 0.26f))
            addAt(dry, at + 3f * beat, snare(0.2f, 0.26f))
            for (e in 0 until 8) addAt(dry, at + e * eighth, hat(0.05f, if (e % 2 == 0) 0.13f else 0.08f))
        }

        val rev = reverb(wet, 0.28f)
        val out = FloatArray(dry.size)
        for (i in out.indices) out[i] = dry[i] + rev[i]
        // Лёгкий верхний срез, чтобы убрать резкость.
        var y = 0f
        val a = 1f - exp(-TWO_PI * 9000f / SR)
        for (i in out.indices) {
            y += a * (out[i] - y)
            out[i] = y
        }
        out
    }
}

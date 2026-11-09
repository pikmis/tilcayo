// Мини-реализация android.media поверх javax.sound.sampled. Звук в игре синтезируется кодом,
// поэтому мы просто проигрываем готовый PCM: SoundPool — короткие эффекты, AudioTrack — музыку.
package android.media

import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

class AudioAttributes private constructor(val usage: Int, val contentType: Int) {
    class Builder {
        private var usage: Int = USAGE_MEDIA
        private var contentType: Int = CONTENT_TYPE_UNKNOWN
        fun setUsage(value: Int) = apply { usage = value }
        fun setContentType(value: Int) = apply { contentType = value }
        fun build(): AudioAttributes = AudioAttributes(usage, contentType)
    }

    companion object {
        const val USAGE_MEDIA = 1
        const val USAGE_GAME = 14
        const val CONTENT_TYPE_UNKNOWN = 0
        const val CONTENT_TYPE_MUSIC = 2
        const val CONTENT_TYPE_SONIFICATION = 4
    }
}

class AudioFormat private constructor(val encoding: Int, val sampleRate: Int, val channelMask: Int) {
    class Builder {
        private var encoding: Int = ENCODING_PCM_16BIT
        private var sampleRate: Int = 44100
        private var channelMask: Int = CHANNEL_OUT_MONO
        fun setEncoding(value: Int) = apply { encoding = value }
        fun setSampleRate(value: Int) = apply { sampleRate = value }
        fun setChannelMask(value: Int) = apply { channelMask = value }
        fun build(): AudioFormat = AudioFormat(encoding, sampleRate, channelMask)
    }

    companion object {
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_8BIT = 3
        const val CHANNEL_OUT_MONO = 0x4
        const val CHANNEL_OUT_STEREO = 0xC
        const val CHANNEL_IN_MONO = 0x10
    }
}

object AudioManager {
    const val AUDIO_SESSION_ID_GENERATE = 0
    const val STREAM_MUSIC = 3
}

/** Проигрыватель длинного буфера (чиптюн-музыка) с циклом и громкостью. */
class AudioTrack(
    private val attributes: AudioAttributes,
    private val format: AudioFormat,
    private val bufferSizeInBytes: Int,
    private val mode: Int,
    private val sessionId: Int,
) {
    private var samples: ShortArray = ShortArray(0)
    @Volatile private var volume: Float = 1f
    @Volatile private var loopCount: Int = 0
    @Volatile private var running: Boolean = false
    private var thread: Thread? = null

    fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        samples = audioData.copyOfRange(offsetInShorts, offsetInShorts + sizeInShorts)
        return sizeInShorts
    }

    fun setLoopPoints(startInFrames: Int, endInFrames: Int, count: Int) { loopCount = count }
    fun setVolume(gain: Float) { volume = gain }

    fun play() {
        if (running) return
        running = true
        thread = Thread { stream() }.also { it.isDaemon = true; it.start() }
    }

    fun pause() { running = false }

    fun release() {
        running = false
        thread?.let { runCatching { it.join(400) } }
        thread = null
    }

    private fun stream() {
        try {
            val af = javax.sound.sampled.AudioFormat(format.sampleRate.toFloat(), 16, 1, true, false)
            val line: SourceDataLine = AudioSystem.getSourceDataLine(af)
            line.open(af, maxOf(bufferSizeInBytes, 8192))
            line.start()
            val out = ByteArray(1024)
            var passes = 0
            while (running) {
                var i = 0
                var o = 0
                while (i < samples.size && running) {
                    val v = (samples[i] * volume).toInt().coerceIn(-32768, 32767)
                    out[o++] = (v and 0xFF).toByte()
                    out[o++] = ((v shr 8) and 0xFF).toByte()
                    i++
                    if (o == out.size) { line.write(out, 0, o); o = 0 }
                }
                if (o > 0) line.write(out, 0, o)
                passes++
                if (loopCount >= 0 && passes > loopCount) break
            }
            runCatching { line.drain() }
            runCatching { line.stop() }
            runCatching { line.close() }
        } catch (_: Exception) {
            // без звуковой карты игра работает молча
        }
    }

    companion object {
        const val MODE_STATIC = 0
        const val MODE_STREAM = 1
    }
}

/** Пул коротких эффектов: WAV грузится в память, каждый play проигрывается на своём потоке. */
class SoundPool private constructor(private val maxStreams: Int, private val attributes: AudioAttributes) {
    class Builder {
        private var maxStreams: Int = 1
        private var attributes: AudioAttributes? = null
        fun setMaxStreams(value: Int) = apply { maxStreams = value }
        fun setAudioAttributes(value: AudioAttributes) = apply { attributes = value }
        fun build(): SoundPool = SoundPool(maxStreams, attributes ?: AudioAttributes.Builder().build())
    }

    private val nextId = AtomicInteger(1)
    private val sounds = HashMap<Int, FloatArray>()
    private val sampleRates = HashMap<Int, Int>()

    fun load(path: String, priority: Int): Int {
        val id = nextId.getAndIncrement()
        runCatching {
            val (pcm, sr) = decodeWav(File(path).readBytes())
            sounds[id] = pcm
            sampleRates[id] = sr
        }
        return id
    }

    fun play(soundID: Int, leftVolume: Float, rightVolume: Float, priority: Int, loop: Int, rate: Float): Int {
        val pcm = sounds[soundID] ?: return 0
        val sr = sampleRates[soundID] ?: 22050
        val vol = (leftVolume + rightVolume) / 2f
        Thread { playPcm(pcm, sr, vol, rate.coerceIn(0.5f, 2f)) }
            .also { it.isDaemon = true; it.start() }
        return 1
    }

    fun release() {
        sounds.clear()
        sampleRates.clear()
    }

    /** Разбирает WAV (16-bit PCM) в моно FloatArray + частоту дискретизации. */
    private fun decodeWav(bytes: ByteArray): Pair<FloatArray, Int> {
        val ais = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        val fmt = ais.format
        val channels = fmt.channels.coerceAtLeast(1)
        val sr = fmt.sampleRate.toInt()
        val raw = ByteArray(ais.available().coerceAtLeast(2))
        var read = 0
        while (read < raw.size) {
            val r = ais.read(raw, read, raw.size - read)
            if (r < 0) break
            read += r
        }
        ais.close()
        val frames = read / (2 * channels)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val idx = (i * channels + c) * 2
                if (idx + 1 >= read) break
                val s = ((raw[idx + 1].toInt() shl 8) or (raw[idx].toInt() and 0xFF)).toShort().toInt()
                acc += s / 32768f
            }
            mono[i] = acc / channels
        }
        return mono to sr
    }

    private fun playPcm(pcm: FloatArray, sampleRate: Int, volume: Float, rate: Float) {
        try {
            val af = javax.sound.sampled.AudioFormat(sampleRate * rate, 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(af)
            line.open(af, 4096)
            line.start()
            val out = ByteArray(1024)
            var o = 0
            for (i in pcm.indices) {
                val v = (pcm[i] * volume * 32767f).toInt().coerceIn(-32768, 32767)
                out[o++] = (v and 0xFF).toByte()
                out[o++] = ((v shr 8) and 0xFF).toByte()
                if (o == out.size) { line.write(out, 0, o); o = 0 }
            }
            if (o > 0) line.write(out, 0, o)
            line.drain()
            line.stop()
            line.close()
        } catch (_: Exception) {
        }
    }
}

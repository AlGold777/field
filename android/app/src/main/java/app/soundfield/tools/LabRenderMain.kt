package app.soundfield.tools

import app.soundfield.core.AudioSource
import app.soundfield.core.Ch
import app.soundfield.core.Pcm24
import app.soundfield.core.Pcm24StereoEncoder
import app.soundfield.core.ServerPlayState
import app.soundfield.core.ServerRenderer
import app.soundfield.core.SF
import app.soundfield.core.SourceMode
import app.soundfield.core.Timeline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Лаборатория sflab, сторона Android: прогоняет входные WAV (float32 mono, 48 кГц) через тот же ServerRenderer,
 * что и приложение (корректор + sinc, нейтральный EQ, усиление 1, финальный true-peak лимитер ядра), и пишет name.out.wav.
 * Два варианта источника: pcm24 — как реальный кэш приложения (float → 24 бит с TPDF-дизерингом ядра → float), float — тот же
 * тракт без квантования источника. Вход создаёт `sflab gen-signals`, результат читает `sflab run --kotlin`.
 */
object LabRenderMain {
    private const val BLOCK = 480

    private class FloatSource(private val x: FloatArray) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "lab"
        override val artist = "lab"
        override val totalFrames = x.size.toLong()
        override fun sample(frame: Long, ch: Int): Float = if (frame < 0 || frame >= totalFrames) 0f else x[frame.toInt()]
    }

    /** Кэш приложения: интерлив L/R → 24 бит с TPDF (Pcm24StereoEncoder, как PcmSegmentWriter) → чтение как SegmentedPcmSource. */
    private class Pcm24Source(x: FloatArray) : AudioSource {
        private val bytes = ByteArray(x.size * 6)
        init {
            val stereo = FloatArray(x.size * 2)
            for (i in x.indices) { stereo[i * 2] = x[i]; stereo[i * 2 + 1] = x[i] }
            Pcm24StereoEncoder(seed = 0x5F24).use { it.encode(stereo, x.size, bytes) }
        }
        private val buf = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        override val mode = SourceMode.MUSIC
        override val title = "lab"
        override val artist = "lab"
        override val totalFrames = x.size.toLong()
        override fun sample(frame: Long, ch: Int): Float =
            if (frame < 0 || frame >= totalFrames) 0f else Pcm24.readLe(buf, (frame * 6 + ch * 3).toInt())
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 2 && args[0].isNotEmpty() && args[1].isNotEmpty()) { "usage: -PlabIn=DIR -PlabOut=DIR" }
        val inDir = File(args[0]).canonicalFile
        val outDir = File(args[1]).canonicalFile
        val inputs = inDir.listFiles { f -> f.name.endsWith(".wav") && !f.name.endsWith(".out.wav") }!!.sortedBy { it.name }
        for (variant in listOf("pcm24", "float")) {
            val dir = File(outDir, variant).also { it.mkdirs() }
            for (file in inputs) {
                val x = readFloatWav(file)
                val source: AudioSource = if (variant == "pcm24") Pcm24Source(x) else FloatSource(x)
                writeFloatWav(File(dir, file.nameWithoutExtension + ".out.wav"), render(source))
            }
            println("lab render $variant: ${inputs.size} files -> $dir")
        }
    }

    private fun render(source: AudioSource): FloatArray {
        val timeline = Timeline(epoch = 1, playing = true, anchorFrame = -SF.SAMPLE_RATE.toLong(), anchorNs = -1_000_000_000L)
        val state = ServerPlayState(timeline, source, Ch.L, 1f)
        val renderer = ServerRenderer { state }
        val total = source.totalFrames.toInt()
        val out = FloatArray(total)
        val block = FloatArray(BLOCK)
        var frame = 0
        while (frame < total) {
            val n = minOf(BLOCK, total - frame)
            renderer.render(block, n, (frame.toLong() * 1_000_000_000L) / SF.SAMPLE_RATE)
            System.arraycopy(block, 0, out, frame, n)
            frame += n
        }
        return out
    }

    private fun readFloatWav(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.capacity()) {
            val id = String(ByteArray(4) { b.get(pos + it) })
            val len = b.getInt(pos + 4)
            if (id == "data") {
                val n = len / 4
                return FloatArray(n) { b.getFloat(pos + 8 + it * 4) }
            }
            pos += 8 + len + (len and 1)
        }
        error("no data chunk in $file")
    }

    private fun writeFloatWav(file: File, x: FloatArray) {
        val data = x.size * 4
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVEfmt ".toByteArray()).putInt(16)
        b.putShort(3).putShort(1).putInt(SF.SAMPLE_RATE).putInt(SF.SAMPLE_RATE * 4).putShort(4).putShort(32)
        b.put("data".toByteArray()).putInt(data)
        for (v in x) b.putFloat(v)
        file.writeBytes(b.array())
    }
}

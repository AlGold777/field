package app.soundfield.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import app.soundfield.core.AudioSource
import app.soundfield.core.MappedPcmSource
import app.soundfield.core.StreamResampler
import app.soundfield.core.SF
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Трек очереди — ссылка на исходный файл (content:// с постоянным доступом), без копии внутри приложения.
 * Благодаря этому очередь можно сохранять как плейлист и открывать после перезапуска.
 */
data class PreparedAudioFile(val id: String, val uri: Uri, val title: String, val artist: String)

/** Метаданные и декодирование треков в формат движка: 48 кГц, стерео. */
object AudioFileDecoder {
    /** Читает метаданные и проверяет, что файл открывается. Название без расширения, если тега нет. */
    fun prepare(context: Context, uri: Uri, displayName: String): PreparedAudioFile {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                ?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.', displayName)
            val artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim().orEmpty()
            return PreparedAudioFile(UUID.randomUUID().toString(), uri, title, artist)
        } finally { retriever.release() }
    }

    /** Файл доступен (не удалён, доступ не отозван). */
    fun isReadable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) { false }

    private fun pcmDir(context: Context) = File(context.filesDir, "audio_pcm").apply { mkdirs() }

    /** Удалить все декодированные файлы (при запуске приложения). */
    fun clearCache(context: Context) {
        pcmDir(context).listFiles()?.forEach { it.delete() }
        File(context.filesDir, "audio_queue").listFiles()?.forEach { it.delete() }   // копии из версий ≤ 1.2.0
    }

    /** Файл с декодированным PCM (48 кГц, стерео, int16). */
    fun pcmFile(context: Context, track: PreparedAudioFile) = File(pcmDir(context), "${track.id}.pcm48")

    /**
     * Декодирует трек потоково прямо в файл (без накопления в памяти) с качественным ресемплингом
     * и возвращает источник, отображённый в память. Повторный вызов берёт готовый файл.
     * [cancelled] позволяет прервать фоновую подготовку следующего трека.
     */
    fun decode(context: Context, track: PreparedAudioFile, cancelled: () -> Boolean = { false }): AudioSource {
        val out = pcmFile(context, track)
        if (out.length() >= 4) return MappedPcmSource(out, track.title, track.artist)
        val tmp = File(out.path + ".tmp")
        try {
            decodeToFile(context, track, tmp, cancelled)
            check(tmp.renameTo(out)) { "Не удалось сохранить декодированный трек" }
        } finally { tmp.delete() }
        return MappedPcmSource(out, track.title, track.artist)
    }

    private class CancelledException : RuntimeException("Подготовка трека отменена")

    private fun decodeToFile(context: Context, track: PreparedAudioFile, target: File, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, track.uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный аудиоформат")
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            val sink = BufferedOutputStream(FileOutputStream(target), 1 shl 16)
            var framesOut = 0L
            val bytes = ByteArray(4)
            fun write(l: Float, r: Float) {
                val a = (l * 32767f).roundToInt().coerceIn(-32768, 32767)
                val b = (r * 32767f).roundToInt().coerceIn(-32768, 32767)
                bytes[0] = a.toByte(); bytes[1] = (a shr 8).toByte(); bytes[2] = b.toByte(); bytes[3] = (b shr 8).toByte()
                sink.write(bytes, 0, 4); framesOut++
            }
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var resampler: StreamResampler? = null
                var resamplerRate = -1
                var lastProgressAt = SystemClock.elapsedRealtime()
                while (!outputEnded) {
                    if (cancelled()) throw CancelledException()
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: error("Не удалось прочитать аудиоданные")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                                lastProgressAt = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            lastProgressAt = SystemClock.elapsedRealtime()
                            if (info.size > 0) {
                                require(sampleRate > 0 && channels in 1..8) { "Неподдерживаемые параметры аудио" }
                                if (resamplerRate != sampleRate) {
                                    resampler?.finish()
                                    resamplerRate = sampleRate
                                    resampler = if (sampleRate == SF.SAMPLE_RATE) null
                                        else StreamResampler(sampleRate, SF.SAMPLE_RATE, ::write)
                                }
                                val buffer = codec.getOutputBuffer(outputIndex) ?: error("Пустой аудиобуфер")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.order(ByteOrder.LITTLE_ENDIAN)
                                val bps = when (encoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> 2
                                    AudioFormat.ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> 4
                                    ENCODING_PCM_24BIT_PACKED -> 3
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    else -> error("Кодек вернул неподдерживаемый PCM формат ($encoding)")
                                }
                                val frames = info.size / (bps * channels)
                                val rs = resampler
                                for (fr in 0 until frames) {
                                    val l = readSample(buffer, encoding)
                                    val r = if (channels >= 2) readSample(buffer, encoding) else l
                                    for (c in 2 until channels) readSample(buffer, encoding)   // остальные каналы пропускаем
                                    if (rs != null) rs.push(l, r) else write(l, r)
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                    check(SystemClock.elapsedRealtime() - lastProgressAt < 30_000) { "Декодер аудио не отвечает" }
                }
                resampler?.finish()
                sink.flush()
                require(framesOut > 0) { "В файле нет аудиоданных" }
            } finally {
                try { sink.close() } catch (_: Exception) {}
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
        } finally { extractor.release() }
    }

    private fun readSample(b: java.nio.ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> b.short / 32768f
        AudioFormat.ENCODING_PCM_FLOAT -> b.float
        ENCODING_PCM_32BIT -> b.int / 2147483648f
        ENCODING_PCM_24BIT_PACKED -> {
            val lo = b.get().toInt() and 0xFF; val mid = b.get().toInt() and 0xFF; val hi = b.get().toInt()
            ((hi shl 16) or (mid shl 8) or lo) / 8388608f
        }
        AudioFormat.ENCODING_PCM_8BIT -> ((b.get().toInt() and 0xFF) - 128) / 128f
        else -> 0f
    }

    // Константы API 31 — объявлены локально, чтобы код компилировался и работал на minSdk 26.
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22
}

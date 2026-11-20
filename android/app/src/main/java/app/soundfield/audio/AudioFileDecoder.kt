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
import app.soundfield.core.SegmentedPcmSource
import app.soundfield.core.Pcm24StereoEncoder
import app.soundfield.core.SampleRateConverter
import app.soundfield.core.SF
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.UUID

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
        pcmDir(context).listFiles()?.forEach { it.deleteRecursively() }
        File(context.filesDir, "audio_queue").listFiles()?.forEach { it.deleteRecursively() }   // копии из версий ≤ 1.2.0
    }

    /** Каталог с декодированным PCM: 48 кГц, стерео, 24 бит little-endian сегментами `*.pcm24` (A1.1). */
    fun pcmFile(context: Context, track: PreparedAudioFile) = File(pcmDir(context), track.id)

    /**
     * Декодирует трек потоково в сегменты с качественным ресемплингом. [onReady] вызывается после
     * появления первого воспроизводимого сегмента, а декодирование затем продолжается в фоне.
     * [cancelled] позволяет прервать фоновую подготовку следующего трека.
     */
    fun decode(
        context: Context,
        track: PreparedAudioFile,
        cancelled: () -> Boolean = { false },
        onReady: (SegmentedPcmSource) -> Unit = {},
        onProgress: (SegmentedPcmSource) -> Unit = {},
    ): AudioSource {
        val directory = pcmFile(context, track)
        val completeFile = File(directory, "complete")
        if (completeFile.exists()) {
            // Метка формата «v3 <кадры>»: кэш PCM16 прежних версий (сегменты *.pcm48, метка без префикса) перекодируется заново.
            val info = parseComplete(completeFile)
            if (info != null) {
                val source = SegmentedPcmSource(directory, track.title, track.artist, info.frames)
                loadSegments(source, directory)
                source.truePeakDbtp = info.truePeakDbtp
                source.loudnessLufs = info.loudnessLufs
                source.finish(info.frames)
                return source
            }
        }
        directory.deleteRecursively()
        directory.mkdirs()
        var notified = false
        try {
            val source = decodeToFile(context, track, directory, cancelled, onProgress) { readySource ->
                if (!notified) { notified = true; onReady(readySource) }
            }
            File(directory, "complete.tmp").writeText("$CACHE_FORMAT ${source.totalFrames} tp=${source.truePeakDbtp} lufs=${source.loudnessLufs}")
            check(File(directory, "complete.tmp").renameTo(completeFile)) { "Не удалось завершить декодирование" }
            onProgress(source)
            return source
        } catch (e: Throwable) {
            directory.deleteRecursively()
            throw e
        }
    }

    /** Метаданные завершённого кэша; [truePeakDbtp] — NaN, если в метке его нет. [loudnessLufs] — NaN, если нет `lufs=`. */
    internal class CacheInfo(val frames: Long, val truePeakDbtp: Double, val loudnessLufs: Double = Double.NaN)

    /** Метка кэша или null, если это кэш другого формата (PCM16 до A1) — его нужно перекодировать. Формат: `v3 <кадры> [tp=<dBTP>] [lufs=<LUFS>]`. */
    internal fun parseComplete(file: File): CacheInfo? {
        val parts = runCatching { file.readText().trim().split(' ') }.getOrNull() ?: return null
        if (parts.size !in 2..4 || parts[0] != CACHE_FORMAT) return null
        val frames = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val tp = parts.drop(2).firstOrNull { it.startsWith("tp=") }?.substring(3)?.toDoubleOrNull() ?: Double.NaN
        val lufs = parts.drop(2).firstOrNull { it.startsWith("lufs=") }?.substring(5)?.toDoubleOrNull() ?: Double.NaN
        return CacheInfo(frames, tp, lufs)
    }

    private fun loadSegments(source: SegmentedPcmSource, directory: File) {
        directory.listFiles { f -> f.extension == "pcm24" }.orEmpty().sortedBy { it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE }
            .forEach { f ->
                val index = f.nameWithoutExtension.toLongOrNull() ?: return@forEach
                val frames = (f.length() / SegmentedPcmSource.BYTES_PER_FRAME).toInt().coerceIn(1, SegmentedPcmSource.SEGMENT_FRAMES)
                source.publish(index, f, frames)
            }
    }

    private class CancelledException : RuntimeException("Подготовка трека отменена")

    private fun decodeToFile(
        context: Context,
        track: PreparedAudioFile,
        directory: File,
        cancelled: () -> Boolean,
        onProgress: (SegmentedPcmSource) -> Unit,
        onReady: (SegmentedPcmSource) -> Unit,
    ): SegmentedPcmSource {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, track.uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный аудиоформат")
            var durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, track.uri)
                    durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1_000L) ?: 0L
                } catch (_: Exception) {
                    // Декодирование всё равно может работать: длина будет расти по мере готовности сегментов.
                } finally { retriever.release() }
            }
            val estimatedFrames = if (durationUs > 0) durationUs * SF.SAMPLE_RATE / 1_000_000 else 0L
            val source = SegmentedPcmSource(directory, track.title, track.artist, estimatedFrames)
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            val sink = PcmSegmentWriter(directory, source, onProgress, onReady)
            var converter: SampleRateConverter? = null
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var converterRate = -1
                val inFrames = BLOCK_FRAMES
                val inBlock = FloatArray(inFrames * 2)
                var inCount = 0
                var outBlock = FloatArray(0)
                // Накопленный интерлив → ресемплер ядра → запись 24 бит. final=true дописывает хвост ресемплера.
                fun flushInput(final: Boolean) {
                    val c = converter ?: return
                    if (outBlock.size < (c.maxOutputFrames(maxOf(inCount, BLOCK_FRAMES)) + 16) * 2)
                        outBlock = FloatArray((c.maxOutputFrames(maxOf(inCount, BLOCK_FRAMES)) + 16) * 2)
                    if (inCount > 0) { sink.write(outBlock, c.process(inBlock, inCount, outBlock)); inCount = 0 }
                    if (final) sink.write(outBlock, c.finish(outBlock))
                }
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
                                if (converterRate != sampleRate) {
                                    flushInput(final = true)
                                    converter?.close()
                                    converterRate = sampleRate
                                    converter = SampleRateConverter(sampleRate, SF.SAMPLE_RATE)
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
                                for (fr in 0 until frames) {
                                    var l = readSample(buffer, encoding)
                                    var r = if (channels >= 2) readSample(buffer, encoding) else l
                                    if (channels == 6) {
                                        // 5.1 (FL FR FC LFE SL SR): даунмикс ITU-R BS.775 без LFE, с нормировкой
                                        // против клиппинга. Раньше центр (диалоги) и тылы терялись.
                                        val c = readSample(buffer, encoding)
                                        readSample(buffer, encoding)   // LFE
                                        val sl = readSample(buffer, encoding)
                                        val sr = readSample(buffer, encoding)
                                        l = ((l + DOWNMIX_SIDE * (c + sl)) * DOWNMIX_NORM).toFloat()
                                        r = ((r + DOWNMIX_SIDE * (c + sr)) * DOWNMIX_NORM).toFloat()
                                    } else for (c in 2 until channels) readSample(buffer, encoding)   // прочие раскладки: L и R
                                    inBlock[inCount * 2] = l; inBlock[inCount * 2 + 1] = r
                                    if (++inCount == inFrames) flushInput(final = false)
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                    check(SystemClock.elapsedRealtime() - lastProgressAt < 30_000) { "Декодер аудио не отвечает" }
                }
                flushInput(final = true)
                sink.finish()
                sink.publishPeak()
                require(sink.framesOut > 0) { "В файле нет аудиоданных" }
                source.finish(sink.framesOut)
                return source
            } finally {
                // SRC закрываем и при отмене или ошибке кодека: иначе нативный хендл утекал.
                try { converter?.close() } catch (_: Exception) {}
                converter = null
                try { sink.close() } catch (_: Exception) {}
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
        } finally { extractor.release() }
    }

    /** Даунмикс 5.1: вес центра и тылов −3 дБ; нормировка 1/(1+2·0,7071) исключает клиппинг. */
    private const val DOWNMIX_SIDE = 0.70710678
    private const val DOWNMIX_NORM = 1.0 / (1.0 + 2.0 * DOWNMIX_SIDE)

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

    /** Формат кэша: PCM 24 бит little-endian, `*.pcm24`, метка завершения «v3 <кадры>». */
    private const val CACHE_FORMAT = "v3"
    private const val BLOCK_FRAMES = 4096

    private class PcmSegmentWriter(
        private val directory: File,
        private val source: SegmentedPcmSource,
        private val onProgress: (SegmentedPcmSource) -> Unit,
        private val onReady: (SegmentedPcmSource) -> Unit,
    ) {
        private val readyThreshold = minOf(source.totalFrames, SegmentedPcmSource.SEGMENT_FRAMES.toLong())
        private val encoder = Pcm24StereoEncoder(seed = 0x5F24)
        private val meter = app.soundfield.dsp.TruePeakMeter()
        private var bytes = ByteArray(0)
        private var index = 0L
        private var segmentFrames = 0
        private var output: BufferedOutputStream? = null
        private var notifiedReady = false
        var framesOut = 0L
            private set

        /** [frames] кадров интерлива L/R (float) → 24 бит с TPDF-дизерингом, сегментами по 5 с. */
        fun write(block: FloatArray, frames: Int) {
            var done = 0
            while (done < frames) {
                val n = minOf(frames - done, SegmentedPcmSource.SEGMENT_FRAMES - segmentFrames)
                if (bytes.size < n * 6) bytes = ByteArray(n * 6)
                val part = if (done == 0 && n == frames) block else block.copyOfRange(done * 2, (done + n) * 2)
                meter.process(part, n)
                encoder.encode(part, n, bytes)
                if (output == null) output = BufferedOutputStream(FileOutputStream(File(directory, "$index.tmp")), 1 shl 16)
                output!!.write(bytes, 0, n * 6)
                segmentFrames += n; framesOut += n; done += n
                if (segmentFrames == SegmentedPcmSource.SEGMENT_FRAMES) publishSegment()
            }
        }

        fun finish() { if (segmentFrames > 0) publishSegment() }

        /** Итоговый true-peak трека (последний сегмент мог быть пуст). */
        fun publishPeak() { source.truePeakDbtp = meter.truePeakDbtp; source.loudnessLufs = meter.loudnessLufs }

        fun close() { try { output?.close() } finally { output = null; encoder.close(); meter.close() } }

        private fun publishSegment() {
            val stream = output ?: return
            stream.flush(); stream.close(); output = null
            val tmp = File(directory, "$index.tmp")
            val segment = File(directory, "$index.pcm24")
            check(tmp.renameTo(segment)) { "Не удалось сохранить сегмент аудио" }
            source.truePeakDbtp = meter.truePeakDbtp
            source.publish(index, segment, segmentFrames)
            segmentFrames = 0
            index++
            onProgress(source)
            if (!notifiedReady && source.readyFrames >= readyThreshold) {
                notifiedReady = true
                onReady(source)
            }
        }
    }
}

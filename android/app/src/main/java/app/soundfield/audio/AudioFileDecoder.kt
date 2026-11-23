package app.soundfield.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.soundfield.audio.mp3.ChannelByteSource
import app.soundfield.audio.mp3.Mp3FrameIndex
import app.soundfield.audio.mp3.Mp3FrameReader
import app.soundfield.audio.mp3.PREROLL_FRAMES
import app.soundfield.core.SegmentedPcmSource
import app.soundfield.core.Pcm24StereoEncoder
import app.soundfield.core.SampleRateConverter
import app.soundfield.core.SF
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Трек очереди — ссылка на исходный файл (content:// с постоянным доступом), без копии внутри приложения.
 * Благодаря этому очередь можно сохранять как плейлист и открывать после перезапуска.
 */
data class PreparedAudioFile(val id: String, val uri: Uri, val title: String, val artist: String)

/**
 * Метаданные и декодирование треков в формат движка: 48 кГц, стерео. Трек декодируется на лету
 * в скользящее окно в памяти ([SegmentedPcmSource]); на диск ничего не пишется.
 */
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

    /** Удалить PCM-кэш прежних версий (до 1.12 трек целиком декодировался на диск). */
    fun clearCache(context: Context) {
        File(context.filesDir, "audio_pcm").deleteRecursively()
        File(context.filesDir, "audio_queue").listFiles()?.forEach { it.deleteRecursively() }   // копии из версий ≤ 1.2.0
    }

    /**
     * Открывает трек: запускает поток декодера и возвращается, когда готов сегмент у [startFrame].
     * Дальше декодер держит окно вокруг позиции ([SegmentedPcmSource.focus]) до [SegmentedPcmSource.close].
     * [onError] — ошибка декодера после открытия (поток декодера). [cancelled] прерывает только открытие.
     */
    fun open(
        context: Context,
        track: PreparedAudioFile,
        startFrame: Long = 0L,
        cancelled: () -> Boolean = { false },
        onError: (Throwable) -> Unit = {},
    ): SegmentedPcmSource {
        val ready = CompletableFuture<SegmentedPcmSource>()
        val opened = java.util.concurrent.atomic.AtomicReference<SegmentedPcmSource?>()
        Thread({
            try {
                StreamDecoder(context, track, startFrame) { opened.set(it) }.use { decoder -> decoder.run(ready) }
            } catch (t: Throwable) {
                if (!ready.isDone) ready.completeExceptionally(t)
                else opened.get()?.let { s -> if (!s.closed) { s.fail(t); onError(t) } }
            }
        }, "sf-decode").apply { isDaemon = true; start() }
        while (true) {
            if (cancelled()) {
                opened.get()?.close()
                ready.thenAccept { it.close() }
                throw CancelledException()
            }
            try { return ready.get(50, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) {}
            catch (e: ExecutionException) { throw e.cause ?: e }
        }
    }

    /**
     * Последовательно декодирует весь файл в 48 кГц стерео float и отдаёт блоками в [consumer]
     * (анализ громкости и пика файла). Ничего не сохраняет.
     */
    fun scanPcm(context: Context, uri: Uri, cancelled: () -> Boolean, consumer: (FloatArray, Int) -> Unit) {
        ExtractorInput.open(context, uri).use { input ->
            PcmPipeline(input, consumer).use { pipeline ->
                while (!pipeline.ended) {
                    if (cancelled()) throw CancelledException()
                    pipeline.step()
                }
            }
        }
    }

    class CancelledException : RuntimeException("Подготовка трека отменена")

    /** MP3 — через собственный индекс кадров (точная перемотка и в VBR), остальное — через MediaExtractor. */
    private fun openInput(context: Context, uri: Uri): CompressedInput {
        val extractor = ExtractorInput.open(context, uri)
        if (extractor.mime != MediaFormat.MIMETYPE_AUDIO_MPEG) return extractor
        val mp3 = try { Mp3Input.open(context, uri, extractor.format) }
            catch (e: Exception) { Log.w("SoundField", "MP3 index failed, using MediaExtractor", e); null }
        if (mp3 == null) return extractor
        extractor.close()
        return mp3
    }

    /** Индекс кадров MP3 строится один раз на файл и хранится в `mp3_index` (≈3 байта на кадр). */
    private fun mp3Index(context: Context, uri: Uri, src: ChannelByteSource): Mp3FrameIndex? {
        val dir = File(context.filesDir, "mp3_index").apply { mkdirs() }
        val file = TrackIdentity.of(context, uri)?.let { File(dir, "${it.key}.idx") }
        file?.takeIf { it.isFile }?.let { cached ->
            runCatching { cached.inputStream().buffered().use { Mp3FrameIndex.read(it) } }.getOrNull()
                ?.takeIf { it.sourceSize == src.size }?.let { cached.setLastModified(System.currentTimeMillis()); return it }
        }
        val index = Mp3FrameIndex.build(src) ?: return null
        if (file != null) runCatching {
            val tmp = File(dir, "${file.name}.tmp")
            tmp.outputStream().buffered().use { index.write(it) }
            check(tmp.renameTo(file))
            dir.listFiles { f -> f.extension == "idx" }.orEmpty().sortedByDescending { it.lastModified() }
                .drop(MP3_INDEX_KEEP).forEach { it.delete() }
        }
        return index
    }

    private class Mp3Input(
        private val pfd: android.os.ParcelFileDescriptor,
        private val stream: java.io.FileInputStream,
        private val index: Mp3FrameIndex,
        src: ChannelByteSource,
        override val format: MediaFormat,
    ) : CompressedInput {
        private val reader = Mp3FrameReader(src, index)
        /** Задержка кодера из LAME-тега плюс стандартная задержка декодера MP3; без тега — 0, как у MediaExtractor. */
        private val delaySamples = if (index.encoderDelay > 0) index.encoderDelay + Mp3FrameIndex.DECODER_DELAY_SAMPLES else 0
        override val mime = MediaFormat.MIMETYPE_AUDIO_MPEG
        override val durationUs = index.totalSamples * 1_000_000L / index.sampleRate
        override val delayUs = delaySamples * 1_000_000L / index.sampleRate
        private var next = 0
        override var lastPtsUs = 0L
            private set

        override fun seekTo(targetUs: Long) {
            val sample = targetUs.coerceAtLeast(0L) * index.sampleRate / 1_000_000L
            val extra = delaySamples - index.encoderDelay
            next = (index.frameForSample(sample, extra) - PREROLL_FRAMES).coerceAtLeast(0)
        }

        override fun read(dst: ByteBuffer): Int {
            if (next >= index.frameCount) return -1
            val size = reader.read(next, dst)
            if (size < 0) error("Не удалось прочитать кадр MP3 $next")
            lastPtsUs = reader.presentationTimeUs(next)
            next++
            return size
        }

        override fun close() { try { stream.close() } finally { pfd.close() } }

        companion object {
            fun open(context: Context, uri: Uri, format: MediaFormat): Mp3Input? {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                val stream = java.io.FileInputStream(pfd.fileDescriptor)
                try {
                    val src = ChannelByteSource(stream.channel)
                    val index = mp3Index(context, uri, src) ?: run { stream.close(); pfd.close(); return null }
                    return Mp3Input(pfd, stream, index, src, format)
                } catch (t: Throwable) { stream.close(); pfd.close(); throw t }
            }
        }
    }

    /** Сжатые пакеты одной аудиодорожки. Позиционирование — по времени трека. */
    private interface CompressedInput : AutoCloseable {
        val format: MediaFormat
        val mime: String
        /** Длительность, мкс; 0 — неизвестна. */
        val durationUs: Long
        /** Задержка кодера, мкс: столько звука в начале потока не принадлежит треку. */
        val delayUs: Long get() = 0L
        /** Перейти так, чтобы первые пакеты начинались не позже [targetUs]. */
        fun seekTo(targetUs: Long)
        /** Следующий пакет в [dst]: размер или −1 — конец потока. Время пакета — [lastPtsUs]. */
        fun read(dst: ByteBuffer): Int
        val lastPtsUs: Long
    }

    private class ExtractorInput(private val extractor: MediaExtractor, override val format: MediaFormat,
                                 override val durationUs: Long) : CompressedInput {
        override val mime: String = format.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный аудиоформат")
        override var lastPtsUs = 0L
            private set
        override fun seekTo(targetUs: Long) =
            extractor.seekTo((targetUs - SEEK_PREROLL_US).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        override fun read(dst: ByteBuffer): Int {
            val size = extractor.readSampleData(dst, 0)
            if (size < 0) return -1
            lastPtsUs = extractor.sampleTime
            extractor.advance()
            return size
        }
        override fun close() = extractor.release()

        companion object {
            fun open(context: Context, uri: Uri): ExtractorInput {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: error("В файле нет аудиодорожки")
                    val format = extractor.getTrackFormat(trackIndex)
                    var durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                    if (durationUs <= 0) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1_000L) ?: 0L
                        } catch (_: Exception) {
                            // Декодирование всё равно может работать: длина будет расти по мере готовности сегментов.
                        } finally { retriever.release() }
                    }
                    extractor.selectTrack(trackIndex)
                    return ExtractorInput(extractor, format, durationUs.coerceAtLeast(0L))
                } catch (t: Throwable) { extractor.release(); throw t }
            }
        }
    }

    /**
     * Сжатый поток → MediaCodec → ресемплер ядра → 48 кГц стерео float, блоками в [emit].
     * Однопоточный: все вызовы — из одного потока. [seek] переходит к времени трека с точностью до кадра PCM.
     */
    private class PcmPipeline(private val input: CompressedInput, private val emit: (FloatArray, Int) -> Unit) : AutoCloseable {
        private val codec = MediaCodec.createDecoderByType(input.mime)
        private val info = MediaCodec.BufferInfo()
        private var inputEnded = false
        /** Конец потока: хвост ресемплера уже отдан. */
        var ended = false
            private set
        private var sawOutput = false
        private var sampleRate = input.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private var channels = input.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var converter: SampleRateConverter? = null
        private var converterRate = -1
        private val inBlock = FloatArray(BLOCK_FRAMES * 2)
        private var inCount = 0
        private var outBlock = FloatArray(0)
        /** Звук раньше этого времени трека отбрасывается (после перехода и задержка кодера в начале). */
        private var dropBeforeUs = 0L
        private var lastProgressAt = SystemClock.elapsedRealtime()

        init {
            try { codec.configure(input.format, null, null, 0); codec.start() }
            catch (t: Throwable) { codec.release(); throw t }
        }

        /** Поток стоял при полном окне: время простоя не считается зависанием кодека. */
        fun resetWatchdog() { lastProgressAt = SystemClock.elapsedRealtime() }

        fun seek(targetUs: Long) {
            inCount = 0
            closeConverter()
            if (sawOutput) codec.flush()
            else { codec.stop(); codec.configure(input.format, null, null, 0); codec.start() }   // до первого выхода flush потерял бы CSD
            input.seekTo(targetUs)
            dropBeforeUs = targetUs
            inputEnded = false; ended = false
            lastProgressAt = SystemClock.elapsedRealtime()
        }

        /** Подать пакет и забрать готовый PCM (до ~10 мс ожидания кодека). */
        fun step() {
            if (ended) return
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: error("Не удалось прочитать аудиоданные")
                    buffer.clear()
                    val size = input.read(buffer)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, input.lastPtsUs, 0)
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
                    sawOutput = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    sawOutput = true
                    lastProgressAt = SystemClock.elapsedRealtime()
                    if (info.size > 0) consumeOutput(codec.getOutputBuffer(outputIndex) ?: error("Пустой аудиобуфер"))
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (eos) { flushInput(final = true); ended = true }
                }
            }
            check(SystemClock.elapsedRealtime() - lastProgressAt < 30_000) { "Декодер аудио не отвечает" }
        }

        private fun consumeOutput(buffer: ByteBuffer) {
            require(sampleRate > 0 && channels in 1..8) { "Неподдерживаемые параметры аудио" }
            if (converterRate != sampleRate) {
                flushInput(final = true)
                closeConverter()
                converterRate = sampleRate
                converter = SampleRateConverter(sampleRate, SF.SAMPLE_RATE)
            }
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
            val bufferUs = info.presentationTimeUs - input.delayUs
            for (fr in 0 until frames) {
                var l = readSample(buffer, encoding)
                var r = if (channels >= 2) readSample(buffer, encoding) else l
                if (channels == 6) {
                    // 5.1 (FL FR FC LFE SL SR): даунмикс ITU-R BS.775 без LFE, с нормировкой против клиппинга.
                    val c = readSample(buffer, encoding)
                    readSample(buffer, encoding)   // LFE
                    val sl = readSample(buffer, encoding)
                    val sr = readSample(buffer, encoding)
                    l = ((l + DOWNMIX_SIDE * (c + sl)) * DOWNMIX_NORM).toFloat()
                    r = ((r + DOWNMIX_SIDE * (c + sr)) * DOWNMIX_NORM).toFloat()
                } else for (c in 2 until channels) readSample(buffer, encoding)   // прочие раскладки: L и R
                if (dropBeforeUs >= 0) {
                    // Допуск 2 мкс: метки времени округлены до мкс, соседние сэмплы отстоят ≥ 5 мкс даже при 192 кГц.
                    if (bufferUs + fr * 1_000_000L / sampleRate < dropBeforeUs - DROP_TOLERANCE_US) continue
                    dropBeforeUs = -1L
                }
                inBlock[inCount * 2] = l; inBlock[inCount * 2 + 1] = r
                if (++inCount == BLOCK_FRAMES) flushInput(final = false)
            }
        }

        /** Накопленный интерлив → ресемплер ядра → [emit]. final=true дописывает хвост ресемплера. */
        private fun flushInput(final: Boolean) {
            val c = converter ?: return
            val need = (c.maxOutputFrames(maxOf(inCount, BLOCK_FRAMES)) + 16) * 2
            if (outBlock.size < need) outBlock = FloatArray(need)
            if (inCount > 0) { emit(outBlock, c.process(inBlock, inCount, outBlock)); inCount = 0 }
            if (final) emit(outBlock, c.finish(outBlock))
        }

        private fun closeConverter() {
            try { converter?.close() } catch (_: Exception) {}
            converter = null; converterRate = -1
        }

        override fun close() {
            // SRC закрываем и при отмене или ошибке кодека: иначе нативный хендл утекал.
            closeConverter()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    /**
     * Поток декодера одного трека: держит заполненным окно [SegmentedPcmSource] вокруг позиции.
     * Работает над первым недостающим сегментом окна; если он не следующий по порядку — переход.
     */
    private class StreamDecoder(
        context: Context,
        track: PreparedAudioFile,
        private val startFrame: Long,
        onSource: (SegmentedPcmSource) -> Unit,
    ) : AutoCloseable {
        private val input: CompressedInput = openInput(context, track.uri)
        val source = SegmentedPcmSource(track.title, track.artist, input.durationUs * SF.SAMPLE_RATE / 1_000_000)
        private val writer = SegmentWriter(source)
        private val pipeline: PcmPipeline
        @Volatile private var stopped = false

        init {
            try { pipeline = PcmPipeline(input) { block, frames -> writer.write(block, frames) } }
            catch (t: Throwable) { input.close(); writer.close(); throw t }
            onSource(source)
            source.setOnClose { stopped = true }
        }

        fun run(ready: CompletableFuture<SegmentedPcmSource>) {
            source.focus(startFrame)
            var passStart = 0L
            var endHandled = false
            var idled = false
            var backtracking = false    // ищем настоящий конец файла, вне окна
            fun jumpTo(index: Long) {
                writer.restartAt(index)
                pipeline.seek(index * SegmentedPcmSource.SEGMENT_FRAMES * 1_000_000L / SF.SAMPLE_RATE)
                passStart = index
                endHandled = false
            }
            while (!stopped && !source.closed) {
                if (!ready.isDone && (source.complete || source.hasSegment(source.playheadSegment))) ready.complete(source)
                if (pipeline.ended && !endHandled) {
                    endHandled = true
                    val passEnd = writer.positionFrame
                    val reachedEnd = writer.wroteSinceRestart && !writer.collided
                    writer.finish()
                    backtracking = false
                    if (reachedEnd) source.markEnd(passEnd)
                    else if (!writer.wroteSinceRestart) {
                        // Переход оказался за концом файла (длительность в метаданных завышена):
                        // сегментов отсюда нет, шагаем назад, пока не найдём настоящий конец.
                        source.limitSegments(passStart)
                        if (passStart > 0) { jumpTo(passStart - 1); backtracking = true; continue }
                        source.markEnd(1L)
                    }
                    if (!ready.isDone) ready.complete(source)
                }
                val target = source.nextWantedSegment()
                if (target == null && backtracking) { pipeline.step(); continue }
                if (target == null) {
                    if (!ready.isDone && pipeline.ended) ready.complete(source)
                    source.awaitDemand(100)
                    idled = true
                    continue
                }
                if (idled) { pipeline.resetWatchdog(); idled = false }
                if (target != writer.index || writer.collided || pipeline.ended) jumpTo(target)
                pipeline.step()
            }
            if (!ready.isDone) ready.completeExceptionally(CancelledException())
        }

        override fun close() {
            try { pipeline.close() } finally { input.close(); writer.close() }
        }
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

    /** Пишет PCM сегментами по 5 с (24 бит, TPDF-дизеринг) прямо в память источника. */
    private class SegmentWriter(private val source: SegmentedPcmSource) {
        private val encoder = Pcm24StereoEncoder(seed = 0x5F24)
        private val meter = app.soundfield.dsp.TruePeakMeter()
        private var bytes = ByteArray(0)
        private var segment: ByteArray? = null
        private var segmentFrames = 0
        /** Номер сегмента, который сейчас пишется. */
        var index = 0L
            private set
        /** Следующий сегмент уже есть в окне: запись остановлена, нужен переход. */
        var collided = false
            private set
        var wroteSinceRestart = false
            private set
        /** Абсолютный номер следующего записываемого кадра. */
        val positionFrame: Long get() = index * SegmentedPcmSource.SEGMENT_FRAMES + segmentFrames

        /** Начать запись с начала сегмента [next]; недописанный сегмент отбрасывается. */
        fun restartAt(next: Long) {
            index = next; segmentFrames = 0
            collided = false; wroteSinceRestart = false
        }

        /** [frames] кадров интерлива L/R (float). */
        fun write(block: FloatArray, frames: Int) {
            var done = 0
            while (done < frames && !collided) {
                val n = minOf(frames - done, SegmentedPcmSource.SEGMENT_FRAMES - segmentFrames)
                if (bytes.size < n * 6) bytes = ByteArray(n * 6)
                val part = if (done == 0 && n == frames) block else block.copyOfRange(done * 2, (done + n) * 2)
                meter.process(part, n)
                encoder.encode(part, n, bytes)
                val target = segment ?: ByteArray(SegmentedPcmSource.SEGMENT_FRAMES * 6).also { segment = it }
                System.arraycopy(bytes, 0, target, segmentFrames * 6, n * 6)
                segmentFrames += n; done += n; wroteSinceRestart = true
                if (segmentFrames == SegmentedPcmSource.SEGMENT_FRAMES) publishSegment()
            }
        }

        /** Конец файла: опубликовать неполный последний сегмент. */
        fun finish() { if (segmentFrames > 0) publishSegment() }

        fun close() { encoder.close(); meter.close() }

        private fun publishSegment() {
            val data = segment ?: return
            segment = null
            if (!source.analyzed) source.truePeakDbtp = maxOf(source.truePeakDbtp.takeUnless { it.isNaN() } ?: -200.0, meter.truePeakDbtp)
            source.publish(index, ByteBuffer.wrap(data, 0, segmentFrames * 6).slice(), segmentFrames)
            segmentFrames = 0
            index++
            collided = source.hasSegment(index)
        }
    }

    // Константы API 31 — объявлены локально, чтобы код компилировался и работал на minSdk 26.
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22
    private const val BLOCK_FRAMES = 4096
    /** После перехода декодер начинает чуть раньше нужного места: первые кадры MP3 после seek неполные. */
    private const val SEEK_PREROLL_US = 300_000L
    private const val DROP_TOLERANCE_US = 2L
    /** Сколько индексов MP3 хранить (последние открытые). */
    private const val MP3_INDEX_KEEP = 300
}

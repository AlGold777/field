package app.soundfield.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import app.soundfield.core.PcmSource
import app.soundfield.core.SF
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.UUID

/** A copied queue item is independent of the temporary permission granted by the document picker. */
data class PreparedAudioFile(val id: String, val file: File, val title: String, val artist: String)

/** Decodes one selected track at a time into the engine's 48 kHz stereo PCM format. */
object AudioFileDecoder {
    fun prepare(context: Context, uri: Uri, displayName: String): PreparedAudioFile {
        val id = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "audio_queue").apply { mkdirs() }
        val extension = displayName.substringAfterLast('.', "audio").take(8).ifBlank { "audio" }
        val target = File(folder, "$id.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: error("Не удалось прочитать файл")
            require(target.length() > 0) { "Файл пуст" }
            val retriever = MediaMetadataRetriever()
            val title: String
            val artist: String
            try {
                retriever.setDataSource(target.absolutePath)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.', displayName)
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() } ?: "Локальная музыка"
            } finally { retriever.release() }
            return PreparedAudioFile(id, target, title, artist)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun decode(track: PreparedAudioFile): PcmSource {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(track.file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный аудиоформат")
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                val output = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var lastProgressAt = SystemClock.elapsedRealtime()
                while (!outputEnded) {
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
                            val decodedFormat = codec.outputFormat
                            sampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val pcmEncoding = if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) { "Кодек вернул неподдерживаемый PCM формат" }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            lastProgressAt = SystemClock.elapsedRealtime()
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(outputIndex) ?: error("Пустой аудиобуфер")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                buffer.get(chunk)
                                output.write(chunk)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                    check(SystemClock.elapsedRealtime() - lastProgressAt < 30_000) {
                        "Декодер аудио не отвечает"
                    }
                }
                require(sampleRate > 0 && channels in 1..8) { "Неподдерживаемые параметры аудио" }
                val pcm = toStereo48k(output.toByteArray(), channels, sampleRate)
                require(pcm.isNotEmpty()) { "В файле нет аудиоданных" }
                return PcmSource(pcm, track.title, track.artist)
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
        } finally { extractor.release() }
    }

    private fun toStereo48k(bytes: ByteArray, channels: Int, sampleRate: Int): ShortArray {
        val samples = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inputFrames = samples.remaining() / channels
        require(inputFrames > 0) { "В файле нет аудиоданных" }
        if (channels == 2 && sampleRate == SF.SAMPLE_RATE) {
            val stereo = ShortArray(inputFrames * 2)
            samples.get(stereo)
            return stereo
        }
        val outputFrames = (inputFrames.toLong() * SF.SAMPLE_RATE / sampleRate).toInt().coerceAtLeast(1)
        val stereo = ShortArray(outputFrames * 2)
        for (frame in 0 until outputFrames) {
            val position = frame.toDouble() * sampleRate / SF.SAMPLE_RATE
            val first = position.toInt().coerceIn(0, inputFrames - 1)
            val second = (first + 1).coerceAtMost(inputFrames - 1)
            val fraction = position - first
            val leftChannel = 0
            val rightChannel = if (channels == 1) 0 else 1
            val leftA = samples.get(first * channels + leftChannel).toInt()
            val leftB = samples.get(second * channels + leftChannel).toInt()
            val rightA = samples.get(first * channels + rightChannel).toInt()
            val rightB = samples.get(second * channels + rightChannel).toInt()
            stereo[frame * 2] = (leftA * (1.0 - fraction) + leftB * fraction).toInt().coerceIn(-32768, 32767).toShort()
            stereo[frame * 2 + 1] = (rightA * (1.0 - fraction) + rightB * fraction).toInt().coerceIn(-32768, 32767).toShort()
        }
        return stereo
    }
}

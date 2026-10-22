package app.soundfield.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder

/**
 * Запись микрофона для акустической калибровки. Источник — UNPROCESSED (без AGC/шумоподавления),
 * если устройство его поддерживает, иначе VOICE_RECOGNITION (минимальная обработка).
 * startNs — момент первого сэмпла в System.nanoTime (точность ±десятки мс достаточна:
 * при анализе абсолютное время сокращается).
 */
class MicRecorder(private val ctx: Context) {
    class Recording(val samples: FloatArray, val sampleRate: Int, val startNs: Long)

    @SuppressLint("MissingPermission")
    fun record(durationMs: Long, onStarted: () -> Unit): Recording = record(durationMs, onStarted) { true }

    fun record(durationMs: Long, onStarted: () -> Unit, shouldContinue: () -> Boolean): Recording {
        val sr = 48_000
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, sr / 5 * 4))
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "Микрофон недоступен" }
            val total = (sr * durationMs / 1000).toInt()
            val out = FloatArray(total)
            val chunk = 960
            var pos = 0
            var startNs = 0L
            rec.startRecording()
            try {
                while (pos < total && shouldContinue() && !Thread.currentThread().isInterrupted) {
                    val r = rec.read(out, pos, minOf(chunk, total - pos), AudioRecord.READ_BLOCKING)
                    // неполная запись не считается успешной: вызывающий код покажет причину
                    check(r > 0) { "Микрофон: чтение прервано (код $r)" }
                    if (pos == 0) {
                        val ts = AudioTimestamp()
                        startNs = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            ts.nanoTime - (ts.framePosition * 1e9 / sr).toLong()
                        } else System.nanoTime() - (r * 1e9 / sr).toLong()
                        onStarted()
                    }
                    pos += r
                }
            } finally {
                rec.stop()
            }
            check(pos == total) { "Запись микрофона неполная: получено $pos из $total кадров" }
            return Recording(out, sr, startNs)
        } finally {
            rec.release()
        }
    }
}

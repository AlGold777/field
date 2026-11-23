package app.soundfield.audio.mp3

import java.nio.ByteBuffer

/**
 * Сколько кадров подавать декодеру до целевого при перемотке. Bit reservoir Layer III позволяет кадру
 * ссылаться назад на main_data_begin до 511 байт: на 128 кбит/с (417 Б/кадр) это ~2 кадра, на низких
 * битрейтах (32 кбит/с, ~104 Б) до 5. Ещё один кадр нужен на прогрев синтезирующего фильтра
 * (перекрытие гранул). Берём 4: покрывает типичные битрейты, лишний кадр дёшев; выход этих
 * кадров отбрасывается.
 */
const val PREROLL_FRAMES = 4

/** Чтение кадров по индексу для подачи в MediaCodec. Не потокобезопасен (внутренний буфер). */
class Mp3FrameReader(private val src: ByteSource, private val index: Mp3FrameIndex) {
    private var scratch = ByteArray(2048)

    /**
     * Копирует байты кадра [frame] в [dst] (буфер ввода MediaCodec). Возвращает размер кадра
     * или -1: номер вне диапазона, мало места в [dst], ошибка чтения.
     */
    fun read(frame: Int, dst: ByteBuffer): Int {
        if (frame < 0 || frame >= index.frameCount) return -1
        val size = index.frameSize(frame)
        if (dst.remaining() < size) return -1
        if (scratch.size < size) scratch = ByteArray(size)
        val pos = index.frameOffset(frame)
        var got = 0
        while (got < size) {
            val r = src.read(pos + got, scratch, got, size - got)
            if (r <= 0) return -1
            got += r
        }
        dst.put(scratch, 0, size)
        return size
    }

    /** Время кадра в мкс от начала первого аудиокадра (без вычета задержки кодера). */
    fun presentationTimeUs(frame: Int): Long =
        frame.toLong() * index.samplesPerFrame * 1_000_000L / index.sampleRate
}

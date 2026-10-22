package app.soundfield.core

import app.soundfield.dsp.WindowedSinc
import org.junit.Assert.*
import org.junit.Test

/**
 * Реальные выходные сэмплы Point: источник кодирует номер кадра в значение сэмпла, каждый выходной сэмпл
 * сверяется с ожидаемым. Цепочка: PointReceiver → PointRenderer (PlayoutCorrector, sinc-интерполяция,
 * огибающая, EQ в обходе, лимитер). Часы идеальны (смещение 0, дрейфа нет), поэтому позиции целые и
 * ожидание точное. Лимитер ядра задерживает выход на limiterLatencyFrames, но корректор вперёд компенсирует эту задержку
 * (план позиции + latency): звук выходит в запланированный момент, первые latency кадров после старта — тишина.
 */
class PlayoutOutputTest {
    private val blockFrames = 480
    private val blocks = 12
    private val firstCallback = 2L            // 20 мс: огибающая нарастания уже позади
    private val callbacks = 6
    private var lookahead = 0
    /** Кадр источника, который попадает в первый выходной сэмпл первого колбэка. */
    private val base = firstCallback * blockFrames - WindowedSinc.GROUP_DELAY_FRAMES

    /** 24-битный код: значения ±0,46 полной шкалы, ниже потолка лимитера. */
    private fun code(frame: Long): Int = (((frame % 30_000L) - 15_000L) * 256L).toInt()
    private fun sampleOf(frame: Long): Float = code(frame).toFloat() / Pcm24.SCALE
    /** Ожидаемое значение выходного сэмпла с глобальным индексом j (по всем колбэкам). */
    private fun expectedAt(j: Int): Float = if (j < lookahead) 0f else sampleOf(base + j)

    private fun packet(seq: Int, flags: Int = 0): UdpMsg.Audio {
        val start = seq * blockFrames.toLong()
        val samples = IntArray(blockFrames) { code(start + it) }
        // arrival = 0, sendNs = -1 мс: односторонняя задержка 1 мс совпадает с замерами pong
        return UdpMsg.Audio(1, 1, seq, start, 0L, -1_000_000L, flags, samples, blockFrames)
    }

    private fun chain(): Pair<PointReceiver, PointRenderer> {
        val clock = ClockSync()
        repeat(3) { k ->
            val t1 = 1_000_000_000L + k * 1_000_000L
            clock.addPong(t1, t1 + 1_000_000L, t1 + 1_000_000L, t1 + 2_000_000L)
        }
        val rx = PointReceiver(clock)
        rx.onTimeline(Timeline(1, true, 0L, 0L))
        val renderer = PointRenderer(rx, clock, TcpAudioBuffer(10), { "udp" }, { PointParams(1f, 0L, Long.MAX_VALUE) })
        lookahead = renderer.limiterLatencyFrames
        return rx to renderer
    }

    private fun renderRun(renderer: PointRenderer): FloatArray {
        val all = FloatArray(callbacks * blockFrames)
        for (c in 0 until callbacks) {
            val out = FloatArray(blockFrames)
            renderer.render(out, blockFrames, (firstCallback + c) * 10_000_000L)
            System.arraycopy(out, 0, all, c * blockFrames, blockFrames)
        }
        return all
    }

    @Test fun inOrderStreamRendersEveryFrameExactly() {
        val (rx, renderer) = chain()
        for (seq in 0 until blocks) rx.onAudio(packet(seq), 0L)
        val got = renderRun(renderer)
        for (j in got.indices) assertEquals("выходной сэмпл $j", expectedAt(j).toDouble(), got[j].toDouble(), 1e-4)
    }

    @Test fun missingPacketIsSilentAndCountedAsUnderrun() {
        val gapSeq = 4
        val gapStart = gapSeq * blockFrames.toLong()
        val gapEnd = gapStart + blockFrames
        val (rx, renderer) = chain()
        for (seq in 0 until blocks) if (seq != gapSeq) rx.onAudio(packet(seq), 0L)
        val got = renderRun(renderer)
        for (j in got.indices) {
            val frame = base + j
            // окно sinc-интерполяции [frame - HALF + 1, frame + HALF] задевает пропуск — тишина
            val touchesGap = j >= lookahead && frame + WindowedSinc.HALF >= gapStart && frame - WindowedSinc.HALF + 1 < gapEnd
            val expected = if (touchesGap) 0f else expectedAt(j)
            assertEquals("выходной сэмпл $j", expected.toDouble(), got[j].toDouble(), 1e-4)
        }
        assertTrue("пропуск должен считаться underrun", renderer.underrunFrames.get() > 0)
    }

    @Test fun retransmittedPacketFillsGapWithExactSamples() {
        val gapSeq = 4
        val (rx, renderer) = chain()
        for (seq in 0 until blocks) if (seq != gapSeq) rx.onAudio(packet(seq), 0L)
        rx.onAudio(packet(gapSeq, UdpMsg.FLAG_RETRANSMIT), 0L)
        val got = renderRun(renderer)
        for (j in got.indices) assertEquals("выходной сэмпл $j", expectedAt(j).toDouble(), got[j].toDouble(), 1e-4)
        assertEquals(1L, rx.recovered)
        assertEquals(0L, renderer.underrunFrames.get())
    }
}

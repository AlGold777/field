package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Smoke-тест запасного ресемплера 44,1 → 48 кГц на синусе 1 кГц. */
class StreamResamplerTest {
    @Test fun sine1kHzKeepsLengthAmplitudeAndHasNoNaN() {
        val inRate = 44100; val outRate = 48000
        val seconds = 1
        val amp = 0.5
        val inFrames = inRate * seconds
        val outL = ArrayList<Float>(); val outR = ArrayList<Float>()
        val rs = StreamResampler(inRate, outRate) { l, r -> outL.add(l); outR.add(r) }
        for (i in 0 until inFrames) {
            val v = (amp * sin(2 * PI * 1000.0 * i / inRate)).toFloat()
            rs.push(v, -v)
        }
        rs.finish()

        // число кадров ≈ inFrames * outRate / inRate; допуск на задержку/хвост фильтра (24 отвода)
        val expected = inFrames.toLong() * outRate / inRate
        assertTrue("кадров ${outL.size}, ожидалось ≈ $expected", abs(outL.size - expected) <= 24)

        assertTrue("NaN/Inf в выходе", outL.all { it.isFinite() } && outR.all { it.isFinite() })

        // RMS на середине (без краёв, где фильтр прогревается) → амплитуда синуса
        val from = outL.size / 4; val to = outL.size * 3 / 4
        var sum = 0.0
        for (i in from until to) sum += outL[i].toDouble() * outL[i]
        val measured = sqrt(sum / (to - from)) * sqrt(2.0)
        val deltaDb = 20 * log10(measured / amp)
        assertEquals("амплитуда $measured, отклонение $deltaDb дБ", 0.0, deltaDb, 0.5)

        // правый канал — зеркальный (инвертированный) левому
        for (i in from until to step 97) assertEquals(-outL[i], outR[i], 1e-6f)
    }
}

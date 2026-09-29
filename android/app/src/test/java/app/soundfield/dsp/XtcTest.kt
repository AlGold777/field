package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class XtcTest {
    private fun run(filters: XtcFilterSet, impulseLeft: Boolean, impulseRight: Boolean = false, frames: Int = 20_000,
        at: Int = 4_000): Pair<DoubleArray, DoubleArray> {
        val left = DoubleArray(frames); val right = DoubleArray(frames)
        val processor = XtcProcessor(filters) { frame, l, r ->
            if (frame in 0 until frames) { left[frame.toInt()] = l.toDouble(); right[frame.toInt()] = r.toDouble() }
        }
        for (frame in 0 until frames + 3_000) {
            processor.push(frame.toLong(), if (impulseLeft && frame == at) 1f else 0f, if (impulseRight && frame == at) 1f else 0f)
        }
        return left to right
    }

    private fun peak(x: DoubleArray) = x.indices.maxByOrNull { abs(x[it]) }!!

    @Test fun outputIsAlignedWithInputNotDelayedByTheModelDelay() {
        // Ошибка прототипа: задержка вносилась дважды, а компенсировалась один раз (+30 мс).
        val filters = XtcFilterDesigner.design(0.15, 0.06)
        val (left, _) = run(filters, impulseLeft = true)
        assertEquals(4_000, peak(left).toLong().toInt())
    }

    @Test fun heavyRegularizationIsTransparent() {
        val filters = XtcFilterDesigner.design(0.15, 10.0)
        val (left, right) = run(filters, impulseLeft = true)
        assertEquals(4_000, peak(left))
        assertTrue("left peak ${left[peak(left)]}", abs(left[peak(left)]) > 0.9)
        assertTrue("crossfeed must stay small, got ${abs(right[peak(right)])}", abs(right[peak(right)]) < 0.1)
    }

    @Test fun filtersAreFiniteAndDoNotAmplifyCentredContentUnreasonably() {
        val filters = XtcFilterDesigner.design(0.15, 0.06)
        val (left, right) = run(filters, impulseLeft = true, impulseRight = true)
        var energy = 0.0
        for (i in left.indices) {
            assertTrue(left[i].isFinite() && right[i].isFinite())
            energy += left[i] * left[i] + right[i] * right[i]
        }
        val levelDb = 10.0 * Math.log10(energy / 2.0)
        assertTrue("centred content changed by $levelDb dB", abs(levelDb) < 3.0)
    }

    @Test fun modelledCrosstalkStaysWithinRegressionBounds() {
        val soft = XtcFilterDesigner.design(0.15, 0.06)
        // Модельная оценка (не измерение): медиана около −5 дБ, худшая точка около +1 дБ.
        assertTrue("median ${soft.predictedMedianDb}", soft.predictedMedianDb < -4.0)
        assertTrue("worst ${soft.predictedWorstDb}", soft.predictedWorstDb < 3.0)
        val strong = XtcFilterDesigner.design(0.15, 0.02)
        assertTrue("stronger setting must not be worse in the median: ${strong.predictedMedianDb} vs ${soft.predictedMedianDb}",
            strong.predictedMedianDb <= soft.predictedMedianDb + 0.5)
    }

    @Test fun designStaysWithinAColdStartBudgetOnTheJvm() {
        val started = System.nanoTime()
        XtcFilterDesigner.design(0.14, 0.05)
        val ms = (System.nanoTime() - started) / 1e6
        assertTrue("design took $ms ms", ms < 3_000.0)
    }
}

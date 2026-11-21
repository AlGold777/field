package app.soundfield.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Короткий сквозной сценарий Server → сеть → PointReceiver + ClockSync на виртуальном времени:
 * потери 5 %, джиттер доставки 0–8 мс, одна «пачка» потерь, NACK → перепосылка
 * (FLAG_RETRANSMIT). Номер кадра закодирован в значении сэмпла, чтобы проверять содержимое.
 */
class PointPipelineTest {
    private val ms = 1_000_000L
    private val t0 = 10_000 * ms                 // локальное время Point: далеко от нуля
    private val serverOffsetNs = -2_500 * ms      // server = local + offset
    private val packets = 300                     // 2,4 с звука, помещается в кольцо приёмника
    private val pf = SF.PACKET_FRAMES

    private fun packet(seq: Int, flags: Int, sendServerNs: Long): UdpMsg.Audio {
        val start = seq.toLong() * pf
        return UdpMsg.Audio(1, 1, seq, start, 0L, sendServerNs, flags,
            IntArray(pf) { (start + it).toInt() }, pf)   // сэмпл = номер кадра (< 2^23, точен в Float)
    }

    private fun expected(frame: Long) = frame.toInt() / Pcm24.SCALE

    private class Event(val at: Long, val order: Int, val pkt: UdpMsg.Audio)

    @Test fun lossBurstJitterAndRetransmitsRestoreContent() {
        val rnd = Random(11)
        val cs = ClockSync()
        val rx = PointReceiver(cs)

        // --- какие оригиналы теряются ---
        val lost = HashSet<Int>()
        for (s in 6 until packets - 10) if (rnd.nextDouble() < 0.05) lost.add(s)
        val burst = (150 until 170).toList()                 // пачка из 20 подряд (160 мс)
        lost.addAll(burst)
        // безнадёжные: перепосылки не доходят никогда (две из пачки + одна случайная)
        val hard = HashSet<Int>().apply {
            add(burst[7]); add(burst[8])
            add(lost.first { it !in burst })
        }
        assertTrue(lost.size > burst.size)

        // --- доставка оригиналов с джиттером 0–8 мс поверх 1 мс базовой задержки ---
        var order = 0
        val queue = PriorityQueue<Event>(compareBy<Event>({ it.at }, { it.order }))
        for (seq in 0 until packets) {
            val sendLocal = t0 + seq * 8 * ms
            if (seq in lost) continue
            val delay = ms + (rnd.nextDouble() * 8 * ms).toLong()
            queue.add(Event(sendLocal + delay, order++, packet(seq, 0, sendLocal + serverOffsetNs)))
        }

        // --- виртуальные часы по 1 мс: доставка, PING/PONG, NACK → ответ Server ---
        val end = t0 + packets * 8 * ms + 700 * ms           // с запасом на 350 мс окончательной потери
        var now = t0
        var nackRounds = 0
        while (now <= end) {
            while (queue.isNotEmpty() && queue.peek().at <= now) {
                val e = queue.poll()
                rx.onAudio(e.pkt, e.at)
            }
            if ((now - t0) % (50 * ms) == 0L) {              // PONG раз в 50 мс, симметричная задержка 2–5 мс
                val up = 2 * ms + rnd.nextLong(3 * ms); val down = 2 * ms + rnd.nextLong(3 * ms)
                val t2 = now + serverOffsetNs + up
                cs.addPong(now, t2, t2 + 100_000, now + up + 100_000 + down)
            }
            val nacks = rx.collectNacks(now)
            if (nacks.isNotEmpty()) nackRounds++
            for (seq in nacks) {
                assertTrue("запрошен seq=$seq, который не терялся", seq in lost)
                if (seq in hard) continue                    // Server «ответил», но пакет не дошёл
                val at = now + 2 * ms + rnd.nextLong(3 * ms)  // RTT NACK→перепосылка
                queue.add(Event(at, order++, packet(seq, UdpMsg.FLAG_RETRANSMIT, at + serverOffsetNs)))
            }
            now += ms
        }

        assertTrue(nackRounds > 0 && rx.nacksSent > 0)
        // непоправимые потери: ровно «безнадёжные», остальное восстановлено
        assertEquals(hard.size.toLong(), rx.lostFinal)
        assertTrue("recovered=${rx.recovered}", rx.recovered >= lost.size - hard.size)
        assertEquals(0L, rx.late)
        assertEquals(0L, rx.stale)

        // --- содержимое: каждый кадр либо точный, либо NaN только у безнадёжных пакетов ---
        for (seq in 0 until packets) {
            val first = seq.toLong() * pf
            for (i in 0 until pf) {
                val v = rx.read(first + i)
                if (seq in hard) assertTrue("seq=$seq кадр ${first + i} должен отсутствовать", v.isNaN())
                else assertEquals("seq=$seq кадр ${first + i}", expected(first + i), v, 0f)
            }
        }
        assertTrue(rx.read(packets.toLong() * pf).isNaN())    // за концом потока — тишина

        // --- часы сошлись по ходу: PONG + односторонние замеры аудиопакетов ---
        assertTrue(cs.ready)
        val probeLocal = now
        assertTrue("ошибка модели ${abs(cs.toServer(probeLocal) - (probeLocal + serverOffsetNs)) / 1e6} мс",
            abs(cs.toServer(probeLocal) - (probeLocal + serverOffsetNs)) < 1_500_000L)
    }
}

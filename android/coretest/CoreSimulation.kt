// Симуляционные тесты ядра SoundField (запускаются на JVM без Android).
import app.soundfield.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  OK   " else "  FAIL ") + name + (if (detail.isNotEmpty()) " — $detail" else ""))
    if (!ok) failures++
}

fun testWire() {
    println("[Wire]")
    val s = ShortArray(480) { (it * 37 - 9000).toShort() }
    val b = Wire.audio(0x1234ABCD, 7, 99, 1_234_567_890_123L, -5L, 42L, UdpMsg.FLAG_RETRANSMIT, s, 480)
    val m = Wire.decode(b, b.size) as UdpMsg.Audio
    check("audio roundtrip", m.session == 0x1234ABCD && m.epoch == 7 && m.seq == 99 && m.startFrame == 1_234_567_890_123L &&
        m.playoutNs == -5L && m.sendNs == 42L && m.isRetransmit && m.samples.contentEquals(s))
    check("audio size", b.size == 44 + 960, "${b.size}")
    val p = Wire.decode(Wire.pong(1, 2, 3, 4, 5), 36) as UdpMsg.Pong
    check("pong roundtrip", p.t1 == 2L && p.t2 == 3L && p.t3 == 4L && p.pingSeq == 5)
    val n = Wire.decode(Wire.nack(9, 3, intArrayOf(10, 11, 12)).let { it }, 8 + 6 + 12) as UdpMsg.Nack
    check("nack roundtrip", n.epoch == 3 && n.seqs.contentEquals(intArrayOf(10, 11, 12)))
    check("reject garbage", Wire.decode(ByteArray(20) { 1 }, 20) == null)
    // эталонный пакет для сверки со Swift-реализацией
    val ref = Wire.ping(0x01020304, 0x0A0B0C0D0E0F1011L, 77)
    println("  PING hex: " + ref.joinToString("") { "%02x".format(it) })
}

/** Часы Point: local = server·(1+drift) + offset. */
class SimClocks(val offsetNs: Long, val driftPpm: Double) {
    fun localFromServer(s: Long) = (s * (1 + driftPpm * 1e-6)).toLong() + offsetNs
    fun serverFromLocal(l: Long) = ((l - offsetNs) / (1 + driftPpm * 1e-6)).toLong()
}

fun testClockSync() {
    println("[ClockSync]")
    val rnd = Random(1)
    val clk = SimClocks(offsetNs = 987_654_321_000L, driftPpm = 45.0)
    val cs = ClockSync()
    var server = 10_000_000_000L
    var maxErr = 0.0
    for (i in 0 until 320) {                  // 20 быстрых + 300 по 0.2 с (60 с)
        server += if (i < 20) 50_000_000L else 200_000_000L
        val up = (2 + rnd.nextDouble() * 3 + if (rnd.nextDouble() < 0.2) rnd.nextDouble() * 40 else 0.0) * 1e6
        val down = (2 + rnd.nextDouble() * 3 + if (rnd.nextDouble() < 0.2) rnd.nextDouble() * 40 else 0.0) * 1e6
        val t1 = clk.localFromServer(server)
        val t2 = server + up.toLong()
        val t3 = t2 + 100_000
        val t4 = clk.localFromServer(t3 + down.toLong())
        cs.addPong(t1, t2, t3, t4)
        if (i > 60) {
            val probeLocal = clk.localFromServer(server + 1_000_000_000L)
            val err = abs(cs.toServer(probeLocal) - (server + 1_000_000_000L)) / 1e6
            maxErr = max(maxErr, err)
        }
    }
    check("offset error < 1.5 ms", maxErr < 1.5, "max %.3f ms".format(maxErr))
    check("drift ≈ −45 ppm (server per local)", abs(cs.driftPpm + 45) < 10, "%.1f ppm".format(cs.driftPpm))
    println("  rttMin=%.2f avg=%.2f jitter=%.2f ms".format(cs.rttMinMs, cs.rttAvgMs, cs.rttJitterMs))
}

/**
 * Сквозная симуляция: Server шлёт PCM (эпохи, NACK), сеть задерживает/теряет/переставляет,
 * Point рендерит колбэками своего аудиоустройства с собственным дрейфом кварца. Проверяем, что
 * кадры звучат на Point в нужный момент часов Server.
 */
fun testEndToEnd(lossPct: Double, jitterMs: Double, spikePct: Double, limitP95: Double) {
    println("[E2E loss=${lossPct}% jitter(exp mean)=${jitterMs}ms spikes=${spikePct}%]")
    val rnd = Random(7)
    val sr = SF.SAMPLE_RATE
    val clk = SimClocks(offsetNs = -3_210_000_000L, driftPpm = -60.0)
    val pcm = ShortArray(sr * 2 * 50) { i -> ((i / 2) % 30000 - 15000).toShort() } // ramp: кадр → значение
    val src = PcmSource(pcm, "t", "a")
    val cs = ClockSync()
    val rx = PointReceiver(cs)
    val params = PointParams(1f, 0, src.totalFrames)
    val renderer = PointRenderer(rx, cs) { params }

    data class InFlight(val deliverAt: Long, val bytes: ByteArray)
    val toPoint = ArrayList<InFlight>(); val toServer = ArrayList<InFlight>()
    fun netDelay(): Long { var d = 1.5 - ln(1 - rnd.nextDouble()) * jitterMs; if (rnd.nextDouble() * 100 < spikePct) d += 30 + rnd.nextDouble() * 50; return (d * 1e6).toLong() }

    val session = 42
    var serverNow = 5_000_000_000L
    val tl = Timeline(1, true, 0, serverNow + 1_000_000_000L)
    var seq = 0; var nextFrame = 0L
    val cache = HashMap<Int, Long>()
    var pingSeq = 0; var nextPing = serverNow
    // аудиоустройство Point: колбэк 240 кадров, собственный кварц +80 ppm относительно локальных часов,
    // выходная задержка 35 мс, таймстамп с шумом ±0.3 мс
    val cb = 240
    var devFrames = 0L
    val devStartLocal = clk.localFromServer(serverNow) + 1_500_000_000L
    val outLatency = 35_000_000L
    val errs = ArrayList<Double>()
    val out = FloatArray(cb)
    val end = serverNow + 40_000_000_000L
    val step = 1_000_000L
    while (serverNow < end) {
        serverNow += step
        // Server: отправка вперёд на LEAD
        val target = tl.frameAt(serverNow + SF.LEAD_NS).toLong()
        while (nextFrame < target) {
            val s = ShortArray(SF.PACKET_FRAMES) { pcm[((nextFrame + it) * 2).toInt()] }
            val b = Wire.audio(session, tl.epoch, seq, nextFrame, tl.timeOf(nextFrame), serverNow, 0, s, s.size)
            cache[seq] = nextFrame
            if (rnd.nextDouble() * 100 >= lossPct) toPoint.add(InFlight(serverNow + netDelay(), b))
            seq++; nextFrame += SF.PACKET_FRAMES
        }
        // Point: пинги
        if (serverNow >= nextPing) {
            nextPing += if (pingSeq < 20) 50_000_000L else 200_000_000L
            val t1 = clk.localFromServer(serverNow)
            toServer.add(InFlight(serverNow + netDelay(), Wire.ping(session, t1, pingSeq++)))
        }
        // доставка
        val now = serverNow
        toServer.filter { it.deliverAt <= now }.forEach { f ->
            when (val m = Wire.decode(f.bytes, f.bytes.size)) {
                is UdpMsg.Ping -> toPoint.add(InFlight(now + netDelay(), Wire.pong(session, m.t1, now, now + 50_000, m.pingSeq)))
                is UdpMsg.Nack -> m.seqs.forEach { sq -> cache[sq]?.let { sf ->
                    val s = ShortArray(SF.PACKET_FRAMES) { pcm[((sf + it) * 2).toInt()] }
                    if (rnd.nextDouble() * 100 >= lossPct)
                        toPoint.add(InFlight(now + netDelay(), Wire.audio(session, tl.epoch, sq, sf, tl.timeOf(sf), now, UdpMsg.FLAG_RETRANSMIT, s, s.size)))
                } }
                else -> {}
            }
        }
        toServer.removeAll { it.deliverAt <= now }
        toPoint.filter { it.deliverAt <= now }.forEach { f ->
            val local = clk.localFromServer(now)
            when (val m = Wire.decode(f.bytes, f.bytes.size)) {
                is UdpMsg.Pong -> cs.addPong(m.t1, m.t2, m.t3, local)
                is UdpMsg.Audio -> rx.onAudio(m, local)
                else -> {}
            }
        }
        toPoint.removeAll { it.deliverAt <= now }
        val nacks = rx.collectNacks(clk.localFromServer(now))
        if (nacks.isNotEmpty()) toServer.add(InFlight(now + netDelay(), Wire.nack(session, tl.epoch, nacks)))

        // аудиоколбэки Point
        val local = clk.localFromServer(serverNow)
        while (true) {
            val devTimeLocal = devStartLocal + (devFrames * 1e9 / (sr * (1 + 80e-6))).toLong()
            if (devTimeLocal > local || local < devStartLocal) break
            val present = devTimeLocal + outLatency + (rnd.nextDouble() * 0.6e6 - 0.3e6).toLong()
            renderer.render(out, cb, present)
            // истинный момент звучания первого сэмпла → какой кадр должен звучать по часам Server
            val trueServer = clk.serverFromLocal(devTimeLocal + outLatency)
            val idealFrame = tl.frameAt(trueServer)
            val v = out[0]
            if (cs.ready && serverNow > tl.anchorNs + 2_000_000_000L && v != 0f) {
                val playedFrame = rx.lastRenderedFrame - (cb - 1)
                errs.add((playedFrame - idealFrame) * 1000.0 / sr)
            }
            devFrames += cb
        }
    }
    val sorted = errs.map { abs(it) }.sorted()
    val p95 = if (sorted.isEmpty()) 999.0 else sorted[(sorted.size * 0.95).toInt()]
    val mean = if (errs.isEmpty()) 999.0 else errs.average()
    check("sync p95 < $limitP95 ms", p95 < limitP95, "p95=%.3f ms mean=%.3f ms, n=%d".format(p95, mean, errs.size))
    val totalPk = seq.toDouble()
    println("  received=${rx.received} lostFinal=${rx.lostFinal} recovered=${rx.recovered} late=${rx.late} " +
        "underrunEvents=${renderer.underrunEvents} jitter=%.2f ms buffer=%.0f ms drift=%.1f ppm hardJumps=${renderer.corrector.hardJumps}"
            .format(rx.jitterMs, rx.bufferMs, cs.driftPpm))
    check("unrecovered loss < 0.5%", rx.lostFinal / totalPk < 0.005, "${rx.lostFinal}/${totalPk.toInt()}")
    check("hard jumps ≤ 1", renderer.corrector.hardJumps <= 1, "${renderer.corrector.hardJumps}")
}

fun testCalibration(trueLateMs: Double, trimMs: Double) {
    println("[Calibration true=${trueLateMs}ms trim=${trimMs}ms]")
    val sr = 48_000
    val rnd = Random(3)
    val recStart = 100_000_000_000L
    val anchor = recStart + 700_000_000L
    val rec = FloatArray((sr * 8.2).toInt()) { (rnd.nextDouble() * 2 - 1).toFloat() * 0.05f }
    val up = Chirp.make(true, sr); val down = Chirp.make(false, sr)
    val inputLatencyS = 0.012  // сокращается
    val serverOutS = 0.020
    // Point звучит позже Server на trueLate (уже с учётом применённого trim)
    for (k in 0 until CalibrationSource.COUNT) {
        val tP = (anchor - recStart) / 1e9 + trimMs / 1000 + CalibrationSource.POINT_FIRST_S + k + serverOutS + trueLateMs / 1000 + inputLatencyS
        val tS = (anchor - recStart) / 1e9 + CalibrationSource.SERVER_FIRST_S + k + serverOutS + inputLatencyS
        fun addAt(t: Double, tpl: FloatArray, amp: Float) {
            val pos = t * sr; val i0 = floor(pos).toInt(); val fr = pos - i0
            for (j in tpl.indices) {           // дробная задержка линейной интерполяцией
                val v = tpl[j] * amp
                if (i0 + j + 1 < rec.size) { rec[i0 + j] += (v * (1 - fr)).toFloat(); rec[i0 + j + 1] += (v * fr).toFloat() }
            }
        }
        addAt(tP, up, 0.15f); addAt(tS, down, 0.3f)
        // эхо комнаты
        addAt(tP + 0.009, up, 0.05f); addAt(tS + 0.013, down, 0.08f)
    }
    val t0 = System.nanoTime()
    val r = CalibrationAnalyzer.analyze(rec, sr, recStart + 30_000_000L /* неточная метка */, anchor, (trimMs * 1e6).toLong())
    val ms = (System.nanoTime() - t0) / 1e6
    check("measured late ≈ true", r.ok && abs(r.lateMs - trueLateMs) < 0.3,
        "measured=%.3f ms spread=%.3f pairs=%d snr=%.1f dB, %s (%.0f ms CPU)".format(r.lateMs, r.spreadMs, r.pairs, r.snrDb, r.message, ms))
}

fun testCorrectorFrames() {
    println("[Corrector]")
    val c = PlayoutCorrector()
    c.plan(1000.0, 240)
    val a = (0 until 240).map { c.frame(it, 240) }
    check("no adjust contiguous", a.first() == 1000L && a.last() == 1239L && a.zipWithNext().all { it.second - it.first == 1L })
    // заставляем пропуск
    repeat(30) { c.plan(c.start + 240.0 + 20, 240) }
    check("slews toward ideal", abs(c.syncErrorMs) < 0.5, "err=%.3f".format(c.syncErrorMs))
}

fun testWavAndSources() {
    println("[WAV/Sources]")
    val f = File("../../tools/test_track.wav")
    if (f.exists()) {
        val d = Wav.decode(f.readBytes())
        check("wav decoded 40 s stereo", d.pcm.size == 48_000 * 40 * 2, "${d.pcm.size}")
        val src = PcmSource(d.pcm, "t", "a")
        var eL = 0.0; var eR = 0.0
        for (i in 0 until 48_000 * 4) { eL += src.sample(i.toLong(), 0).pow(2); eR += src.sample(i.toLong(), 1).pow(2) }
        check("intro has both channels", eL > 1 && eR > 1)
    }
    val cal = CalibrationSource(Ch.R)
    check("calib: point chirp at 0.5 s on R", cal.sample(24_000 + 300, Ch.R) != 0f && cal.sample(24_000 + 300, Ch.L) == 0f)
    check("calib: server chirp at 1.0 s on L", cal.sample(48_000 + 300, Ch.L) != 0f && cal.sample(48_000 + 300, Ch.R) == 0f)
}

fun testSegmentedPcmSource() {
    println("[SegmentedPcmSource]")
    val dir = kotlin.io.path.createTempDirectory("sf-segments").toFile()
    try {
        val frames = SegmentedPcmSource.SEGMENT_FRAMES
        fun segment(file: File, left: Short, right: Short) {
            val bytes = ByteArray(frames * 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0, left); putShort(2, right)
            }
            file.writeBytes(bytes)
        }

        val source = SegmentedPcmSource(dir, "segmented", "", frames.toLong() * 2)
        val first = File(dir, "first.pcm48").apply { segment(this, 16_384, (-16_384).toShort()) }
        val second = File(dir, "second.pcm48").apply { segment(this, 8_192, (-8_192).toShort()) }
        source.publish(0, first, frames)
        check("first segment publishes stereo samples", source.readyFrames == frames.toLong() &&
            abs(source.sample(0, Ch.L) - 0.5f) < 0.001f && abs(source.sample(0, Ch.R) + 0.5f) < 0.001f)
        check("missing segment is nonblocking silence", source.sample(frames.toLong(), Ch.L) == 0f)
        source.publish(1, second, frames)
        check("progress reaches full length", abs(source.progress() - 1f) < 0.001f)
        check("next segment is addressable", abs(source.sample(frames.toLong(), Ch.L) - 0.25f) < 0.001f)
        source.finish(frames.toLong() * 2)
        check("finished source reports exact duration", source.complete && source.totalFrames == frames.toLong() * 2)

        val largeStartIndex = Int.MAX_VALUE.toLong() / frames + 1
        val largeStart = largeStartIndex * frames
        val large = SegmentedPcmSource(dir, "large", "", largeStart + frames)
        large.publish(largeStartIndex, first, frames)
        check("long tracks exceed a single Int-sized mapping", large.readyFrames > Int.MAX_VALUE &&
            abs(large.sample(largeStart, Ch.L) - 0.5f) < 0.001f)
    } finally {
        dir.deleteRecursively()
    }
}

fun main() {
    testWire()
    testCorrectorFrames()
    testClockSync()
    testWavAndSources()
    testSegmentedPcmSource()
    testEndToEnd(0.0, 1.0, 0.0, 1.0)
    testEndToEnd(3.0, 4.0, 3.0, 1.5)
    testEndToEnd(10.0, 12.0, 8.0, 3.0)
    testCalibration(23.4, 0.0)
    testCalibration(-41.7, 30.0)
    testCalibration(0.8, -12.0)
    println(if (failures == 0) "ALL PASSED" else "FAILURES: $failures")
    if (failures != 0) kotlin.system.exitProcess(1)
}

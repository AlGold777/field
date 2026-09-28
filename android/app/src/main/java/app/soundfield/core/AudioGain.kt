package app.soundfield.core

import kotlin.math.abs
import kotlin.math.pow

/** Единственный источник закона пользовательского баланса. */
object BalanceLaw {
    fun gain(channel: Int, balance: Float): Float {
        val p = (2f * balance.coerceIn(0f, 1f) - 1f).coerceIn(-1f, 1f)
        val attenuated = (1f - abs(p)).coerceIn(0f, 1f).let { it * it }
        return when {
            p > 0f && channel == Ch.L -> attenuated
            p < 0f && channel == Ch.R -> attenuated
            else -> 1f
        }
    }

    fun differenceDb(balance: Float): Float {
        val p = abs(2f * balance.coerceIn(0f, 1f) - 1f)
        return if (p >= 1f) Float.POSITIVE_INFINITY else 40f * kotlin.math.log10(1f - p)
    }
}

/** Только для приёма снапшотов от старых Server до появления pointGain. */
object LegacyBalanceLaw {
    fun gain(channel: Int, balance: Float): Float = when {
        balance <= 0.5f -> if (channel == Ch.L) 1f else balance * 2f
        else -> if (channel == Ch.R) 1f else (1f - balance) * 2f
    }
}

/** Закон громкости и преобразование калибровочного уровня в линейное усиление. */
object AudioGain {
    fun taper(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v < 0.005f) 0f else 10.0.pow((-50.0 * (1.0 - v) / 20.0)).toFloat()
    }

    fun migrateMaster(old: Float): Float {
        if (old <= 0f) return 0f
        return (1.0 + 20.0 * kotlin.math.log10(old.toDouble()) / 50.0).coerceIn(0.0, 1.0).toFloat()
    }

    fun fromDb(db: Float): Float = 10.0.pow(db.coerceIn(-12f, 0f).toDouble() / 20.0).toFloat()

    /** Convert legacy per-channel linear gains to normalized per-device trims. */
    fun migrateChannelGains(serverGain: Float, pointGain: Float): Pair<Float, Float> {
        fun db(g: Float) = if (g <= 0f) -12f else (20f * kotlin.math.log10(g)).coerceIn(-12f, 0f)
        val s = db(serverGain); val p = db(pointGain)
        val peak = maxOf(s, p)
        return (s - peak).coerceIn(-12f, 0f) to (p - peak).coerceIn(-12f, 0f)
    }
}

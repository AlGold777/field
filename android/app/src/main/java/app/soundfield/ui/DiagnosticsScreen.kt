package app.soundfield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.soundfield.core.Role
import app.soundfield.engine.UiState
import kotlin.math.abs

@Composable
fun DiagnosticsScreen(ui: UiState, onBack: () -> Unit) {
    val p = ui.pointDiag; val s = ui.serverDiag
    fun c(v: Double, good: Double, warn: Double): Color = when {
        v <= good -> SFColors.Good; v <= warn -> SFColors.Warn; else -> SFColors.Bad
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 24.dp)) {
        BackBar("Диагностика", onBack)
        Subtitle("Обновляется каждую секунду. Этот телефон — ${if (ui.role == Role.SERVER) "Server" else "Point"}.")
        Spacer(Modifier.height(16.dp))

        SFCard {
            H3("Рассогласование L/R")
            val est = if (p.valid && s.valid) abs(p.syncErrMs - s.syncErrMs) + p.clockUncMs else null
            DiagRow("Оценка по часам", est?.let { "±%.2f мс".format(it) } ?: "—", est?.let { c(it, 2.0, 5.0) } ?: SFColors.Text)
            DiagRow("Акустически измерено", ui.calib.lastMeasuredMs?.let { "%+.1f мс".format(it) } ?: "не измерялось",
                ui.calib.lastMeasuredMs?.let { c(abs(it), 2.0, 5.0) } ?: SFColors.TextDim)
            DiagRow("Сдвиг Point (калибровка)", "%+.1f мс".format(ui.trimMs))
        }

        SFCard {
            H3("Сеть (Point ← Server)")
            if (!p.valid) DiagRow("Нет данных", "—") else {
                DiagRow("RTT (среднее)", "%.1f мс".format(p.rttMs), c(p.rttMs, 15.0, 50.0))
                DiagRow("Разброс RTT", "%.1f мс".format(p.rttJitterMs), c(p.rttJitterMs, 5.0, 20.0))
                DiagRow("Jitter (RFC 3550)", "%.1f мс".format(p.jitterMs), c(p.jitterMs, 5.0, 20.0))
                DiagRow("Потери пакетов", "%.1f %%".format(p.lossPct), c(p.lossPct, 0.5, 3.0))
                DiagRow("Восстановлено NACK", "${p.recovered}")
                DiagRow("Опоздавшие / устаревшие", "${p.late} / ${p.stale}", if (p.late > 0) SFColors.Warn else SFColors.Text)
                DiagRow("Перепослано Server", "${s.resent}")
            }
        }

        SFCard {
            H3("Буфер и часы")
            if (!p.valid) DiagRow("Нет данных", "—") else {
                DiagRow("Размер буфера Point", "%.0f мс".format(p.bufferMs), if (p.bufferMs < 60) SFColors.Warn else SFColors.Good)
                DiagRow("Clock drift", "%+.1f ppm".format(p.driftPpm))
                DiagRow("Точность часов", "±%.2f мс".format(p.clockUncMs), c(p.clockUncMs, 1.0, 3.0))
                DiagRow("Часы синхронизированы", if (p.clockReady) "да" else "нет", if (p.clockReady) SFColors.Good else SFColors.Warn)
            }
        }

        SFCard {
            H3("Аудиовыход")
            DiagRow("Ошибка воспроизведения Server", if (s.valid) "%+.2f мс".format(s.syncErrMs) else "—")
            DiagRow("Ошибка воспроизведения Point", if (p.valid) "%+.2f мс".format(p.syncErrMs) else "—")
            DiagRow("Underrun Server", if (s.valid) "${s.underruns}" else "—", if (s.underruns > 0) SFColors.Warn else SFColors.Text)
            DiagRow("Underrun Point", if (p.valid) "${p.underruns}" else "—", if (p.underruns > 0) SFColors.Warn else SFColors.Text)
            DiagRow("Выходная задержка Server", if (s.valid) "%.1f мс".format(s.latencyMs) else "—")
            DiagRow("Выходная задержка Point", if (p.valid) "%.1f мс".format(p.latencyMs) else "—")
        }
    }
}

package app.soundfield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.soundfield.core.Role
import app.soundfield.BuildConfig
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState
import kotlin.math.abs

@Composable
fun DiagnosticsScreen(ui: UiState, engine: Engine, onBack: () -> Unit) {
    val p = ui.pointDiag; val s = ui.serverDiag
    fun c(v: Double, good: Double, warn: Double): Color = when {
        v <= good -> SFColors.Good; v <= warn -> SFColors.Warn; else -> SFColors.Bad
    }
    fun signalColor(diag: app.soundfield.engine.Diag): Color = when {
        !diag.playing -> SFColors.TextDim
        diag.outputRmsDbfs >= -50.0 -> SFColors.Good
        diag.outputRmsDbfs >= -70.0 -> SFColors.Warn
        else -> SFColors.Bad
    }
    fun signalText(diag: app.soundfield.engine.Diag): String =
        if (diag.valid) "%.1f dBFS".format(diag.outputRmsDbfs) else "—"
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
            DiagRow("Путь аудио / часов", "${p.audioPath} / ${p.clockPath}")
            DiagRow("Аудиопакеты на Point", when {
                p.audioAgeMs < 0 -> "не поступают"
                else -> "${p.audioAgeMs} мс назад"
            }, when {
                p.audioAgeMs < 0 || p.audioAgeMs >= 5_000 -> SFColors.Bad
                p.audioAgeMs >= 2_000 -> SFColors.Warn
                else -> SFColors.Good
            })
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
                DiagRow("Запас аудио Point", "%.1f с".format(p.audioBufferSeconds),
                    if (p.audioBufferSeconds < 3.0 && p.audioPath == "tcp") SFColors.Warn else SFColors.Good)
                DiagRow("Скорость заполнения", "%.0f кадров/с".format(p.audioFillFramesPerSec))
                DiagRow("Clock drift", "%+.1f ppm".format(p.driftPpm))
                DiagRow("Точность часов", "±%.2f мс".format(p.clockUncMs), c(p.clockUncMs, 1.0, 3.0))
                DiagRow("Часы синхронизированы", if (p.clockReady) "да" else "нет", if (p.clockReady) SFColors.Good else SFColors.Warn)
            }
        }

        SFCard {
            H3("Аудиовыход")
            DiagRow("Звук на выходе · Server", signalText(s), signalColor(s))
            DiagRow("Звук на выходе · Point", signalText(p), signalColor(p))
            DiagRow("Сигнал канала Server → Point", if (s.valid) "%.1f dBFS".format(s.serverChannelRmsDbfs) else "—",
                if (s.serverChannelRmsDbfs > -50.0) SFColors.Good else SFColors.TextDim)
            DiagRow("Ошибка воспроизведения Server", if (s.valid) "%+.2f мс".format(s.syncErrMs) else "—")
            DiagRow("Ошибка воспроизведения Point", if (p.valid) "%+.2f мс".format(p.syncErrMs) else "—")
            DiagRow("Underrun Server", if (s.valid) "${s.underruns}" else "—", if (s.underruns > 0) SFColors.Warn else SFColors.Text)
            DiagRow("Underrun Point", if (p.valid) "${p.underruns}" else "—", if (p.underruns > 0) SFColors.Warn else SFColors.Text)
            DiagRow("Выходная задержка Server", if (s.valid) "%.1f мс".format(s.latencyMs) else "—")
            DiagRow("Выходная задержка Point", if (p.valid) "%.1f мс".format(p.latencyMs) else "—")
            DiagRow("Частота выхода · Server", if (s.outputTimestampValid) "%.0f кадров/с".format(s.outputFramesPerSecond) else "нет таймстампа")
            DiagRow("Частота выхода · Point", if (p.outputTimestampValid) "%.0f кадров/с".format(p.outputFramesPerSecond) else "нет таймстампа")
            DiagRow("Громкость / mute · Server",
                if (s.valid) "%d/%d · %s".format(s.musicVolume, s.musicVolumeMax, if (s.musicMuted) "mute" else "on") else "—")
            DiagRow("Громкость / mute · Point",
                if (p.valid) "%d/%d · %s".format(p.musicVolume, p.musicVolumeMax, if (p.musicMuted) "mute" else "on") else "—")
            DiagRow("Маршрут Server / Point", "%s / %s".format(s.audioRoute, p.audioRoute))
            DiagRow("Аудиофокус Server / Point", "%s / %s".format(if (s.audioFocus) "есть" else "нет",
                if (p.audioFocus) "есть" else "нет"))
        }

        SFCard {
            H3("Активные инциденты")
            if (ui.activeIncidents.isEmpty()) DiagRow("Нарушений нет", "—", SFColors.Good)
            ui.activeIncidents.forEach { DiagRow(it, "идёт", SFColors.Bad) }
        }

        if (BuildConfig.DEBUG && ui.inPair) {
            SFCard {
                H3("Тестовые сбои · debug")
                when (ui.role) {
                    Role.SERVER -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Остановить UDP-аудио", color = SFColors.Text, modifier = Modifier.weight(1f))
                            Switch(checked = ui.debugUdpAudioStopped, onCheckedChange = engine::setDebugUdpAudioStopped)
                        }
                        Subtitle("TCP останется подключён; звук и часы должны перейти на TCP.")
                    }
                    Role.POINT -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Обнулить выход Point", color = SFColors.Text, modifier = Modifier.weight(1f))
                            Switch(checked = ui.debugPointOutputSilenced, onCheckedChange = engine::setDebugPointOutputSilenced)
                        }
                        Subtitle("UDP продолжит поступать. Ожидается POINT_SILENT_OUTPUT.")
                    }
                }
            }
        }

        SFCard {
            H3("Журнал подключения")
            if (ui.connLog.isEmpty()) DiagRow("Событий нет", "—")
            ui.connLog.asReversed().take(30).forEach { line ->
                Text(line, color = SFColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ProblemMarkButton(engine) }
        TelemetryButton(ui, engine)
    }
}

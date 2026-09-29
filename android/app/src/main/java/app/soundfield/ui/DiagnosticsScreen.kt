package app.soundfield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.soundfield.core.Role
import app.soundfield.core.Ch
import app.soundfield.core.BalanceLaw
import app.soundfield.BuildConfig
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(ui: UiState, engine: Engine, onRequestSyncProbe: () -> Unit, onBack: () -> Unit) {
    var developerMode by rememberSaveable { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapMs by remember { mutableLongStateOf(0L) }
    var spatialStats by remember { mutableStateOf(engine.spatialDeveloperSnapshot()) }
    val p = ui.pointDiag; val s = ui.serverDiag
    LaunchedEffect(developerMode, ui.spatialAvailable) {
        while (developerMode && ui.spatialAvailable) {
            spatialStats = engine.spatialDeveloperSnapshot()
            delay(500)
        }
    }
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
            H3("Рассогласование L/R · по оценке часов")
            val est = if (p.valid && s.valid) abs(p.syncErrMs - s.syncErrMs) + p.clockUncMs else null
            DiagRow("Оценка по часам", est?.let { "±%.2f мс".format(it) } ?: "—", est?.let { c(it, 2.0, 5.0) } ?: SFColors.Text)
            DiagRow("Акустически измерено", ui.calib.lastMeasuredMs?.let { "%+.1f мс".format(it) } ?: "не измерялось",
                ui.calib.lastMeasuredMs?.let { c(abs(it), 2.0, 5.0) } ?: SFColors.TextDim)
            val delayState = when (ui.delayCalib.state) {
                "auto" -> "авто, разброс %.1f мс".format(ui.delayCalib.spreadMs)
                "manual" -> "вручную"
                else -> "не откалибрована"
            }
            DiagRow("Задержка", "trim %+.1f мс · %s".format(ui.trimMs, delayState))
            val serverCh = Ch.other(ui.pointChannel)
            val lDb = if (serverCh == Ch.L) ui.levelTrimServerDb else ui.levelTrimPointDb
            val rDb = if (serverCh == Ch.R) ui.levelTrimServerDb else ui.levelTrimPointDb
            val levelSource = when (ui.levelCalib.state) { "ear" -> "на слух"; "mic" -> "микрофон"; "manual" -> "вручную"; else -> "не настроено" }
            DiagRow("Уровень", "L-тел %+.1f дБ, R-тел %+.1f дБ · %s".format(lDb, rDb, levelSource))
            val balanceP = 2f * ui.balance - 1f
            val balanceDb = -BalanceLaw.differenceDb(ui.balance)
            val balanceText = when {
                kotlin.math.abs(balanceP) < 0.001f -> "Центр"
                kotlin.math.abs(balanceP) >= 0.995f -> if (balanceP < 0) "Только L" else "Только R"
                else -> "%s +%.1f дБ".format(if (balanceP < 0) "L" else "R", balanceDb)
            }
            DiagRow("Баланс", balanceText)
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
                DiagRow("Скачок модели часов p50 / p95 / max",
                    "%.1f / %.1f / %.1f мкс".format(p.clockModelStepP50Us, p.clockModelStepP95Us, p.clockModelStepMaxUs))
            }
        }

        SFCard {
            H3("Аудиовыход")
            DiagRow("Звук на выходе · Server", signalText(s), signalColor(s))
            DiagRow("Звук на выходе · Point", signalText(p), signalColor(p))
            DiagRow("Сигнал канала Server → Point", if (s.valid) "%.1f dBFS".format(s.serverChannelRmsDbfs) else "—",
                if (s.serverChannelRmsDbfs > -50.0) SFColors.Good else SFColors.TextDim)
            DiagRow("Ошибка воспроизведения Server", if (s.valid) syncErrorText(s.syncErrUs) else "—")
            DiagRow("Ошибка воспроизведения Point", if (p.valid) syncErrorText(p.syncErrUs) else "—")
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

        SFCard {
            H3("Работа в фоне")
            DiagRow("Заморозки процесса", "${ui.freezeCount}")
            DiagRow("Суммарный простой", "%.1f с".format(ui.freezeDurationMs / 1000.0))
        }

        if (developerMode && ui.inPair && ui.role == Role.SERVER) {
            SFCard {
                H3("Инструменты разработчика")
                Button(onClick = onRequestSyncProbe, enabled = !ui.syncProbeRunning) {
                    Text(if (ui.syncProbeRunning) "Замер выполняется…" else "Замер синхронизации (2 мин)")
                }
                if (ui.syncProbeRunning || ui.syncProbeResult.isNotBlank()) Subtitle(ui.syncProbeResult)
            }
        }

        if (developerMode && BuildConfig.DEBUG && ui.inPair) {
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

        if (developerMode && ui.spatialAvailable) {
            SFCard {
                H3("Пространство · разработчик")
                DiagRow("Side / Mid", "%+.1f дБ".format(spatialStats.sideMidDb))
                DiagRow("Корреляция L/R", "%.3f".format(spatialStats.correlation))
                DiagRow("Скорость DSP", "%.1f×".format(spatialStats.realtimeFactor))
                DiagRow("Запас кэша", "%.0f мс".format(spatialStats.cacheAheadMs))
                DiagRow("Перезапуски / недоборы", "${spatialStats.restarts} / ${spatialStats.misses}")
                DiagRow("Версия", app.soundfield.dsp.DSP_VERSION)
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
        Spacer(Modifier.height(12.dp))
        Text("SoundField ${BuildConfig.VERSION_NAME}", color = SFColors.TextDim, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable {
                val now = android.os.SystemClock.elapsedRealtime()
                versionTapCount = if (now - lastVersionTapMs <= 2_000L) versionTapCount + 1 else 1
                lastVersionTapMs = now
                if (versionTapCount >= 3) {
                    developerMode = !developerMode
                    versionTapCount = 0
                }
            }.padding(8.dp))
    }
}

private fun syncErrorText(errorUs: Double): String = if (kotlin.math.abs(errorUs) < 1_000.0)
    "%+.1f мкс".format(errorUs) else "%+.3f мс".format(errorUs / 1_000.0)

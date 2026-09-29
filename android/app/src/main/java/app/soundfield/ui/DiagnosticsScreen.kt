package app.soundfield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.DisposableEffect
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
import app.soundfield.core.SpatialAbAnswer
import app.soundfield.core.SpatialAbComparison
import app.soundfield.core.SpatialAbSession
import app.soundfield.core.SpatialLevel
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(ui: UiState, engine: Engine, onRequestSyncProbe: () -> Unit, onBack: () -> Unit) {
    var developerMode by rememberSaveable { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var spatialStats by remember { mutableStateOf(engine.spatialDeveloperSnapshot()) }
    var xtcReport by remember { mutableStateOf(engine.xtcDeveloperSnapshot()) }
    var xtcReportLevel by rememberSaveable { mutableStateOf(SpatialLevel.SOFT) }
    var abComparison by rememberSaveable { mutableStateOf(SpatialAbComparison.OFF_SOFT.wire) }
    var abSession by remember { mutableStateOf<SpatialAbSession?>(null) }
    var abFinishedResult by remember { mutableStateOf<SpatialAbSession?>(null) }
    var auditionRequest by remember { mutableStateOf<Pair<Long, SpatialLevel>?>(null) }
    var auditionSequence by remember { mutableLongStateOf(0L) }
    val p = ui.pointDiag; val s = ui.serverDiag
    LaunchedEffect(developerMode, ui.spatialAvailable) {
        while (developerMode && ui.spatialAvailable) {
            spatialStats = engine.spatialDeveloperSnapshot()
            xtcReport = engine.xtcDeveloperSnapshot()
            delay(500)
        }
    }
    LaunchedEffect(developerMode, ui.spatialAvailable, ui.mode) {
        if ((!developerMode || !ui.spatialAvailable || ui.mode != app.soundfield.core.SourceMode.MUSIC) && abSession != null)
            abSession = null
    }
    LaunchedEffect(abSession?.id, auditionRequest?.first) {
        val session = abSession ?: return@LaunchedEffect
        val request = auditionRequest ?: return@LaunchedEffect
        engine.spatialAbAudition(request.second, session)
        var ready = false
        for (attempt in 0 until 200) {
            if (engine.spatialAbReadyAt(session.anchorMs, request.second)) { ready = true; break }
            delay(25)
        }
        if (!ready) return@LaunchedEffect
        engine.play()
        delay(8_000)
        engine.pause()
        auditionRequest = null
    }
    DisposableEffect(abSession?.id) {
        val captured = abSession
        onDispose { captured?.let(engine::finishSpatialAb) }
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
        Subtitle(if (ui.soloMode) "Обновляется каждую секунду. Этот телефон играет один." else "Обновляется каждую секунду. Этот телефон — ${if (ui.role == Role.SERVER) "Server" else "Point"}.")
        Spacer(Modifier.height(16.dp))

        if (!ui.soloMode) {
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
                DiagRow("Ограничитель / компенсация", "%.1f / %+.1f дБ".format(spatialStats.limiterDb, spatialStats.loudnessCompDb))
                if (ui.soloMode) {
                    DiagRow("XTC в обработке", if (spatialStats.xtcApplied) "да" else "нет")
                    if (spatialStats.xtcDesignTimedOut) DiagRow("Расчёт XTC", "не успел за 4 с", SFColors.Warn)
                }
                DiagRow("Версия", app.soundfield.dsp.DSP_VERSION)
            }
            if (ui.soloMode) {
                SFCard {
                    H3("XTC · модельная оценка")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(SpatialLevel.SOFT, SpatialLevel.STRONG).forEach { level ->
                            FilterChip(selected = xtcReportLevel == level,
                                onClick = { xtcReportLevel = level },
                                label = { Text(if (level == SpatialLevel.SOFT) "Мягко" else "Сильно") })
                        }
                    }
                    Button(onClick = { engine.requestXtcDeveloperReport(xtcReportLevel) }) {
                        Text(if (xtcReport.status == "расчёт") "Считаю…" else "Рассчитать отчёт")
                    }
                    if (xtcReport.status == "готово") {
                        DiagRow("Расстояние динамиков", "%.2f м".format(xtcReport.spacingMeters))
                        DiagRow("β / время расчёта", "%.4f / %.0f мс".format(xtcReport.beta, xtcReport.designMs))
                        DiagRow("Медиана / худшая точка", "%.1f / %.1f дБ".format(
                            xtcReport.predictedMedianDb, xtcReport.predictedWorstDb))
                    } else if (xtcReport.status != "не рассчитано") Subtitle(xtcReport.status)
                    var xtcOverride by remember { mutableStateOf(engine.xtcDeveloperOverrideEnabled()) }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Включить XTC на этом телефоне (проверка на слух)", color = SFColors.Text, fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        Switch(checked = xtcOverride, onCheckedChange = { xtcOverride = it; engine.setXtcDeveloperOverride(it) })
                    }
                    Subtitle("Оценка модельная и не подтверждает модель телефона. Переключатель только для разработчика: без него XTC включается лишь для моделей с xtcVerified=true в таблице устройств.")
                }
            }
        }

        if (developerMode && ui.spatialAvailable && ui.role == Role.SERVER) {
            SFCard {
                H3("Слепое A/B · разработчик")
                val activeSession = abSession
                if (activeSession == null) {
                    val result = abFinishedResult
                    if (result != null) {
                        Subtitle("Распознано ${result.correctDetections} из 8 · p = %.4f".format(result.pValue))
                        Subtitle("Результат записан в files/spatial_ab.")
                        Button(onClick = { abFinishedResult = null }) { Text("Новая серия") }
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpatialAbComparison.entries.forEach { comparison ->
                                FilterChip(selected = abComparison == comparison.wire,
                                    onClick = { abComparison = comparison.wire },
                                    label = { Text(when (comparison) {
                                        SpatialAbComparison.OFF_SOFT -> "Выкл / Мягко"
                                        SpatialAbComparison.OFF_STRONG -> "Выкл / Сильно"
                                        SpatialAbComparison.SOFT_STRONG -> "Мягко / Сильно"
                                    }) })
                            }
                        }
                        Subtitle("Серия: 6 сравнений и 2 контрольных повтора A=A. Каждый вариант запускает один и тот же фрагмент на 8 секунд.")
                        Button(enabled = ui.durationMs > 0, onClick = {
                            val comparison = SpatialAbComparison.entries.first { it.wire == abComparison }
                            val session = SpatialAbSession.create(comparison, ui.spatial,
                                engine.positionMs().coerceAtMost((ui.durationMs - 8_000L).coerceAtLeast(0L)),
                                ui.playing, engine.spatialAbCompensationSnapshotDb())
                            abFinishedResult = null
                            abSession = session
                            engine.pause()
                            engine.seekMs(session.anchorMs)
                        }) { Text("Начать серию") }
                    }
                } else if (activeSession.complete) {
                    Subtitle("Распознано ${activeSession.correctDetections} из 8 · p = %.4f".format(activeSession.pValue))
                    Button(onClick = {
                        engine.saveSpatialAbResult(activeSession)
                        abFinishedResult = activeSession
                        abSession = null
                    }) { Text("Сохранить результат и восстановить звук") }
                } else {
                    val trial = activeSession.trials[activeSession.currentTrial]
                    var answer by remember(activeSession.id, activeSession.currentTrial) {
                        mutableStateOf(SpatialAbAnswer())
                    }
                    Subtitle("Попытка ${trial.index} из 8")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            auditionSequence++
                            auditionRequest = auditionSequence to trial.variantA
                        }) { Text("Прослушать A") }
                        Button(onClick = {
                            auditionSequence++
                            auditionRequest = auditionSequence to trial.variantB
                        }) { Text("Прослушать B") }
                    }
                    Subtitle("Варианты звучат по 8 секунд с одной и той же позиции.")
                    AbYesNoRow("Слышна разница?", answer.detectsDifference) {
                        answer = answer.copy(detectsDifference = it)
                    }
                    AbPreferenceRow("Где шире?", answer.width) { answer = answer.copy(width = it) }
                    AbPreferenceRow("Где лучше центр?", answer.center) { answer = answer.copy(center = it) }
                    AbPreferenceRow("Где естественнее?", answer.naturalness) { answer = answer.copy(naturalness = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = answer.complete, onClick = {
                            engine.pause()
                            engine.recordSpatialAbTrial(activeSession, activeSession.currentTrial, answer)
                            val next = activeSession.answerCurrent(answer)
                            if (next.complete) {
                                engine.saveSpatialAbResult(next)
                                abFinishedResult = next
                                abSession = null
                            } else abSession = next
                        }) { Text(if (trial.index == 8) "Завершить" else "Следующая") }
                        Button(onClick = { engine.pause(); abSession = null }) { Text("Отменить") }
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
        Spacer(Modifier.height(12.dp))
        Text("SoundField ${BuildConfig.VERSION_NAME}", color = SFColors.TextDim, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable {
                // Три нажатия на номер версии без ограничения по времени между ними; счётчик сбрасывается при выходе с экрана.
                versionTapCount += 1
                if (versionTapCount >= 3) {
                    developerMode = !developerMode
                    versionTapCount = 0
                }
            }.padding(8.dp))
    }
}

@Composable
private fun AbYesNoRow(label: String, selected: Boolean?, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SFColors.Text, modifier = Modifier.weight(1f))
        FilterChip(selected = selected == true, onClick = { onSelect(true) }, label = { Text("Да") })
        Spacer(Modifier.width(6.dp))
        FilterChip(selected = selected == false, onClick = { onSelect(false) }, label = { Text("Нет") })
    }
}

@Composable
private fun AbPreferenceRow(label: String, selected: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = SFColors.Text, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("a" to "A", "b" to "B", "same" to "Равно").forEach { (value, title) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(title) })
            }
        }
    }
}

private fun syncErrorText(errorUs: Double): String = if (kotlin.math.abs(errorUs) < 1_000.0)
    "%+.1f мкс".format(errorUs) else "%+.3f мс".format(errorUs / 1_000.0)

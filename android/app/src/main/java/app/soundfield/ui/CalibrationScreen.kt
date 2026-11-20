package app.soundfield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.core.Ch
import app.soundfield.core.Role
import app.soundfield.core.SF
import app.soundfield.core.SourceMode
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CalibrationScreen(ui: UiState, engine: Engine, requestMic: () -> Unit, onBack: () -> Unit, micDeniedForever: Boolean = false) {
    // Экран калибровки не гаснет: погасший экран в фоне останавливает запись микрофона.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val c = ui.calib
    val pointCh = ui.pointChannel
    val serverCh = Ch.other(pointCh)
    val serverDevice = if (ui.role == Role.SERVER) ui.myName else ui.peerName
    val pointDevice = if (ui.role == Role.POINT) ui.myName else ui.peerName
    val levelReady = ui.delayCalib.state != "none" && ui.gainLaw >= 2 && !ui.calib.running
    val gateText = if (ui.delayCalib.state == "none")
        "Сначала выполните калибровку задержки: пока телефоны звучат с разным запаздыванием, звук тянет к одному из них, и уровнем это не исправить"
    else "Обновите второй телефон для калибровки уровня"
    val offsetNow = if (serverCh == Ch.L) ui.levelTrimServerDb - ui.levelTrimPointDb
        else ui.levelTrimPointDb - ui.levelTrimServerDb

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 24.dp)) {
        BackBar("Калибровка", {
            if (ui.mode == SourceMode.LEVEL_MATCH) engine.stopLevelMatch(commit = false)
            onBack()
        })
        Subtitle("Выполняйте шаги по порядку: задержка → уровень → проверка центра. Ошибка задержки смещает образ сильнее, чем небольшая разница громкости.")
        Spacer(Modifier.height(16.dp))

        SFCard {
            H3("Шаг 1 · Задержка")
            DiagRow("Состояние", delayStatus(ui.delayCalib.state, ui.delayCalib.spreadMs))
            Text("Положите телефоны рядом (10–20 см), динамиками вверх. Во время автокалибровки не шумите.",
                color = SFColors.Text, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            if (c.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = SFColors.Accent1)
                Spacer(Modifier.height(10.dp)); Text(c.stage, color = SFColors.Warn, fontSize = 14.sp)
            } else {
                PrimaryButton("🎯  Запустить автокалибровку") { engine.calibrate() }
                SecondaryButton("📏  Только измерить рассогласование") { engine.measure() }
            }
            if (c.needsMicPermission && ui.role == Role.SERVER) {
                if (micDeniedForever) {
                    Text("Доступ к микрофону запрещён. Откройте настройки приложения и включите «Микрофон».",
                        color = SFColors.Warn, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                }
                SecondaryButton(if (micDeniedForever) "⚙  Открыть настройки приложения" else "🎤  Разрешить доступ к микрофону") { requestMic() }
            }
            if (c.resultText.isNotEmpty() && !c.running) {
                Text(c.resultText, color = if (c.resultOk) SFColors.Good else SFColors.Warn, fontSize = 14.sp, lineHeight = 20.sp)
            }

            var trim by remember(ui.trimMs) { mutableFloatStateOf(ui.trimMs.toFloat()) }
            val limit = SF.MAX_TRIM_MS.toFloat()
            SliderRow("Ручной сдвиг Point", "%+.1f мс".format(trim), trim, SFColors.Accent1, -limit..limit,
                onChange = { trim = (it * 2).roundToInt() / 2f }, onDone = { engine.setTrimMs(trim.toDouble()) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-5f, -1f, -0.5f, 0.5f, 1f, 5f).forEach { d ->
                    OutlinedButton(onClick = { trim = (trim + d).coerceIn(-limit, limit); engine.setTrimMs(trim.toDouble()) },
                        contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.weight(1f)) {
                        Text(if (d > 0) "+${fmt(d)}" else fmt(d), fontSize = 11.sp, color = SFColors.Text)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Метроном", color = SFColors.Text, fontWeight = FontWeight.SemiBold)
                    Subtitle("Щелчки звучат одновременно в обоих каналах. Двигайте сдвиг, пока они не сольются.")
                }
                Switch(ui.mode == SourceMode.METRONOME, onCheckedChange = engine::metronome,
                    colors = SwitchDefaults.colors(checkedTrackColor = SFColors.Accent1))
            }
            GhostButton("Сбросить сдвиг в 0", color = SFColors.TextDim) { trim = 0f; engine.resetTrim() }
        }

        SFCard {
            H3("Шаг 2 · Уровень телефонов")
            Text("Выровняйте громкость устройств. Уровень хранится за телефоном, а баланс L/R остаётся отдельной пользовательской настройкой.",
                color = SFColors.Text, fontSize = 14.sp, lineHeight = 20.sp)
            if (!levelReady) {
                Spacer(Modifier.height(8.dp))
                Text(gateText, color = SFColors.Warn, fontSize = 13.sp, lineHeight = 19.sp)
            }
            if (ui.mode == SourceMode.LEVEL_MATCH) {
                Spacer(Modifier.height(8.dp))
                Text(if (ui.mode == SourceMode.LEVEL_MATCH) "Сейчас звучит по очереди L, затем R" else "",
                    color = SFColors.Accent1, fontWeight = FontWeight.SemiBold)
                var offset by remember { mutableFloatStateOf(offsetNow) }
                var levelDragging by remember { mutableStateOf(false) }
                LaunchedEffect(ui.levelTrimServerDb, ui.levelTrimPointDb, levelDragging) {
                    if (!levelDragging) offset = if (serverCh == Ch.L) ui.levelTrimServerDb - ui.levelTrimPointDb
                        else ui.levelTrimPointDb - ui.levelTrimServerDb
                }
                SliderRow("L громче  ·  R громче", "%+.1f дБ".format(offset), offset, SFColors.Accent1, -12f..12f,
                    onChange = { offset = (it * 2).roundToInt() / 2f; levelDragging = true; engine.setLevelOffsetDb(offset, false) },
                    onDone = { levelDragging = false; engine.setLevelOffsetDb(offset, true) })
                Text("Сядьте на одинаковом расстоянии от телефонов (±10 см). Двигайте ползунок, пока обе стороны не станут одинаково громкими.",
                    color = SFColors.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Готово", Modifier.weight(1f)) { engine.stopLevelMatch(commit = true) }
                    SecondaryButton("Отмена", Modifier.weight(1f)) { engine.stopLevelMatch(commit = false) }
                }
            } else {
                PrimaryButton("Выровнять уровень на слух", enabled = levelReady) { engine.startLevelMatch() }
            }
            Spacer(Modifier.height(4.dp))
            levelDeviceRow("$serverDevice (${Ch.name(serverCh)}) · Server",
                "server", ui.levelTrimServerDb, levelReady, engine)
            levelDeviceRow("$pointDevice (${Ch.name(pointCh)}) · Point",
                "point", ui.levelTrimPointDb, levelReady, engine)
            GhostButton("Сбросить уровни", color = SFColors.TextDim, enabled = levelReady) { engine.resetLevelTrims() }
        }

        SFCard {
            H3("Шаг 3 · Проверка центра")
            if (!levelReady) Text(gateText, color = SFColors.Warn, fontSize = 13.sp, lineHeight = 19.sp)
            Text("Сядьте на одинаковом расстоянии от телефонов. Розовый шум должен звучать из точки посередине.",
                color = SFColors.Text, fontSize = 14.sp, lineHeight = 20.sp)
            PrimaryButton("Проверка центра", enabled = levelReady) { engine.startCenterCheck() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton("В центре ✓", Modifier.weight(1f), enabled = levelReady) { engine.centerCheckAnswer("center") }
                SecondaryButton("Тянет влево", Modifier.weight(1f), enabled = levelReady) { engine.centerCheckAnswer("left") }
                SecondaryButton("Тянет вправо", Modifier.weight(1f), enabled = levelReady) { engine.centerCheckAnswer("right") }
            }
        }

        SFCard {
            H3("Эксперимент · измерение микрофонами")
            Text("Разрешение нужно на обоих телефонах. Сверка: телефоны рядом, внешний шумовой источник по центру в 30–50 см; выполните запись, поменяйте телефоны местами и запишите ещё раз. Затем верните их в рабочие позиции.",
                color = SFColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
            SecondaryButton("Разрешить микрофон на этом телефоне") { requestMic() }
            if (ui.role == Role.SERVER) {
                SecondaryButton("Сверить микрофоны · положение 1", enabled = levelReady) { engine.measureMicReference(1) }
                SecondaryButton("Сверить микрофоны · положение 2", enabled = levelReady && ui.levelMeasureText.contains("d1 =")) { engine.measureMicReference(2) }
                Text("После сверки: поставьте телефоны на места для прослушивания. Измерение длится около 24 с.",
                    color = SFColors.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
                PrimaryButton("Измерить микрофонами (эксперимент)",
                    enabled = levelReady && ui.levelMeasureText.contains("Сверка микрофонов готова")) { engine.measureLevels() }
                Text("Результат показывается для диагностики и автоматически не применяется.",
                    color = SFColors.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
            } else {
                Subtitle("После выдачи разрешения запустите оба измерения с телефона Server.")
            }
            if (ui.levelMeasureText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(ui.levelMeasureText, color = SFColors.Accent1, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }

        SFCard {
            H3("Проверка каналов")
            Subtitle("Три сигнала слева, затем три справа — убедитесь, что телефоны стоят на своих местах.")
            SecondaryButton("🔈  Проверить L / R") { engine.channelTest() }
        }
        SFCard {
            H3("Текущие значения")
            DiagRow("Выходная задержка Server", ms(ui.serverDiag.latencyMs, ui.serverDiag.valid))
            DiagRow("Выходная задержка Point", ms(ui.pointDiag.latencyMs, ui.pointDiag.valid))
            DiagRow("Сдвиг Point", "%+.1f мс".format(ui.trimMs))
            DiagRow("Уровень Server", "%+.1f дБ".format(ui.levelTrimServerDb))
            DiagRow("Уровень Point", "%+.1f дБ".format(ui.levelTrimPointDb))
        }
    }
}

@Composable
private fun levelDeviceRow(label: String, device: String, db: Float, enabled: Boolean, engine: Engine) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SFColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("%+.1f дБ".format(db), color = SFColors.Accent1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        TextButton(enabled = enabled && db > -12f, onClick = { engine.setLevelTrim(device, db - 0.5f) }) { Text("−0.5") }
        TextButton(enabled = enabled && db < 0f, onClick = { engine.setLevelTrim(device, db + 0.5f) }) { Text("+0.5") }
    }
}

private fun delayStatus(state: String, spreadMs: Double): String = when (state) {
    "auto" -> "Авто, разброс %.1f мс".format(spreadMs)
    "manual" -> "Вручную"
    else -> "Не откалибрована"
}
private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
private fun ms(v: Double, valid: Boolean) = if (valid) "%.1f мс".format(v) else "—"

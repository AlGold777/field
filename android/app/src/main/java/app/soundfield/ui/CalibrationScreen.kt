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
import app.soundfield.core.Role
import app.soundfield.core.SF
import app.soundfield.core.SourceMode
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState

@Composable
fun CalibrationScreen(ui: UiState, engine: Engine, requestMic: () -> Unit, onBack: () -> Unit) {
    val c = ui.calib
    val serverName = if (ui.role == Role.SERVER) "этом телефоне" else "«${ui.peerName}»"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 24.dp)) {
        BackBar("Калибровка задержки", onBack)
        Subtitle("Разные модели телефонов выводят звук с разной задержкой. Калибровка измеряет её и выравнивает каналы.")
        Spacer(Modifier.height(16.dp))

        SFCard {
            H3("Автоматически · микрофон")
            Text("1. Положите телефоны рядом (10–20 см), динамиками вверх.\n2. Громкость — средняя или выше.\n" +
                "3. Нажмите кнопку и ~8 секунд не шумите.\nЗапись ведётся микрофоном на $serverName (Server).",
                color = SFColors.Text, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(14.dp))
            if (c.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = SFColors.Accent1)
                Spacer(Modifier.height(10.dp))
                Text(c.stage, color = SFColors.Warn, fontSize = 14.sp)
            } else {
                PrimaryButton("🎯  Запустить автокалибровку") { engine.calibrate() }
                SecondaryButton("📏  Только измерить рассогласование") { engine.measure() }
            }
            if (c.needsMicPermission && ui.role == Role.SERVER) {
                SecondaryButton("🎤  Разрешить доступ к микрофону") { requestMic() }
            }
            if (c.resultText.isNotEmpty() && !c.running) {
                Spacer(Modifier.height(6.dp))
                Text(c.resultText, color = if (c.resultOk) SFColors.Good else SFColors.Warn, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }

        SFCard {
            H3("Вручную · на слух")
            var trim by remember(ui.trimMs) { mutableFloatStateOf(ui.trimMs.toFloat()) }
            val lim = SF.MAX_TRIM_MS.toFloat()
            SliderRow("Сдвиг Point", "%+.1f мс".format(trim) + if (trim > 0) " (позже)" else if (trim < 0) " (раньше)" else "",
                trim, SFColors.Accent1, -lim..lim,
                onChange = { trim = (it * 2).toInt() / 2f }, onDone = { engine.setTrimMs(trim.toDouble()) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-5f, -1f, -0.5f, 0.5f, 1f, 5f).forEach { d ->
                    OutlinedButton(onClick = { trim = (trim + d).coerceIn(-lim, lim); engine.setTrimMs(trim.toDouble()) },
                        contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.weight(1f)) {
                        Text(if (d > 0) "+${fmt(d)}" else fmt(d), fontSize = 12.sp, color = SFColors.Text)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val metronomeOn = ui.mode == SourceMode.METRONOME
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Метроном", color = SFColors.Text, fontWeight = FontWeight.SemiBold)
                    Subtitle("Щелчки одновременно в обоих каналах. Двигайте сдвиг, пока не услышите один щелчок.")
                }
                Switch(checked = metronomeOn, onCheckedChange = { engine.metronome(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = SFColors.Accent1))
            }
            Spacer(Modifier.height(8.dp))
            GhostButton("Сбросить сдвиг в 0", color = SFColors.TextDim) { trim = 0f; engine.setTrimMs(0.0) }
        }

        SFCard {
            H3("Проверка каналов")
            Subtitle("Три сигнала слева, затем три справа — убедитесь, что телефоны стоят на своих местах.")
            Spacer(Modifier.height(10.dp))
            SecondaryButton("🔈  Проверить L / R") { engine.channelTest() }
        }

        SFCard {
            H3("Текущие значения")
            DiagRow("Выходная задержка Server", ms(ui.serverDiag.latencyMs, ui.serverDiag.valid))
            DiagRow("Выходная задержка Point", ms(ui.pointDiag.latencyMs, ui.pointDiag.valid))
            DiagRow("Сдвиг Point (калибровка)", "%+.1f мс".format(ui.trimMs))
            DiagRow("Акустически измерено L/R", ui.calib.lastMeasuredMs?.let { "%+.1f мс".format(it) } ?: "—",
                ui.calib.lastMeasuredMs?.let { if (kotlin.math.abs(it) < 2) SFColors.Good else SFColors.Warn } ?: SFColors.Text)
        }
    }
}

private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
private fun ms(v: Double, valid: Boolean) = if (valid) "%.1f мс".format(v) else "—"

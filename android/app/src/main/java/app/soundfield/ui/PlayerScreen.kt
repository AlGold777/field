package app.soundfield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import app.soundfield.core.AudioGain
import app.soundfield.core.Ch
import app.soundfield.core.Role
import app.soundfield.core.SourceMode
import app.soundfield.core.BalanceLaw
import app.soundfield.core.SpatialLevel
import app.soundfield.core.SpatialSettings
import app.soundfield.engine.Engine
import app.soundfield.engine.Phase
import app.soundfield.engine.UiState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

@Composable
fun PlayerScreen(ui: UiState, engine: Engine, openCalibration: () -> Unit, openDiagnostics: () -> Unit,
    openTrackPicker: () -> Unit, openEqualizer: () -> Unit, openSpatial: () -> Unit, openPairing: () -> Unit) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmCancelFileTransfer by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(ui.inPair, ui.soloMode) { if (ui.inPair || ui.soloMode) while (true) { pos = engine.positionMs(); delay(200) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                ui.soloMode -> {
                    PairStatusButton("Подключить телефон", SFColors.Accent1, openPairing)
                    ui.remembered?.let { rec ->
                        Spacer(Modifier.width(8.dp))
                        PairStatusButton("«${rec.peerName}»", SFColors.Good, { engine.resumeRemembered() })
                    }
                }
                ui.phase == Phase.RECONNECTING && ui.role == Role.POINT -> PairStatusButton("Переподключение…", SFColors.Warn, { confirmDisconnect = true })
                ui.phase == Phase.RECONNECTING -> PairStatusButton("Ждём «${ui.peerName}»", SFColors.Warn, { confirmDisconnect = true })
                else -> PairStatusButton("Стереопара активна", SFColors.Good, { confirmDisconnect = true })
            }
            Spacer(Modifier.weight(1f))
            if (!ui.soloMode) Badge(if (ui.role == Role.SERVER) "SERVER" else "POINT", SFColors.Master)
        }

        if (!ui.soloMode && ui.multiWifiWarning.isNotBlank()) {
            Text(ui.multiWifiWarning, color = SFColors.Warn, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp)).background(SFColors.Warn.copy(alpha = 0.12f)).padding(12.dp))
        }

        // --- стереосцена ---
        if (!ui.soloMode) {
            val serverCh = Ch.other(ui.pointChannel)
            val leftIsMe = ui.myChannel == Ch.L
            val leftName = if (leftIsMe) "Этот телефон" else ui.peerName
            val rightName = if (!leftIsMe) "Этот телефон" else ui.peerName
            fun roleOf(ch: Int) = if (ch == serverCh) "Server" else "Point"
            Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                SpeakerCircle("L", Ch.L, ui.playing, leftName, roleOf(Ch.L), leftIsMe) { engine.swapChannels() }
                Box(Modifier.width(48.dp).height(44.dp).offset(y = (-20).dp)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Поменять каналы L и R") { engine.swapChannels() },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.width(40.dp).height(2.dp).background(SFColors.accent))
                }
                SpeakerCircle("R", Ch.R, ui.playing, rightName, roleOf(Ch.R), !leftIsMe) { engine.swapChannels() }
            }

            // --- индикатор синхронизации ---
            val pd = ui.pointDiag; val sd = ui.serverDiag
            val lrEstUs = if (pd.valid && sd.valid) abs(pd.syncErrUs - sd.syncErrUs) + pd.clockUncMs * 1_000.0 else null
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(SFColors.Card).clickable { openDiagnostics() }
                .padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    if (lrEstUs == null) "⚡ Синхронизация часов…"
                    else {
                        val estimate = if (lrEstUs < 1_000.0) "±%.0f мкс".format(lrEstUs)
                            else "±%.2f мс".format(lrEstUs / 1_000.0)
                        "⚡ L/R: $estimate : RTT %.0f мс : буф. %.0f мс".format(pd.rttMs, pd.bufferMs)
                    },
                    color = if (lrEstUs != null && lrEstUs < 2_000.0) SFColors.Good else SFColors.Warn,
                    fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
        }

        if (ui.mode != SourceMode.MUSIC) {
            SFCard {
                Text(when (ui.mode) {
                    SourceMode.METRONOME -> "🥁 Метроном для ручной подстройки"
                    SourceMode.CHANNEL_TEST -> "🔈 Проверка каналов: сначала L, потом R"
                    SourceMode.LEVEL_MATCH -> "Уровень: L и R звучат по очереди"
                    SourceMode.CENTER_CHECK -> "Проверка центра"
                    SourceMode.LEVEL_MEASURE -> "Экспериментальное измерение микрофонами"
                    else -> "🎯 Идёт калибровка"
                }, color = SFColors.Warn, fontWeight = FontWeight.SemiBold)
            }
        }

        // --- уведомления над названием трека ---
        if (ui.fileTransferText.isNotBlank()) {
            if (ui.role == Role.POINT) {
                val progress = ui.fileTransferText.substringAfterLast('·', "").trim()
                    .takeIf { it.endsWith("%") }
                TextButton(onClick = { confirmCancelFileTransfer = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(progress?.let { "Send $it" } ?: "Send…", color = SFColors.Accent1, fontSize = 12.sp)
                }
            } else {
                Text(ui.fileTransferText, color = SFColors.Accent1, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }
        } else if (ui.loadingTracks || ui.audioBuffering || ui.decodingProgress != null) {
            Text(
                if (ui.decodingProgress != null) "Подготовка аудиофайла… ${(ui.decodingProgress * 100).roundToInt()}%"
                else if (ui.audioBuffering) "Буферизация аудио…" else "Подготовка аудиофайла…",
                color = SFColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // --- трек ---
        // «Название - Исполнитель» одной строкой; для тестовых сигналов — только название
        val trackTitle = if (ui.mode == SourceMode.MUSIC) app.soundfield.engine.trackLabel(ui.title, ui.artist) else ui.title
        Text(trackTitle, color = SFColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().pointerInput(engine, swipeThreshold) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = {
                        if (distance <= -swipeThreshold) engine.nextTrack()
                        else if (distance >= swipeThreshold) engine.previousTrack()
                    },
                    onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                )
            }.clickable(onClick = openTrackPicker))
        Text(
            ui.artist.ifBlank { "---" },
            color = SFColors.TextDim,
            fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        val dur = ui.durationMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { engine.seekMs((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = SFColors.Accent1, activeTrackColor = SFColors.Accent1,
                inactiveTrackColor = Color(0x1AFFFFFF)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(dragging?.let { (it * dur).toLong() } ?: pos), color = SFColors.TextDim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(ui.durationMs), color = SFColors.TextDim, fontSize = 12.sp)
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            RoundIcon("⏪") { engine.skipMs(-10_000) }
            Box(Modifier.size(76.dp).clip(CircleShape).background(SFColors.accent).clickable { engine.togglePlay() },
                contentAlignment = Alignment.Center) {
                Text(if (ui.playing || ui.audioBuffering) "❚❚" else "▶", color = Color.White, fontSize = 28.sp)
            }
            RoundIcon("⏩") { engine.skipMs(10_000) }
        }

        QueueCard(ui, engine)

        // --- громкость ---
        SFCard {
            Text("Sound", color = SFColors.TextDim, fontSize = 13.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
            var volume by remember { mutableFloatStateOf(AudioGain.masterToSlider(ui.master)) }
            var volumeDragging by remember { mutableStateOf(false) }
            LaunchedEffect(ui.master, volumeDragging) { if (!volumeDragging) volume = AudioGain.masterToSlider(ui.master) }
            var balance by remember { mutableFloatStateOf(ui.balance) }
            var balanceDragging by remember { mutableStateOf(false) }
            var wasInCenter by remember { mutableStateOf(abs(ui.balance - 0.5f) < 0.015f) }
            val haptic = LocalHapticFeedback.current
            LaunchedEffect(ui.balance, balanceDragging) { if (!balanceDragging) balance = ui.balance }
            SliderRow("Громкость", "${(volume * 100).roundToInt()}%", volume, SFColors.Master,
                enabled = ui.mode != SourceMode.LEVEL_MEASURE,
                onChange = { volumeDragging = true; volume = it; engine.setVolume("M", AudioGain.sliderToMaster(it), finished = false) },
                onDone = { volumeDragging = false; engine.setVolume("M", AudioGain.sliderToMaster(volume), finished = true) })
            if (!ui.soloMode) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Баланс между телефонами", color = SFColors.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    balance = 0.5f; wasInCenter = true; engine.setBalance(0.5f, finished = true)
                }) { Text(balanceLabel(balance), color = SFColors.Accent1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (!ui.soloMode) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("L", color = SFColors.LeftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = balance,
                    onValueChange = { raw ->
                        val centered = abs(raw - 0.5f) < 0.015f
                        if (centered && !wasInCenter) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        wasInCenter = centered
                        val value = if (centered) 0.5f else raw
                        balanceDragging = true; balance = value; engine.setBalance(value, finished = false)
                    },
                    onValueChangeFinished = { balanceDragging = false; engine.setBalance(balance, finished = true) },
                    colors = SliderDefaults.colors(thumbColor = SFColors.Accent1, activeTrackColor = SFColors.Accent1,
                        inactiveTrackColor = Color(0x1AFFFFFF)),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        .drawWithContent {
                            drawContent()
                            drawLine(SFColors.TextDim.copy(alpha = 0.75f),
                                Offset(size.width / 2f, size.height / 2f - 5.dp.toPx()),
                                Offset(size.width / 2f, size.height / 2f + 5.dp.toPx()), strokeWidth = 2.dp.toPx())
                        }
                        .semantics {
                            contentDescription = "Баланс между телефонами"
                            stateDescription = "Баланс: ${balanceLabel(balance)}"
                        },
                )
                Text("R", color = SFColors.RightText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!ui.soloMode) LaunchedEffect(balance) { if (!balanceDragging) wasInCenter = abs(balance - 0.5f) < 0.015f }
            EqualizerCompactBlock(ui, engine, openEqualizer)
            SpatialCompactBlock(ui, engine, openSpatial)
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!ui.soloMode) PlayerActionButton("Калибровка", Modifier.weight(1f), openCalibration)
            PlayerActionButton("Диагностика", Modifier.weight(1f), openDiagnostics)
            TelemetryIconButton(ui, engine)
        }
        ui.telemetryFile?.let { Subtitle("Сохранено: $it", modifier = Modifier.padding(bottom = 8.dp)) }
    }

    if (confirmDisconnect && ui.inPair) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Вы хотите разорвать пару?") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; engine.disconnect() }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Нет") } },
        )
    }

    if (confirmCancelFileTransfer) {
        AlertDialog(
            onDismissRequest = { confirmCancelFileTransfer = false },
            title = { Text("Отменить передачу?") },
            text = { Text("Отправка аудиофайлов на Server будет остановлена.") },
            confirmButton = {
                TextButton(onClick = { confirmCancelFileTransfer = false; engine.cancelFileTransfer() }) {
                    Text("Отменить передачу", color = SFColors.Warn)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelFileTransfer = false }) { Text("Продолжить") }
            },
        )
    }
}

@Composable
private fun RoundIcon(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape).background(SFColors.Secondary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(icon, color = Color.White, fontSize = 20.sp) }
}

private fun balanceLabel(value: Float): String {
    val p = 2f * value.coerceIn(0f, 1f) - 1f
    if (abs(p) < 0.001f) return "Центр"
    if (abs(p) >= 0.995f) return if (p < 0f) "Только L" else "Только R"
    val db = -BalanceLaw.differenceDb(value)
    val rounded = (db * 2f).roundToInt() / 2f
    val channel = if (p < 0f) "L" else "R"
    return String.format(Locale.US, "%s +%.1f дБ", channel, rounded)
}

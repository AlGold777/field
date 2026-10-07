package app.soundfield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onLongClick
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
    var showConnectChooser by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(ui.inPair, ui.soloMode) { if (ui.inPair || ui.soloMode) while (true) { pos = engine.positionMs(); delay(200) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(104.dp).height(36.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    ui.soloMode -> {
                        ConnectStatusButton(
                            onClick = { if (ui.remembered != null) engine.resumeRemembered() else showConnectChooser = true },
                            onOpenChooser = { showConnectChooser = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ui.phase == Phase.RECONNECTING && ui.role == Role.POINT -> PairStatusButton("Переподключение…", SFColors.Warn, { confirmDisconnect = true }, modifier = Modifier.fillMaxSize())
                    ui.phase == Phase.RECONNECTING -> PairStatusButton("Ждём «${ui.peerName}»", SFColors.Warn, { confirmDisconnect = true }, modifier = Modifier.fillMaxSize())
                    else -> PairStatusButton("Active", SFColors.Good, { confirmDisconnect = true }, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.weight(1f))
            val switchMode: () -> Unit = {
                if (ui.soloMode && ui.remembered == null) openPairing()
                else engine.toggleSoloMode()
            }
            Box(
                Modifier.pointerInput(ui.soloMode, ui.phase, ui.remembered) {
                    detectTapGestures(onLongPress = { switchMode() })
                }.padding(8.dp).semantics {
                    contentDescription = "Режим проигрывателя"
                    onLongClick(label = if (ui.soloMode) "Восстановить стереопару" else "Открыть локальный плеер Solo") {
                        switchMode()
                        true
                    }
                    stateDescription = if (ui.soloMode) "Solo" else if (ui.role == Role.SERVER) "Server" else "Point"
                },
            ) {
                Badge(if (ui.soloMode) "Solo" else if (ui.role == Role.SERVER) "Server" else "Point",
                    if (ui.soloMode) SFColors.Accent1 else SFColors.Master,
                    modifier = Modifier.width(76.dp).height(32.dp))
            }
        }

        // Резервируем место под предупреждение, чтобы переключение Solo не сдвигало сцену.
        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            if (!ui.soloMode && ui.multiWifiWarning.isNotBlank()) {
                Text(ui.multiWifiWarning, color = SFColors.Warn, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(SFColors.Warn.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }

        if (showConnectChooser) {
            ConnectDeviceDialog(
                ui = ui,
                onDismiss = { showConnectChooser = false },
                onResumeRemembered = {
                    showConnectChooser = false
                    engine.resumeRemembered()
                },
                onConnectNearby = { peer ->
                    showConnectChooser = false
                    engine.invite(peer)
                },
            )
        }

        // --- стереосцена остаётся видимой и в локальном Solo ---
        val serverCh = Ch.other(ui.pointChannel)
        val leftIsMe = ui.myChannel == Ch.L
        val leftName = if (ui.soloMode) "MyPhone" else if (leftIsMe) "Этот телефон" else ui.peerName
        val rightName = if (ui.soloMode) "MyPhone" else if (!leftIsMe) "Этот телефон" else ui.peerName
        fun roleOf(ch: Int) = if (ch == serverCh) "Server" else "Point"
        Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            SpeakerCircle("L", Ch.L, ui.playing, leftName, if (ui.soloMode) "" else roleOf(Ch.L), ui.soloMode || leftIsMe) {
                if (!ui.soloMode) engine.swapChannels()
            }
            Box(Modifier.width(48.dp).height(44.dp).offset(y = (-20).dp)
                .then(if (ui.soloMode) Modifier else Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = "Поменять каналы L и R") { engine.swapChannels() }),
                contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(2.dp).background(SFColors.accent))
            }
            SpeakerCircle("R", Ch.R, ui.playing, rightName, if (ui.soloMode) "" else roleOf(Ch.R), ui.soloMode || !leftIsMe) {
                if (!ui.soloMode) engine.swapChannels()
            }
        }

        Box(Modifier.fillMaxWidth().height(36.dp)) {
            if (!ui.soloMode) {
                // --- индикатор синхронизации; слот остаётся на месте и в Solo ---
                val pd = ui.pointDiag; val sd = ui.serverDiag
                val lrEstUs = if (pd.valid && sd.valid) abs(pd.syncErrUs - sd.syncErrUs) + pd.clockUncMs * 1_000.0 else null
                Row(Modifier.fillMaxSize().clip(CircleShape).background(SFColors.Card).clickable { openDiagnostics() }
                    .padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
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
            }
        }
        Spacer(Modifier.height(20.dp))

        val operationStatus = when (ui.mode) {
            SourceMode.MUSIC -> null
            SourceMode.METRONOME -> "🥁 Метроном для ручной подстройки"
            SourceMode.CHANNEL_TEST -> "🔈 Проверка каналов: сначала L, потом R"
            SourceMode.LEVEL_MATCH -> "Уровень: L и R звучат по очереди"
            SourceMode.CENTER_CHECK -> "Проверка центра"
            SourceMode.LEVEL_MEASURE -> "Экспериментальное измерение микрофонами"
            else -> "🎯 Идёт калибровка"
        }

        // --- уведомления над названием трека ---
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
            if (ui.fileTransferText.isNotBlank()) {
                if (ui.role == Role.POINT) {
                    val progress = ui.fileTransferText.substringAfterLast('·', "").trim()
                        .takeIf { it.endsWith("%") }
                    Text(progress?.let { "Send $it" } ?: "Send…", color = SFColors.Accent1, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { confirmCancelFileTransfer = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp))
                } else {
                    Text(ui.fileTransferText, color = SFColors.Accent1, fontSize = 12.sp, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
            } else if (operationStatus != null) {
                Text(operationStatus, color = SFColors.Warn, fontSize = 12.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            } else if (ui.loadingTracks || ui.audioBuffering || ui.decodingProgress != null) {
                Text(
                    if (ui.decodingProgress != null) "Подготовка аудиофайла… ${(ui.decodingProgress * 100).roundToInt()}%"
                    else if (ui.audioBuffering) "Буферизация аудио…" else "Подготовка аудиофайла…",
                    color = SFColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // --- трек ---
        // «Название - Исполнитель» одной строкой; для тестовых сигналов — только название
        val trackTitle = if (ui.mode == SourceMode.MUSIC) app.soundfield.engine.trackLabel(ui.title, ui.artist) else ui.title
        Text(trackTitle, color = SFColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
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
            fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
            title = { Text("Disconnect ${(ui.peerName.ifBlank { "Phone" }).take(7)}?") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; engine.disconnect() }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("No") } },
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
private fun ConnectDeviceDialog(
    ui: UiState,
    onDismiss: () -> Unit,
    onResumeRemembered: () -> Unit,
    onConnectNearby: (app.soundfield.engine.Peer) -> Unit,
) {
    val remembered = ui.remembered
    val nearby = ui.nearby.filterNot { it.id == remembered?.peerId }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(22.dp),
            color = SFColors.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, SFColors.CardBorder),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                Text("Connect", color = SFColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                remembered?.let { record ->
                    Text("Последний телефон", color = SFColors.TextDim, fontSize = 9.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                    DeviceChoiceTile(
                        name = record.peerName,
                        platform = record.peerPlatform,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onResumeRemembered,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Text("Доступные телефоны", color = SFColors.TextDim, fontSize = 9.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
                if (nearby.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 62.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchingDots()
                        Spacer(Modifier.width(10.dp))
                        Text("Ищем телефоны…", color = SFColors.TextDim, fontSize = 13.sp)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        nearby.forEach { peer ->
                            DeviceChoiceTile(
                                name = peer.name,
                                platform = peer.platform,
                                modifier = Modifier.width(72.dp),
                                onClick = { onConnectNearby(peer) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceChoiceTile(name: String, platform: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isApple = platform.equals("ios", ignoreCase = true)
    Column(
        modifier.clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 42.dp, height = 58.dp)) {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF273A68), Color(0xFF111A31))))
                    .border(1.dp, Color(0xA6D9E2FF), RoundedCornerShape(11.dp)),
            )
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                    .size(width = if (isApple) 16.dp else 4.dp, height = if (isApple) 3.dp else 4.dp)
                    .clip(CircleShape).background(Color(0xFF080E1D)),
            )
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                    .size(width = 12.dp, height = 1.dp)
                    .clip(RoundedCornerShape(1.dp)).background(Color(0x99E9EFFF)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(name, color = SFColors.Text, fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 1)
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

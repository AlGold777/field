package app.soundfield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.core.Ch
import app.soundfield.core.Role
import app.soundfield.core.SourceMode
import app.soundfield.engine.Engine
import app.soundfield.engine.Phase
import app.soundfield.engine.UiState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(ui: UiState, engine: Engine, openCalibration: () -> Unit, openDiagnostics: () -> Unit,
    openTrackPicker: () -> Unit) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(Unit) { while (true) { pos = engine.positionMs(); delay(200) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                ui.phase == Phase.RECONNECTING && ui.role == Role.POINT -> StatusPill("Переподключение…", SFColors.Warn)
                ui.phase == Phase.RECONNECTING -> StatusPill("Ждём «${ui.peerName}»", SFColors.Warn)
                else -> StatusPill("Стереопара активна", SFColors.Good)
            }
            Spacer(Modifier.weight(1f))
            Badge(if (ui.role == Role.SERVER) "SERVER" else "POINT", SFColors.Master)
        }

        // --- стереосцена ---
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
        val lrEst = if (pd.valid && sd.valid) abs(pd.syncErrMs - sd.syncErrMs) + pd.clockUncMs else null
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(SFColors.Card).clickable { openDiagnostics() }
            .padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                if (lrEst == null) "⚡ Синхронизация часов…"
                else "⚡ L/R: ±%.1f мс • RTT %.0f мс • буфер %.0f мс".format(lrEst, pd.rttMs, pd.bufferMs),
                color = if (lrEst != null && lrEst < 2) SFColors.Good else SFColors.Warn, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))

        if (ui.mode != SourceMode.MUSIC) {
            SFCard {
                Text(when (ui.mode) {
                    SourceMode.METRONOME -> "🥁 Метроном для ручной подстройки"
                    SourceMode.CHANNEL_TEST -> "🔈 Проверка каналов: сначала L, потом R"
                    else -> "🎯 Идёт калибровка"
                }, color = SFColors.Warn, fontWeight = FontWeight.SemiBold)
            }
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
            when {
                ui.loadingTracks -> "Подготовка аудиофайла…"
                ui.role == Role.POINT -> "Нажмите на название, чтобы добавить музыку на Server"
                else -> "Нажмите на название, чтобы добавить FLAC, WAV или MP3"
            },
            color = SFColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
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
                Text(if (ui.playing) "❚❚" else "▶", color = Color.White, fontSize = 28.sp)
            }
            RoundIcon("⏩") { engine.skipMs(10_000) }
        }

        QueueCard(ui, engine)

        // --- громкость ---
        SFCard {
            H3("Громкость")
            var m by remember(ui.master) { mutableFloatStateOf(ui.master) }
            var l by remember(ui.volL) { mutableFloatStateOf(ui.volL) }
            var r by remember(ui.volR) { mutableFloatStateOf(ui.volR) }
            SliderRow("Общая (оба канала)", "${(m * 100).roundToInt()}%", m, SFColors.Master,
                onChange = { m = it; engine.setVolume("M", it) })
            SliderRow("Левый канал · L", "${(l * 100).roundToInt()}%", l, SFColors.Left,
                onChange = { l = it; engine.setVolume("L", it) })
            SliderRow("Правый канал · R", "${(r * 100).roundToInt()}%", r, SFColors.Right,
                onChange = { r = it; engine.setVolume("R", it) })
        }

        SecondaryButton("🎯  Калибровка задержки") { openCalibration() }
        SecondaryButton("📊  Диагностика пары") { openDiagnostics() }
        DangerButton("⛓  Разорвать пару") { confirmDisconnect = true }
        TelemetryButton(ui, engine)
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Разорвать пару?") },
            text = { Text("Воспроизведение остановится на обоих телефонах. Пара сохранится — её можно быстро подключить снова.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; engine.disconnect() }) { Text("Разорвать") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RoundIcon(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape).background(SFColors.Secondary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(icon, color = Color.White, fontSize = 20.sp) }
}

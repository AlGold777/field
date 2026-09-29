package app.soundfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.core.EqMagnet
import app.soundfield.core.EqMagnetMath
import app.soundfield.core.EqPreset
import app.soundfield.core.EqPresets
import app.soundfield.core.EqSettings
import app.soundfield.core.EqualizerMath
import app.soundfield.core.withBand
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow

@Composable
fun EqualizerCompactBlock(ui: UiState, engine: Engine, openEqualizer: () -> Unit) {
    val state = ui.eq
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = ui.eqAvailable, onClick = openEqualizer)) {
                Text("Эквалайзер", color = SFColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(eqStatus(state), color = SFColors.TextDim, fontSize = 12.sp)
            }
            MiniEqCurve(state, ui.eqCurveDb, Modifier.width(82.dp).height(34.dp).clickable(enabled = ui.eqAvailable, onClick = openEqualizer))
            Text(if (ui.eqExpanded) "⌃" else "⌄", color = SFColors.Accent1, fontSize = 24.sp,
                modifier = Modifier.padding(start = 12.dp).clickable { engine.setEqExpanded(!ui.eqExpanded) })
        }
        if (!ui.eqAvailable) {
            Text("Эквалайзер в паре требует SoundField 1.8.0 на обоих телефонах",
                color = SFColors.Warn, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (ui.eqExpanded) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Включить", color = SFColors.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, enabled = ui.eqAvailable, onCheckedChange = {
                    engine.setEq(state.copy(enabled = it, bypass = false), finished = true)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Для: ${ui.eqRouteLabel}", color = SFColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Запас ${formatDb(ui.eqPreampDb)}", color = SFColors.Accent1, fontSize = 12.sp)
            }
            TextButton(enabled = ui.eqAvailable, onClick = openEqualizer, modifier = Modifier.align(Alignment.End)) {
                Text("Настроить эквалайзер", color = SFColors.Accent1)
            }
            if (!ui.eqAvailable) {
                Text("Эквалайзер в паре требует SoundField 1.8.0 на обоих телефонах",
                    color = SFColors.Warn, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
fun EqualizerScreen(ui: UiState, engine: Engine, onBack: () -> Unit) {
    var saveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var renameDialog by remember { mutableStateOf(false) }
    var renameName by remember { mutableStateOf("") }
    var deletePreset by remember { mutableStateOf<String?>(null) }
    var spectrum by remember { mutableStateOf(List(60) { -100f }) }
    val haptic = LocalHapticFeedback.current
    val settings = ui.eq
    val phoneSpeaker = ui.eqRouteLabel.contains("динамик", ignoreCase = true)
    val allPresets = EqPresets.builtIn + ui.customEqPresets
    LaunchedEffect(settings.analyzerEnabled, ui.eqAvailable) {
        if (settings.analyzerEnabled && ui.eqAvailable) {
            while (true) {
                val fresh = withContext(Dispatchers.Default) { engine.eqSpectrumSnapshot() }
                spectrum = fresh.mapIndexed { i, value ->
                    val previous = spectrum.getOrElse(i) { -100f }
                    if (value > previous) value else previous + (value - previous) * 0.105f
                }
                delay(33)
            }
        } else spectrum = List(60) { -100f }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = SFColors.Accent1, fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text("Эквалайзер", color = SFColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Для: ${ui.eqRouteLabel}", color = SFColors.TextDim, fontSize = 12.sp)
            }
            Text(if (ui.eq.enabled) "Вкл" else "Выкл", color = if (ui.eq.enabled) SFColors.Good else SFColors.TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        if (!ui.eqAvailable) {
            SFCard {
                Text("Эквалайзер в паре требует SoundField 1.8.0 на обоих телефонах",
                    color = SFColors.Warn, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Эквалайзер", color = SFColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = settings.enabled, enabled = ui.eqAvailable, onCheckedChange = {
                engine.setEq(settings.copy(enabled = it, bypass = false), finished = true)
            })
        }

        EqualizerGraph(
            settings = settings,
            curveDb = ui.eqCurveDb,
            spectrum = if (settings.analyzerEnabled) spectrum else emptyList(),
            phoneSpeaker = phoneSpeaker,
            enabled = ui.eqAvailable,
            onChange = { next, finished -> engine.setEq(next, finished) },
            onCrossZero = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            onExactMode = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
        Spacer(Modifier.height(6.dp))
        Text("20 Гц                         1 кГц                         20 кГц", color = SFColors.TextDim, fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        EqualizerBandSliders(settings, phoneSpeaker, ui.eqAvailable) { next, finished -> engine.setEq(next, finished) }

        SFCard(Modifier.padding(top = 14.dp)) {
            Text("Управление", color = SFColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Магнит", color = SFColors.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                listOf(EqMagnet.OFF to "Выкл", EqMagnet.SOFT to "Мягко", EqMagnet.STRONG to "Сильно").forEach { (mode, label) ->
                    FilterChip(selected = settings.magnet == mode, onClick = {
                        engine.setEq(settings.copy(magnet = mode), finished = true)
                    }, label = { Text(label, fontSize = 11.sp) }, enabled = ui.eqAvailable,
                        modifier = Modifier.padding(start = 3.dp))
                }
            }
            EqSwitchRow("Без эквалайзера", "Сравнение с компенсацией громкости", settings.bypass, ui.eqAvailable) {
                engine.setEq(settings.copy(bypass = it), finished = true)
            }
            EqSwitchRow("Анализатор", "Спектр выхода этого телефона", settings.analyzerEnabled, ui.eqAvailable) {
                engine.setEqAnalyzer(it)
            }
            EqSwitchRow("Сохранять громкость", "Автозапас и компенсация средней громкости", settings.preserveLoudness,
                ui.eqAvailable) { engine.setEq(settings.copy(preserveLoudness = it), finished = true) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Запас ${formatDb(ui.eqPreampDb)}", color = SFColors.Accent1, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(enabled = ui.eqAvailable, onClick = {
                    engine.setEq(settings.copy(gainsDb = List(10) { 0f }, enabled = false, preset = "Ровно", bypass = false), true)
                }) { Text("Сброс") }
            }
        }

        Text("Пресеты", color = SFColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            allPresets.forEach { preset ->
                FilterChip(selected = settings.preset == preset.name, onClick = {
                    val gains = if (phoneSpeaker) preset.gainsDb.mapIndexed { i, value -> if (i < 2) value.coerceAtMost(3f) else value }
                    else preset.gainsDb
                    engine.applyEqPreset(preset.copy(gainsDb = gains))
                },
                    label = { Text(preset.name, fontSize = 12.sp) }, enabled = ui.eqAvailable)
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = ui.eqAvailable, onClick = { saveName = ""; saveDialog = true }) { Text("Сохранить как…") }
            if (settings.preset in ui.customEqPresets.map { it.name }) {
                TextButton(enabled = ui.eqAvailable, onClick = { renameName = settings.preset; renameDialog = true }) { Text("Переименовать") }
                TextButton(enabled = ui.eqAvailable, onClick = { deletePreset = settings.preset }) { Text("Удалить") }
            }
        }
    }

    if (saveDialog) AlertDialog(
        onDismissRequest = { saveDialog = false }, title = { Text("Сохранить пресет") },
        text = { OutlinedTextField(value = saveName, onValueChange = { saveName = it.take(32) }, singleLine = true, label = { Text("Название") }) },
        confirmButton = { TextButton(onClick = { engine.saveEqPreset(saveName); saveDialog = false }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Отмена") } },
    )
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false }, title = { Text("Переименовать пресет") },
        text = { OutlinedTextField(value = renameName, onValueChange = { renameName = it.take(32) }, singleLine = true, label = { Text("Название") }) },
        confirmButton = { TextButton(onClick = { engine.renameEqPreset(settings.preset, renameName); renameDialog = false }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Отмена") } },
    )
    deletePreset?.let { name -> AlertDialog(
        onDismissRequest = { deletePreset = null }, title = { Text("Удалить пресет «$name»?") },
        confirmButton = { TextButton(onClick = { engine.deleteEqPreset(name); deletePreset = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deletePreset = null }) { Text("Отмена") } },
    ) }
}

@Composable
private fun EqSwitchRow(title: String, detail: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SFColors.Text, fontSize = 14.sp)
            Text(detail, color = SFColors.TextDim, fontSize = 11.sp)
        }
        Switch(checked = value, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun EqualizerGraph(
    settings: EqSettings,
    curveDb: List<Float>,
    spectrum: List<Float>,
    phoneSpeaker: Boolean,
    enabled: Boolean,
    onChange: (EqSettings, Boolean) -> Unit,
    onCrossZero: () -> Unit,
    onExactMode: () -> Unit,
) {
    val current by rememberUpdatedState(settings)
    val freq = EqualizerMath.frequencies
    Canvas(Modifier.fillMaxWidth().height(218.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x20212D49))
        .semantics { contentDescription = "График эквалайзера. Перетаскивайте кривую для настройки полос" }
        .pointerInput(enabled) {
            var working = current.gainsDb
                var activeBand = -1
                var bandBase = working
                var previousValue = 0f
            detectDragGestures(
                onDragStart = {
                    working = current.gainsDb
                    activeBand = -1
                    bandBase = working
                    previousValue = working.firstOrNull() ?: 0f
                },
                onDragEnd = { onChange(current.copy(gainsDb = working, preset = "Свой", bypass = false), true) },
                onDragCancel = {},
                onDrag = { change, _ ->
                    if (!enabled) return@detectDragGestures
                    val index = change.position.x.toDouble().nearestBand(size.width.toFloat())
                    if (index != activeBand) { activeBand = index; bandBase = working; previousValue = working[index] }
                    val old = bandBase[index]
                    val requested = yToGain(change.position.y, size.height.toFloat())
                    if ((previousValue < 0f && requested >= 0f) || (previousValue > 0f && requested <= 0f)) onCrossZero()
                    previousValue = requested
                    working = EqMagnetMath.move(bandBase, index, requested - bandBase[index], current.magnet, phoneSpeaker)
                    onChange(current.copy(gainsDb = working, preset = "Свой", bypass = false), false)
                    change.consume()
                },
            )
        }
        .pointerInput(enabled, phoneSpeaker) {
            var working = current.gainsDb
            var activeBand = -1
            var bandBase = working
            var previousValue = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    if (!enabled) return@detectDragGesturesAfterLongPress
                    onExactMode()
                    working = current.gainsDb
                    activeBand = position.x.toDouble().nearestBand(size.width.toFloat())
                    bandBase = working
                    previousValue = working[activeBand]
                },
                onDragEnd = { onChange(current.copy(gainsDb = working, preset = "Свой", bypass = false), true) },
                onDragCancel = {},
                onDrag = { change, _ ->
                    if (!enabled) return@detectDragGesturesAfterLongPress
                    val index = change.position.x.toDouble().nearestBand(size.width.toFloat())
                    if (index != activeBand) { activeBand = index; bandBase = working; previousValue = working[index] }
                    val requested = yToGain(change.position.y, size.height.toFloat())
                    if ((previousValue < 0f && requested >= 0f) || (previousValue > 0f && requested <= 0f)) onCrossZero()
                    previousValue = requested
                    working = EqMagnetMath.move(bandBase, index, requested - bandBase[index], EqMagnet.OFF, phoneSpeaker)
                    onChange(current.copy(gainsDb = working, preset = "Свой", bypass = false), false)
                    change.consume()
                },
            )
        }
        .pointerInput(enabled) {
            detectTapGestures(onDoubleTap = { offset ->
                if (!enabled) return@detectTapGestures
                val index = offset.x.toDouble().nearestBand(size.width.toFloat())
                if (settings.gainsDb[index] != 0f) onCrossZero()
                onChange(current.copy(gainsDb = current.gainsDb.withBand(index, 0f), preset = "Свой"), true)
            })
        }) {
        val w = size.width; val h = size.height
        val center = h / 2f
        fun y(db: Double) = center - (db.coerceIn(-12.0, 12.0).toFloat() / 12f) * (h * 0.43f)
        if (phoneSpeaker) {
            val shadeEnd = xForFrequency(200.0, w)
            drawRect(Color(0x334B5563), topLeft = Offset.Zero, size = Size(shadeEnd, h))
            drawContext.canvas.nativeCanvas.let { canvas ->
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.LTGRAY; textSize = 10.dp.toPx() }
                canvas.save(); canvas.rotate(-90f, shadeEnd * 0.48f, h * 0.52f)
                canvas.drawText("динамик телефона здесь почти не звучит", shadeEnd * 0.48f - h * 0.45f, h * 0.52f, paint)
                canvas.restore()
            }
        }
        if (spectrum.isNotEmpty()) {
            val bw = w / spectrum.size
            spectrum.forEachIndexed { i, db ->
                val amount = ((db + 80f) / 80f).coerceIn(0f, 1f)
                val barH = amount * h * 0.42f
                drawRect(SFColors.Accent1.copy(alpha = 0.18f), Offset(i * bw, h - barH), Size(max(1f, bw - 2f), barH))
            }
        }
        listOf(-12, -6, 0, 6, 12).forEach { db ->
            drawLine(if (db == 0) SFColors.TextDim.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f),
                Offset(0f, y(db.toDouble())), Offset(w, y(db.toDouble())), strokeWidth = if (db == 0) 1.5f else 1f)
        }
        freq.forEach { f ->
            val x = xForFrequency(f, w)
            drawLine(Color.White.copy(alpha = 0.12f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        val curve = curveDb.ifEmpty { List(256) { 0f } }
        fun drawArea(positive: Boolean, color: Color) {
            val path = Path()
            var open = false
            for (i in curve.indices) {
                val x = w * i / (curve.size - 1)
                val pointY = y(curve[i].toDouble())
                val isSide = if (positive) curve[i] >= 0.0 else curve[i] <= 0.0
                if (isSide) {
                    if (!open) { path.moveTo(x, center); path.lineTo(x, pointY); open = true }
                    else path.lineTo(x, pointY)
                } else if (open) { path.lineTo(x, center); path.close(); open = false }
            }
            if (open) { path.lineTo(w, center); path.close() }
            drawPath(path, color.copy(alpha = 0.18f))
        }
        drawArea(true, Color(0xFFFF9A62)); drawArea(false, Color(0xFF60A5FA))
        val line = Path()
        curve.forEachIndexed { i, value ->
            val p = Offset(w * i / (curve.size - 1), y(value.toDouble()))
            if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }
        drawPath(line, SFColors.Accent1, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        freq.forEachIndexed { i, f ->
            val x = xForFrequency(f, w); val yy = y(settings.gainsDb.getOrElse(i) { 0f }.toDouble())
            drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(x, yy))
            drawCircle(SFColors.Accent1, radius = 3.dp.toPx(), center = Offset(x, yy))
        }
        listOf("+12", "+6", "0", "−6", "−12").forEachIndexed { i, label ->
            drawContext.canvas.nativeCanvas.drawText(label, 5.dp.toPx(), y((12 - i * 6).toDouble()) - 3.dp.toPx(),
                android.graphics.Paint().apply { color = android.graphics.Color.LTGRAY; textSize = 9.dp.toPx() })
        }
    }
}

private fun List<Float>.nearestBand(x: Float, width: Float): Int = x.toDouble().nearestBand(width)
private fun Double.nearestBand(width: Float): Int {
    val f = frequencyForX(this.toFloat(), width)
    return EqualizerMath.frequencies.indices.minByOrNull { abs(ln(EqualizerMath.frequencies[it] / f)) } ?: 0
}
private fun xForFrequency(f: Double, width: Float): Float =
    (ln(f / 20.0) / ln(20_000.0 / 20.0) * width).toFloat().coerceIn(0f, width)
private fun frequencyForX(x: Float, width: Float): Double =
    20.0 * (20_000.0 / 20.0).pow((x / width.coerceAtLeast(1f)).toDouble())
private fun yToGain(y: Float, height: Float): Float = ((height / 2f - y) / (height * 0.43f) * 12f).coerceIn(-12f, 12f)
    .let { if (abs(it) <= 0.3f) 0f else it }

@Composable
private fun EqualizerBandSliders(settings: EqSettings, phoneSpeaker: Boolean, enabled: Boolean,
    onChange: (EqSettings, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(174.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        EqualizerMath.frequencies.forEachIndexed { index, frequency ->
            EqBandSlider(index, frequency, settings, phoneSpeaker, enabled, Modifier.weight(1f), onChange)
        }
    }
}

@Composable
private fun EqBandSlider(index: Int, frequency: Double, settings: EqSettings, phoneSpeaker: Boolean,
    enabled: Boolean, modifier: Modifier, onChange: (EqSettings, Boolean) -> Unit) {
    val current by rememberUpdatedState(settings)
    var dragBase by remember { mutableStateOf<List<Float>?>(null) }
    var displayValue by remember { mutableStateOf<Float?>(null) }
    val haptic = LocalHapticFeedback.current
    val bandLabel = when {
        frequency >= 1_000 -> "${(frequency / 1_000).toInt()}k"
        else -> frequency.toInt().toString()
    }
    Column(modifier.height(174.dp).semantics { contentDescription = "$bandLabel Гц, ${settings.gainsDb[index]} дБ" },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(displayValue?.let(::formatDb) ?: " ", color = SFColors.Accent1, fontSize = 10.sp, maxLines = 1)
        Canvas(Modifier.weight(1f).fillMaxWidth().pointerInput(index, enabled) {
            detectDragGestures(
                onDragStart = { offset ->
                    dragBase = current.gainsDb
                    val base = dragBase!!
                    val value = yToGain(offset.y, size.height.toFloat())
                    if (crossesZero(current.gainsDb[index], value)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    displayValue = value
                    onChange(current.copy(gainsDb = moveBand(base, index, value, current, phoneSpeaker), preset = "Свой", bypass = false), false)
                },
                onDragEnd = { onChange(current, true); dragBase = null; displayValue = null },
                onDragCancel = { dragBase = null; displayValue = null },
                onDrag = { change, _ ->
                    if (!enabled) return@detectDragGestures
                    val base = dragBase ?: current.gainsDb.also { dragBase = it }
                    val value = yToGain(change.position.y, size.height.toFloat())
                    if (crossesZero(displayValue ?: base[index], value)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    displayValue = value
                    onChange(current.copy(gainsDb = moveBand(base, index, value, current, phoneSpeaker), preset = "Свой", bypass = false), false)
                    change.consume()
                },
            )
        }.pointerInput(index, enabled, phoneSpeaker) {
            var exactBase: List<Float>? = null
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    if (!enabled) return@detectDragGesturesAfterLongPress
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    exactBase = current.gainsDb
                },
                onDragEnd = { onChange(current, true); exactBase = null; displayValue = null },
                onDragCancel = { exactBase = null; displayValue = null },
                onDrag = { change, _ ->
                    if (!enabled) return@detectDragGesturesAfterLongPress
                    val base = exactBase ?: current.gainsDb.also { exactBase = it }
                    val value = yToGain(change.position.y, size.height.toFloat())
                    if (crossesZero(displayValue ?: base[index], value)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    displayValue = value
                    onChange(current.copy(gainsDb = moveBand(base, index, value, current, phoneSpeaker, EqMagnet.OFF), preset = "Свой", bypass = false), false)
                    change.consume()
                },
            )
        }.pointerInput(index, enabled) {
            detectTapGestures(onDoubleTap = {
                if (enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(current.copy(gainsDb = current.gainsDb.withBand(index, 0f), preset = "Свой"), true)
                }
            })
        }) {
            val center = size.height / 2f
            val x = size.width / 2f
            val y = center - settings.gainsDb[index] / 12f * size.height * 0.43f
            drawLine(Color.White.copy(alpha = if (enabled) 0.35f else 0.12f), Offset(x, size.height * 0.07f), Offset(x, size.height * 0.93f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(SFColors.Accent1.copy(alpha = 0.8f), Offset(x, center), Offset(x, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(SFColors.Text, radius = 6.dp.toPx(), center = Offset(x, y))
            drawCircle(SFColors.Accent1, radius = 4.dp.toPx(), center = Offset(x, y))
        }
        Text(bandLabel, color = SFColors.TextDim, fontSize = 9.sp, maxLines = 1)
    }
}

private fun moveBand(base: List<Float>, index: Int, value: Float, settings: EqSettings, phoneSpeaker: Boolean,
    magnet: EqMagnet = settings.magnet): List<Float> {
    val target = if (phoneSpeaker && index < 2) value.coerceAtMost(3f) else value
    return EqMagnetMath.move(base, index, target - base[index], magnet, phoneSpeaker)
}

private fun crossesZero(from: Float, to: Float) = (from < 0f && to == 0f) || (from > 0f && to == 0f) || (from < 0f && to > 0f) || (from > 0f && to < 0f)

@Composable
private fun MiniEqCurve(settings: EqSettings, curveDb: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val curve = curveDb.ifEmpty { List(48) { 0f } }
        val mid = size.height / 2f
        val path = Path()
        curve.forEachIndexed { i, value ->
            val p = Offset(size.width * i / (curve.size - 1), mid - (value / 12.0).toFloat() * size.height * 0.45f)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, if (settings.enabled) SFColors.Accent1 else SFColors.TextDim, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun eqStatus(settings: EqSettings): String = when {
    !settings.enabled -> "Выкл"
    EqPresets.builtIn.any { it.name == settings.preset } -> settings.preset
    else -> "Свой"
}

private fun formatDb(value: Float): String = "${if (value > 0f) "+" else ""}${(value * 10f).roundToInt() / 10f} дБ"

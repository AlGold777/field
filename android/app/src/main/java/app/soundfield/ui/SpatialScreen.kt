package app.soundfield.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.core.SpatialSettings
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun SpatialCompactBlock(ui: UiState, engine: Engine, openSpatial: () -> Unit) {
    if (!ui.spatialAvailable) {
        if (ui.spatialHint.isNotBlank()) Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Text("Сцена", color = SFColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(ui.spatialHint, color = SFColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        return
    }
    val state = ui.spatial
    val profile = if (ui.soloMode) "solo_stereo_speaker" else state.profile
    var width by remember(state.width) { mutableFloatStateOf(state.width.toFloat()) }
    var depth by remember(state.depth) { mutableFloatStateOf(state.depth.toFloat()) }
    var height by remember(state.height) { mutableFloatStateOf(state.height.toFloat()) }
    val apply = {
        engine.setSpatial(state.withScene(width.toDouble(), depth.toDouble(), height.toDouble()).copy(profile = profile), finished = true)
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { openSpatial() }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Сцена", color = SFColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        SceneSliderRow(
            label = "Ширина", value = width, description = "Ширина сцены",
            onChange = { width = it },
            onFinished = apply,
        )
        SceneSliderRow(
            label = "Глубина", value = depth, description = "Глубина сцены",
            onChange = { depth = it },
            onFinished = apply,
        )
        SceneSliderRow(
            label = "Высота", value = height, description = "Высота сцены",
            onChange = { height = it },
            onFinished = apply,
        )
    }
}

@Composable
fun SpatialScreen(ui: UiState, engine: Engine, onBack: () -> Unit) {
    var width by remember(ui.spatial.width) { mutableFloatStateOf(ui.spatial.width.toFloat()) }
    var depth by remember(ui.spatial.depth) { mutableFloatStateOf(ui.spatial.depth.toFloat()) }
    var height by remember(ui.spatial.height) { mutableFloatStateOf(ui.spatial.height.toFloat()) }
    var near by remember(ui.spatial.profile) { mutableStateOf(ui.spatial.profile == "pair_near") }
    var preserveLoudness by remember(ui.spatial) { mutableStateOf(ui.spatial.preserveLoudness) }
    var meter by remember { mutableStateOf(0.0 to 0.0) }
    LaunchedEffect(ui.spatialAvailable) {
        while (ui.spatialAvailable) {
            meter = engine.spatialMeterSnapshot()
            delay(80)
        }
    }
    fun apply(placementNear: Boolean = near, preserve: Boolean = preserveLoudness) {
        near = placementNear
        preserveLoudness = preserve
        val profile = if (ui.soloMode) "solo_stereo_speaker" else if (placementNear) "pair_near" else "pair_wide"
        engine.setSpatial(ui.spatial.withScene(width.toDouble(), depth.toDouble(), height.toDouble())
            .copy(profile = profile, preserveLoudness = preserve), finished = true)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = SFColors.Accent1, fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Text("Сцена", color = SFColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(22.dp))
        SceneWidthView(meter.first, meter.second, Modifier.fillMaxWidth().height(140.dp))
        Spacer(Modifier.height(24.dp))
        SceneSliderRow(
            label = "Ширина", value = width, description = "Ширина сцены",
            onChange = { width = it }, onFinished = { apply() },
        )
        SceneSliderRow(
            label = "Глубина", value = depth, description = "Глубина сцены",
            onChange = { depth = it }, onFinished = { apply() },
        )
        SceneSliderRow(
            label = "Высота", value = height, description = "Высота сцены",
            onChange = { height = it }, onFinished = { apply() },
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Сохранять громкость", color = SFColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = preserveLoudness, onCheckedChange = { apply(preserve = it) })
        }
        if (!ui.soloMode) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Телефоны рядом", color = SFColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                FilterChip(selected = near, onClick = { apply(placementNear = !near) }, label = { Text(if (near) "Вкл" else "Выкл") })
            }
        }
        TextButton(onClick = {
            preserveLoudness = true
            val profile = if (ui.soloMode) "solo_stereo_speaker" else "pair_wide"
            engine.setSpatial(SpatialSettings(profile = profile), finished = true)
        }, modifier = Modifier.align(Alignment.End)) { Text("Сбросить") }
    }
}

@Composable
private fun SceneSliderRow(label: String, value: Float, description: String,
    onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val percent = (value * 100).roundToInt()
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SFColors.TextDim, fontSize = 14.sp, modifier = Modifier.width(76.dp))
        Slider(
            value = value, onValueChange = onChange, onValueChangeFinished = onFinished,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = SFColors.Accent1, activeTrackColor = SFColors.Accent1,
                inactiveTrackColor = Color(0x1AFFFFFF)),
            modifier = Modifier.weight(1f).semantics {
                contentDescription = description
                stateDescription = "$percent %"
            },
        )
        Text("$percent", color = SFColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp))
    }
}

@Composable
private fun SceneWidthView(sideMidDb: Double, correlation: Double, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "scene")
    val breathing = if (motionEnabled()) infinite.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath").value else 1f
    val width = (0.54f + (sideMidDb.toFloat().coerceIn(-12f, 12f) / 30f)).coerceIn(0.30f, 0.90f) * breathing
    val center = (0.5f + (correlation.toFloat().coerceIn(-1f, 1f) * 0.025f))
    Canvas(modifier.clip(RoundedCornerShape(18.dp))) {
        drawRect(Color(0xFF141A25))
        val midX = size.width * center
        val half = size.width * width * 0.5f
        drawRoundRect(SFColors.Accent2.copy(alpha = 0.30f),
            topLeft = androidx.compose.ui.geometry.Offset(midX - half, size.height * 0.35f),
            size = androidx.compose.ui.geometry.Size(half * 2f, size.height * 0.30f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.15f))
        drawCircle(SFColors.Accent1, radius = size.height * 0.11f, center = androidx.compose.ui.geometry.Offset(midX, size.height * 0.5f))
        drawCircle(Color.White.copy(alpha = 0.8f), radius = size.height * 0.055f,
            center = androidx.compose.ui.geometry.Offset(midX - half * 0.78f, size.height * 0.5f))
        drawCircle(Color.White.copy(alpha = 0.8f), radius = size.height * 0.055f,
            center = androidx.compose.ui.geometry.Offset(midX + half * 0.78f, size.height * 0.5f))
    }
}

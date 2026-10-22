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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.core.SpatialCustom
import app.soundfield.core.SpatialLevel
import app.soundfield.core.SpatialSettings
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState
import kotlinx.coroutines.delay

@Composable
fun SpatialCompactBlock(ui: UiState, engine: Engine, openSpatial: () -> Unit) {
    if (!ui.spatialAvailable) return
    val state = ui.spatial
    val profile = if (ui.soloMode) "solo_stereo_speaker" else state.profile
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { openSpatial() }) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Пространство", color = SFColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        SpatialLevel.entries.forEach { level ->
            FilterChip(
                selected = state.level == level,
                onClick = {
                    engine.setSpatial(state.copy(
                        enabled = level != SpatialLevel.OFF,
                        level = level,
                        profile = profile,
                    ))
                },
                label = { Text(when (level) {
                    SpatialLevel.OFF -> "Выкл"
                    SpatialLevel.SOFT -> "Мягко"
                    SpatialLevel.STRONG -> "Сильно"
                }, fontSize = 11.sp) },
                modifier = Modifier.height(34.dp),
            )
        }
    }
}

@Composable
fun SpatialScreen(ui: UiState, engine: Engine, onBack: () -> Unit) {
    val initial = ui.spatial.custom ?: SpatialCustom(
        gMidDb = if (ui.spatial.profile == "pair_near") 5.0 else if (ui.soloMode) 2.0 else 3.0,
        gHighDb = if (ui.spatial.profile == "pair_near") 3.0 else if (ui.soloMode) 2.0 else 2.0,
        gAmbientDb = if (ui.spatial.profile == "pair_near") 3.0 else 2.0,
        depthDb = -2.0, reflectionDb = -17.0,
    )
    var custom by remember(ui.spatial) { mutableStateOf(initial) }
    var near by remember(ui.spatial.profile) { mutableStateOf(ui.spatial.profile == "pair_near") }
    var preserveLoudness by remember(ui.spatial) { mutableStateOf(ui.spatial.preserveLoudness) }
    var meter by remember { mutableStateOf(0.0 to 0.0) }
    LaunchedEffect(ui.spatialAvailable) {
        while (ui.spatialAvailable) {
            meter = engine.spatialMeterSnapshot()
            delay(80)
        }
    }
    fun apply(values: SpatialCustom = custom, placementNear: Boolean = near, preserve: Boolean = preserveLoudness) {
        custom = values
        near = placementNear
        preserveLoudness = preserve
        val profile = if (ui.soloMode) "solo_stereo_speaker" else if (placementNear) "pair_near" else "pair_wide"
        engine.setSpatial(ui.spatial.copy(
            enabled = true,
            level = ui.spatial.level.takeIf { it != SpatialLevel.OFF } ?: SpatialLevel.SOFT,
            profile = profile,
            custom = values,
            preserveLoudness = preserve,
        ), finished = true)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = SFColors.Accent1, fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Text("Свой", color = SFColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(22.dp))
        SceneWidthView(meter.first, meter.second, Modifier.fillMaxWidth().height(140.dp))
        Spacer(Modifier.height(24.dp))
        SpatialSettingSlider("Ширина", custom.gMidDb.coerceIn(0.0, 8.0).toFloat() / 8f,
            onValueChange = { value -> custom = custom.copy(gMidDb = value * 8.0, gHighDb = value * 8.0) },
            onValueChangeFinished = { apply() })
        SpatialSettingSlider("Воздух", custom.gAmbientDb.coerceIn(0.0, 6.0).toFloat() / 6f,
            onValueChange = { value -> custom = custom.copy(gAmbientDb = value * 6.0) },
            onValueChangeFinished = { apply() })
        SpatialSettingSlider("Глубина", (custom.depthDb + 6.0).coerceIn(0.0, 6.0).toFloat() / 6f,
            onValueChange = { value -> custom = custom.copy(depthDb = value * 6.0 - 6.0) },
            onValueChangeFinished = { apply() })
        SpatialSettingSlider("Отражения", (custom.reflectionDb + 30.0).coerceIn(0.0, 20.0).toFloat() / 20f,
            onValueChange = { value -> custom = custom.copy(reflectionDb = value * 20.0 - 30.0) },
            onValueChangeFinished = { apply() })
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
            custom = initial
            preserveLoudness = true
            val profile = if (ui.soloMode) "solo_stereo_speaker" else "pair_wide"
            engine.setSpatial(SpatialSettings(enabled = ui.spatial.enabled, level = ui.spatial.level,
                profile = profile, custom = null, preserveLoudness = true), finished = true)
        }, modifier = Modifier.align(Alignment.End)) { Text("Сбросить") }
    }
}

@Composable
private fun SpatialSettingSlider(label: String, value: Float, onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = SFColors.TextDim, fontSize = 14.sp)
        Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished,
            colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = SFColors.Accent1, activeTrackColor = SFColors.Accent1,
            inactiveTrackColor = Color(0x1AFFFFFF)))
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

package app.soundfield.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun H1(text: String) = Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = SFColors.Accent1)

@Composable
fun H2(text: String, modifier: Modifier = Modifier) =
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SFColors.Text, modifier = modifier)

@Composable
fun H3(text: String) = Text(text.uppercase(), fontSize = 13.sp, letterSpacing = 1.sp, color = SFColors.TextDim,
    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))

@Composable
fun Subtitle(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) =
    Text(text, fontSize = 14.sp, color = SFColors.TextDim, modifier = modifier, textAlign = align, lineHeight = 20.sp)

@Composable
fun SFCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SFColors.Card)
            .border(1.dp, SFColors.CardBorder, RoundedCornerShape(16.dp))
            .padding(18.dp),
        content = content,
    )
}

private val BtnShape = RoundedCornerShape(14.dp)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(BtnShape)
            .background(if (enabled) SFColors.accent else Brush.linearGradient(listOf(Color(0x55667EEA), Color(0x55764BA2))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.6f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(BtnShape)
            .background(SFColors.Secondary)
            .border(1.dp, SFColors.SecondaryBorder, BtnShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = SFColors.Accent1, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(BtnShape).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(BtnShape)
            .background(SFColors.Bad.copy(alpha = 0.2f))
            .border(1.dp, SFColors.Bad.copy(alpha = 0.3f), BtnShape)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = SFColors.Bad, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun StatusPill(text: String, color: Color) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PairStatusButton(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pair-status-dot")
    val alpha by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pair-status-alpha")
    Row(
        modifier.width(104.dp).height(36.dp).clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(if (enabled) alpha else 0.6f).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ConnectStatusButton(onClick: () -> Unit, onOpenChooser: () -> Unit, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pair-status-dot")
    val alpha by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pair-status-alpha")
    val swipeThreshold = with(LocalDensity.current) { 44.dp.toPx() }
    Row(
        modifier.width(104.dp).height(36.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SFColors.Accent1.copy(alpha = 0.15f))
            .border(1.dp, SFColors.Accent1.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .pointerInput(swipeThreshold, onOpenChooser) {
                var drag = 0f
                var opened = false
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f; opened = false },
                    onVerticalDrag = { change, amount ->
                        drag += amount
                        if (drag >= swipeThreshold && !opened) {
                            opened = true
                            onOpenChooser()
                        }
                        change.consume()
                    },
                    onDragEnd = { drag = 0f; opened = false },
                    onDragCancel = { drag = 0f; opened = false },
                )
            }
            .combinedClickable(onClick = onClick, onLongClick = onOpenChooser)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(SFColors.Accent1))
        Spacer(Modifier.width(8.dp))
        Text("Connect", color = SFColors.Accent1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PlayerActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(SFColors.Secondary)
            .border(1.dp, SFColors.SecondaryBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = SFColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Круг канала L/R; при воспроизведении меняет яркость обводки, не меняя размер и положение. */
@Composable
fun SpeakerCircle(label: String, ch: Int, playing: Boolean, caption: String, sub: String, highlight: Boolean, onClick: () -> Unit = {}) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0.72f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "pulse-alpha")
    val color = SFColors.channel(ch)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(130.dp)) {
        Box(
            Modifier.size(84.dp).clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
                .border(if (highlight) 3.dp else 2.dp, color.copy(alpha = if (playing) pulse else 1f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = SFColors.channelText(ch), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Text(caption, color = SFColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub.ifBlank { " " }, color = if (sub.isBlank()) Color.Transparent else SFColors.TextDim,
            fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun DeviceRow(icon: String, name: String, detail: String, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(SFColors.Card)
            .border(1.dp, SFColors.CardBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(SFColors.accent), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 20.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = SFColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = SFColors.TextDim, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
fun SliderRow(label: String, valueText: String, value: Float, color: Color, range: ClosedFloatingPointRange<Float> = 0f..1f,
              steps: Int = 0, enabled: Boolean = true, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = SFColors.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, steps = steps, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = Color(0x1AFFFFFF)),
        )
    }
}

@Composable
fun DiagRow(label: String, value: String, color: Color = SFColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SFColors.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = Color(0x0DFFFFFF))
}

@Composable
fun SearchingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
        repeat(3) { i ->
            val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.padding(horizontal = 5.dp).size(10.dp).alpha(a).clip(CircleShape).background(SFColors.Accent1))
        }
    }
}

@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SFColors.Secondary)
            .semantics { contentDescription = "Назад"; role = androidx.compose.ui.semantics.Role.Button }
            .clickable(onClick = onBack),
            contentAlignment = Alignment.Center) { Text("←", color = Color.White, fontSize = 20.sp) }
        Spacer(Modifier.width(14.dp))
        H2(title)
    }
}

fun fmtTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

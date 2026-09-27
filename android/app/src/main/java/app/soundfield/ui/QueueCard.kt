package app.soundfield.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.core.Role
import app.soundfield.engine.Engine
import app.soundfield.engine.PlaylistInfo
import app.soundfield.engine.QueueTrackUi
import app.soundfield.engine.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Значки Material Symbols (Apache 2.0), нарисованы векторно — без тяжёлой зависимости material-icons-extended. */
private object QueueIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val PlaylistAdd = icon("PlaylistAdd",
        "M14,10H3v2h11V10zM14,6H3v2h11V6zM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2H18zM3,16h7v-2H3V16z")
    val Checklist = icon("Checklist",
        "M22,7h-9v2h9V7zM22,15h-9v2h9V15zM5.54,11L2,7.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,11zM5.54,19L2,15.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,19z")
    val LibraryMusic = icon("LibraryMusic",
        "M20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4V7zM4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6z")
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (active) SFColors.Accent1.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp),
            tint = when { !enabled -> SFColors.TextDim.copy(alpha = 0.4f); active -> SFColors.Accent1; else -> SFColors.Text })
    }
}

/**
 * Очередь: заголовок со значками «Добавить музыку», «Выбрать» (режим удаления), «Плейлисты»;
 * список треков «Название - Исполнитель»; удаление свайпом влево с возможностью «Вернуть».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueCard(ui: UiState, engine: Engine) {
    val isServer = ui.role == Role.SERVER
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showPlaylists by remember { mutableStateOf(false) }
    // выбранные треки, которых уже нет в очереди, из выделения убираем
    val ids = remember(ui.queueTracks) { ui.queueTracks.map { it.id }.toSet() }
    LaunchedEffect(ids) {
        selected = selected.filter { it in ids }.toSet()
        if (ids.isEmpty()) selecting = false
    }

    SFCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(CircleShape).clickable { engine.setQueueExpanded(!ui.queueExpanded) }
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ОЧЕРЕДЬ · ${ui.queueTracks.size}", fontSize = 13.sp, letterSpacing = 1.sp,
                    color = SFColors.TextDim, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(if (ui.queueExpanded) "⌃" else "⌄", color = SFColors.TextDim, fontSize = 18.sp)
            }
            HeaderIcon(QueueIcons.PlaylistAdd, "Добавить музыку", enabled = isServer) { engine.requestTrackPicker() }
            HeaderIcon(QueueIcons.Checklist, "Выбрать треки", active = selecting, enabled = ui.queueTracks.isNotEmpty()) {
                selecting = !selecting; selected = emptySet()
                if (selecting && !ui.queueExpanded) engine.setQueueExpanded(true)
            }
            HeaderIcon(QueueIcons.LibraryMusic, "Плейлисты", enabled = isServer) { showPlaylists = true }
        }

        if (ui.queueExpanded) {
            if (ui.queueTracks.isEmpty()) {
                Text(if (isServer) "Добавьте музыку значком «+» или откройте плейлист"
                     else "Список появится после добавления музыки на Server",
                    color = SFColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    itemsIndexed(ui.queueTracks, key = { _, item -> item.id }) { index, item ->
                        val current = index == ui.queueIndex || item.id == ui.loadingTrackId
                        if (selecting) {
                            val checked = item.id in selected
                            TrackRow(index, item, current, ui.loadingTrackId, checked = checked,
                                onClick = { selected = if (checked) selected - item.id else selected + item.id })
                        } else {
                            SwipeToRemove(onRemove = { engine.removeTracks(listOf(item.id)) }) {
                                TrackRow(index, item, current, ui.loadingTrackId, checked = null,
                                    onClick = { if (!ui.loadingTracks) engine.selectTrack(item.id) })
                            }
                        }
                    }
                }
            }
            if (selecting) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("Отмена", color = SFColors.TextDim) }
                    TextButton(onClick = {
                        selected = if (selected.size == ui.queueTracks.size) emptySet() else ui.queueTracks.map { it.id }.toSet()
                    }) { Text(if (selected.size == ui.queueTracks.size) "Снять все" else "Выбрать все", color = SFColors.Accent1) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { engine.removeTracks(selected); selected = emptySet(); selecting = false },
                        enabled = selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = SFColors.Bad, contentColor = Color.White),
                    ) { Text("Удалить (${selected.size})") }
                }
            }
        }

        if (ui.undoText.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(MaterialTheme.shapes.medium)
                    .background(SFColors.Secondary).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ui.undoText, color = SFColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { engine.undoRemove() }) { Text("Вернуть", color = SFColors.Accent1, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (showPlaylists) PlaylistsSheet(ui, engine, onDismiss = { showPlaylists = false })
}

@Composable
private fun TrackRow(index: Int, item: QueueTrackUi, current: Boolean, loadingId: String?, checked: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (current) Color(0xFF2A2F5A) else Color(0xFF1B2446))   // непрозрачный фон — под ним полоса свайпа
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (checked != null) 2.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = SFColors.Accent1, uncheckedColor = SFColors.TextDim))
        } else {
            Text("${index + 1}.", color = SFColors.TextDim, fontSize = 13.sp, modifier = Modifier.width(30.dp))
        }
        Text(item.label, color = if (current) SFColors.Accent1 else SFColors.Text, fontSize = 14.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (current && checked == null) Text(if (item.id == loadingId) "…" else "♫", color = SFColors.Accent1, fontSize = 17.sp)
    }
}

/** Свайп влево удаляет трек; под строкой — красная полоса «Удалить». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(SFColors.Bad).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd) {
                Text("Удалить", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        },
        modifier = Modifier.padding(vertical = 2.dp),
    ) { content() }
}

/** Нижняя панель плейлистов: сохранить текущую очередь, открыть/добавить, переименовать, удалить. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PlaylistsSheet(ui: UiState, engine: Engine, onDismiss: () -> Unit) {
    var saving by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<PlaylistInfo?>(null) }
    var editing by remember { mutableStateOf<PlaylistInfo?>(null) }
    val dateFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF16213E)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Плейлисты", color = SFColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Сохранить текущую очередь как плейлист…", enabled = ui.queueTracks.isNotEmpty()) { saving = true }
            Spacer(Modifier.height(16.dp))
            if (ui.playlists.isEmpty()) {
                Subtitle("Сохранённых плейлистов пока нет")
            } else {
                Subtitle("Нажмите, чтобы открыть · удерживайте, чтобы переименовать или удалить")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(ui.playlists, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .combinedClickable(onClick = { opening = p }, onLongClick = { editing = p })
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(QueueIcons.LibraryMusic, contentDescription = null, tint = SFColors.Accent1, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = SFColors.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.count} ${tracksWord(p.count)} · ${dateFmt.format(Date(p.savedAt))}",
                                    color = SFColors.TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (saving) {
        val default = remember { "Плейлист " + SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date()) }
        NameDialog("Сохранить плейлист", default, "Сохранить",
            onDismiss = { saving = false }) { name -> engine.savePlaylist(name); saving = false }
    }
    opening?.let { p ->
        AlertDialog(
            onDismissRequest = { opening = null },
            title = { Text("«${p.name}»") },
            text = { Text("${p.count} ${tracksWord(p.count)}. Заменить текущую очередь или добавить в конец?") },
            confirmButton = {
                TextButton(onClick = { engine.openPlaylist(p.id, append = false); opening = null; onDismiss() }) { Text("Заменить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { engine.openPlaylist(p.id, append = true); opening = null; onDismiss() },
                        enabled = ui.queueTracks.isNotEmpty()) { Text("Добавить") }
                    TextButton(onClick = { opening = null }) { Text("Отмена") }
                }
            },
        )
    }
    editing?.let { p ->
        var confirmDelete by remember(p.id) { mutableStateOf(false) }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Удалить «${p.name}»?") },
                text = { Text("Удаляется только список. Сами аудиофайлы остаются на телефоне.") },
                confirmButton = { TextButton(onClick = { engine.deletePlaylist(p.id); editing = null }) { Text("Удалить", color = SFColors.Bad) } },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
            )
        } else {
            NameDialog("Плейлист", p.name, "Переименовать", onDismiss = { editing = null },
                extra = { TextButton(onClick = { confirmDelete = true }) { Text("Удалить", color = SFColors.Bad) } },
            ) { name -> engine.renamePlaylist(p.id, name); editing = null }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit,
                       extra: @Composable () -> Unit = {}, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true, label = { Text("Название") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { Row { extra(); TextButton(onClick = onDismiss) { Text("Отмена") } } },
    )
}

private fun tracksWord(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "трек"
        m10 in 2..4 && m100 !in 12..14 -> "трека"
        else -> "треков"
    }
}

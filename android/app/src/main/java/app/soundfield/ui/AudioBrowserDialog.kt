package app.soundfield.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.soundfield.audio.RecentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AudioDocument(val uri: Uri, val name: String, val directory: Boolean)

/** One browser: navigate folders, select files, or import the current folder without a selection. */
@Composable
fun AudioBrowserDialog(root: Uri, onDismiss: () -> Unit, onChangeRoot: () -> Unit, loadRecent: () -> List<RecentFile>, onImport: (List<Uri>) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var path by remember(root) { mutableStateOf(listOf(DocumentsContract.getTreeDocumentId(root) to "Музыка")) }
    var selected by remember(root) { mutableStateOf(emptySet<Uri>()) }
    var entries by remember { mutableStateOf(emptyList<AudioDocument>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = path.last()
    var showRecent by rememberSaveable(root) { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<RecentFile>?>(null) }
    var recentSelected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(showRecent) {
        if (showRecent) { recent = null; recentSelected = emptySet(); recent = withContext(Dispatchers.IO) { runCatching(loadRecent).getOrDefault(emptyList()) } }
    }
    LaunchedEffect(root, current.first) {
        loading = true; error = null; entries = emptyList(); selected = emptySet()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, current.first)
                buildList {
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                        null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1).orEmpty()
                            val mime = cursor.getString(2).orEmpty()
                            val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                            if (directory || mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                                add(AudioDocument(DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0)), name, directory))
                            }
                        }
                    } ?: kotlin.error("Папка недоступна")
                }.sortedWith(compareBy<AudioDocument> { !it.directory }.thenBy { it.name.lowercase() })
            }
        }
        result.onSuccess { entries = it }.onFailure { error = "Не удалось открыть папку. Выберите расположение заново." }
        loading = false
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Добавить музыку", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth()) {
                    if (showRecent) TextButton(onClick = { showRecent = false }) { Text("‹ Папки") }
                    else TextButton(enabled = path.size > 1, onClick = { selected = emptySet(); path = path.dropLast(1) }) { Text("‹ Назад") }
                    Spacer(Modifier.weight(1f))
                    if (!showRecent) TextButton(onClick = { showRecent = true }) { Text("Недавние") }
                    TextButton(onClick = onChangeRoot) { Text("Расположение") }
                }
                Text(if (showRecent) "Недавние файлы" else current.second, maxLines = 1, overflow = TextOverflow.Ellipsis, color = SFColors.TextDim)
                if (showRecent) {
                    val list = recent
                    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                        when {
                            list == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                            list.isEmpty() -> Text("Пока нет недавних файлов", modifier = Modifier.padding(vertical = 16.dp))
                            else -> LazyColumn {
                                items(list, key = { it.uri }) { r ->
                                    val on = r.uri in recentSelected
                                    Row(Modifier.fillMaxWidth().clickable { recentSelected = if (on) recentSelected - r.uri else recentSelected + r.uri }
                                        .padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Checkbox(checked = on, onCheckedChange = { recentSelected = if (it) recentSelected + r.uri else recentSelected - r.uri })
                                        Column(Modifier.weight(1f)) {
                                            Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (r.artist.isNotBlank()) Text(r.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = SFColors.TextDim)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Отмена") }
                        TextButton(enabled = recentSelected.isNotEmpty(), onClick = {
                            onImport((recent ?: emptyList()).filter { it.uri in recentSelected }.map { Uri.parse(it.uri) })
                        }) { Text(if (recentSelected.isEmpty()) "Добавить" else "Добавить (${recentSelected.size})") }
                    }
                } else Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                        error != null -> Text(error!!, color = SFColors.Warn, modifier = Modifier.padding(vertical = 16.dp))
                        entries.isEmpty() -> Text("В папке нет аудиофайлов", modifier = Modifier.padding(vertical = 16.dp))
                        else -> LazyColumn {
                            items(entries, key = { it.uri.toString() }) { entry ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    if (entry.directory) { selected = emptySet(); path = path + (DocumentsContract.getDocumentId(entry.uri) to entry.name) }
                                    else selected = if (entry.uri in selected) selected - entry.uri else selected + entry.uri
                                }.padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    if (entry.directory) Text("▱", modifier = Modifier.width(40.dp))
                                    else Checkbox(checked = entry.uri in selected, onCheckedChange = {
                                        selected = if (it) selected + entry.uri else selected - entry.uri
                                    })
                                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (entry.directory) Text("›", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    TextButton(enabled = !loading && error == null, onClick = {
                        onImport(if (selected.isNotEmpty()) entries.filter { it.uri in selected }.map { it.uri }
                        else listOf(DocumentsContract.buildDocumentUriUsingTree(root, current.first)))
                    }) { Text(if (selected.isEmpty()) "Добавить папку" else "Добавить (${selected.size})") }
                }
                }
            }
        }
    }
}

private val AUDIO_EXTENSIONS = setOf("aac", "aif", "aiff", "alac", "amr", "flac", "m4a", "m4b", "mp3", "oga", "ogg", "opus", "wav", "wave", "weba", "wma")

package app.soundfield.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.engine.Engine

@Composable
fun ProblemMarkButton(engine: Engine) {
    var showDialog by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }

    TextButton(onClick = { comment = ""; showDialog = true }, modifier = Modifier.padding(vertical = 2.dp)) {
        Text("⚑ Здесь проблема", fontSize = 13.sp, color = SFColors.Accent1)
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Отметить проблему") },
            text = {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Комментарий (необязательно)") },
                    supportingText = { Text("Метка сохранится здесь и на подключённом Android-телефоне") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    engine.markProblem(comment)
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Отмена") } },
        )
    }
}

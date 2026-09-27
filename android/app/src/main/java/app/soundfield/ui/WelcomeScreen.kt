package app.soundfield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soundfield.core.Role
import app.soundfield.engine.Engine
import app.soundfield.engine.Phase
import app.soundfield.engine.UiState

@Composable
fun WelcomeScreen(ui: UiState, engine: Engine) {
    var renaming by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 48.dp, bottom = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔊", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            H1("SoundField")
            Spacer(Modifier.height(6.dp))
            Subtitle("Объедините два смартфона в стереосистему", align = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))

        ui.remembered?.let { rec ->
            H3("Знакомая пара")
            DeviceRow(
                icon = "🔁", name = rec.peerName,
                detail = "Этот телефон: ${if (rec.myRole == Role.SERVER) "Server" else "Point"} · без подтверждения",
                trailing = { Text("→", color = SFColors.TextDim) },
                onClick = { engine.resumeRemembered() },
            )
            if (ui.autoResuming) Subtitle("Ищем «${rec.peerName}» — пара восстановится автоматически…")
            Row {
                GhostButton("Подключить снова", Modifier.weight(1f)) { engine.resumeRemembered() }
                GhostButton("Забыть", Modifier.weight(1f), color = SFColors.TextDim) { engine.forgetPair() }
            }
            Spacer(Modifier.height(12.dp))
        }

        H3("Телефоны рядом")
        SearchingDots()
        if (ui.nearby.isEmpty()) {
            SFCard {
                Text("📱📱", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Subtitle("Откройте SoundField на втором телефоне.\nОба телефона должны быть в одной сети Wi‑Fi " +
                    "(или один раздаёт точку доступа).", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        } else {
            ui.nearby.forEach { p ->
                DeviceRow(
                    icon = if (p.platform == "ios") "🍏" else "🤖",
                    name = p.name,
                    detail = (if (p.platform == "ios") "iPhone" else "Android") + " · нажмите, чтобы создать пару",
                    trailing = { Text("→", color = SFColors.TextDim) },
                    onClick = { engine.invite(p) },
                )
            }
            Subtitle("Этот телефон станет Server (левый канал), второй — Point (правый). Каналы можно поменять позже.")
        }

        Spacer(Modifier.height(24.dp))
        GhostButton("Этот телефон: ${ui.myName}  ✎", color = SFColors.TextDim) { renaming = true }
        GhostButton(if (showLog) "Скрыть журнал подключения" else "Журнал подключения", color = SFColors.TextDim) { showLog = !showLog }
        if (showLog) {
            if (ui.connLog.isEmpty()) Subtitle("Событий нет")
            ui.connLog.asReversed().take(30).forEach { line ->
                Text(line, color = SFColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }

    if (renaming) RenameDialog(ui.myName, onDismiss = { renaming = false }) { engine.rename(it); renaming = false }

    if (ui.phase == Phase.INVITING) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Подключение…") },
            text = {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = SFColors.Accent1)
                    Spacer(Modifier.height(12.dp))
                    Text(when {
                        ui.resuming -> "Восстанавливаем пару с «${ui.invitingName}»…"
                        ui.inviteRinging -> "Ждём подтверждения на «${ui.invitingName}»."
                        else -> "Соединяемся с «${ui.invitingName}»…"
                    })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { engine.cancelInvite() }) { Text("Отмена") } },
        )
    }
    if (ui.phase == Phase.INCOMING) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Создать стереопару?") },
            text = { Text("«${ui.incomingName}» приглашает этот телефон в стереопару.\nЭтот телефон станет Point и будет играть правый канал.") },
            confirmButton = { TextButton(onClick = { engine.acceptIncoming() }) { Text("Принять") } },
            dismissButton = { TextButton(onClick = { engine.rejectIncoming() }) { Text("Отклонить") } },
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Имя телефона") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true,
            label = { Text("Видно второму телефону") }) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

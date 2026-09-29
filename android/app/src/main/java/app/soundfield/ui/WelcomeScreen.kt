package app.soundfield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
    var showLog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
      Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 60.dp, bottom = 24.dp),
      ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔊", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            H1("SoundField")
        }
        Spacer(Modifier.height(28.dp))

        ui.remembered?.let { rec ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                H3("Знакомая пара")
                Spacer(Modifier.weight(1f))
                GhostButton("Забыть", modifier = Modifier.width(IntrinsicSize.Min), color = SFColors.TextDim) { engine.forgetPair() }
            }
            SFCard(Modifier.clickable { engine.resumeRemembered() }) {
                H2(rec.peerName)
                Text("Подключить", color = SFColors.Accent1, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!ui.soloMode) {
            SFCard(Modifier.clickable { engine.startSolo() }) {
                H3("Слушать на этом телефоне")
                Subtitle("Открыть локальный плеер и слушать музыку без пары")
            }
            Spacer(Modifier.height(12.dp))
        }

        SearchingDots()
        H3("Телефоны рядом")
        if (ui.nearby.isEmpty()) {
            SFCard {
                Text("📱📱", fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Subtitle("Откройте SoundField на другом телефоне.", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
        }

        Spacer(Modifier.height(24.dp))
        GhostButton(if (showLog) "Скрыть журнал подключения" else "Журнал подключения", color = SFColors.TextDim) { showLog = !showLog }
        if (showLog) {
            if (ui.connLog.isEmpty()) Subtitle("Событий нет")
            ui.connLog.asReversed().take(30).forEach { line ->
                Text(line, color = SFColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        TelemetryButton(ui, engine)
      }
      ui.remembered?.let { rec ->
          Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 20.dp)) {
              Badge(if (rec.myRole == Role.SERVER) "SERVER" else "POINT", SFColors.Master)
          }
      }
    }

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

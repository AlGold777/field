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

/** Экран подключения второго телефона. Открывается с плеера; после создания пары закрывается сам. */
@Composable
fun WelcomeScreen(ui: UiState, engine: Engine, onBack: () -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
      Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 24.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = SFColors.Accent1, fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            H1("Второй телефон")
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
}

/** Диалоги подключения показываются поверх любого экрана: приглашение может прийти, пока играет плеер. */
@Composable
fun PairingDialogs(ui: UiState, engine: Engine) {
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
            text = { Text("«${ui.incomingName}» приглашает этот телефон в стереопару.\nЭтот телефон станет Point и будет играть правый канал." +
                if (ui.soloMode && ui.playing) "\nМузыка на этом телефоне будет остановлена; после разрыва пары очередь вернётся." else "") },
            confirmButton = { TextButton(onClick = { engine.acceptIncoming() }) { Text("Принять") } },
            dismissButton = { TextButton(onClick = { engine.rejectIncoming() }) { Text("Отклонить") } },
        )
    }
}

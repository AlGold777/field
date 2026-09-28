package app.soundfield.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.soundfield.engine.Engine

@Composable
fun LanBlockedScreen(peerName: String, details: String, engine: Engine) {
    val context = LocalContext.current
    fun open(intent: Intent, action: String) {
        runCatching { context.startActivity(intent) }
        engine.dismissLanBlockedHint(action)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        .padding(top = 36.dp, bottom = 24.dp)) {
        BackBar("Проблема соединения", onBack = { engine.dismissLanBlockedHint() })
        Spacer(Modifier.height(16.dp))
        SFCard {
            H2("Роутер не пропускает соединение между телефонами")
            Spacer(Modifier.height(12.dp))
            Subtitle("«$peerName» найден в сети и SoundField на нём запущен, но роутер не передаёт данные напрямую между телефонами. Так бывает при включённой изоляции клиентов (AP isolation), в гостевой сети или когда телефоны подключены к разным точкам или диапазонам mesh-системы.")
            Spacer(Modifier.height(16.dp))
            Subtitle("Что можно сделать:\n1. Самый быстрый способ: включите точку доступа на одном телефоне и подключите к ней второй.\n2. Подключите оба телефона к одной и той же сети (не гостевой) и одному диапазону 2,4 или 5 ГГц.\n3. В настройках роутера выключите «Изоляцию клиентов» / «AP isolation» / «Client isolation»." )
            if (details == "double_wifi") {
                Spacer(Modifier.height(10.dp)); Subtitle("4. Двойной Wi‑Fi включён — попробуйте выключить его или переподключить второй Wi‑Fi.")
            } else if (details == "previous_wifi") {
                Spacer(Modifier.height(10.dp)); Subtitle("Раньше соединение шло через второй Wi‑Fi (двойной Wi‑Fi). Включите его снова.")
            }
        }
        Button(onClick = {
            val tether = Intent("android.settings.TETHER_SETTINGS")
            val resolved = context.packageManager.resolveActivity(tether, 0)
            open(if (resolved != null) tether else Intent(Settings.ACTION_WIRELESS_SETTINGS), "hotspot")
        }, modifier = Modifier.padding(bottom = 10.dp)) { Text("Точка доступа") }
        OutlinedButton(onClick = {
            open(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI)
                else Intent(Settings.ACTION_WIFI_SETTINGS), "wifi")
        }, modifier = Modifier.padding(bottom = 10.dp)) { Text("Настройки Wi‑Fi") }
        OutlinedButton(onClick = { engine.dismissLanBlockedHint() }) { Text("Понятно") }
    }
}

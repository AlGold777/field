package app.soundfield.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun StabilitySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 24.dp)) {
        BackBar("Настройки для стабильной работы", onBack)
        Spacer(Modifier.height(16.dp))
        SFCard {
            H3("Разрешите SoundField работать в фоне")
            Subtitle("На Xiaomi и других телефонах система может приостанавливать сеть и звук при выключенном экране.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null))
                context.startActivity(intent)
            }) { Text("Открыть настройки приложения") }
        }
        SFCard {
            H3("Xiaomi · HyperOS")
            Subtitle("Откройте «Батарея» и выберите «Без ограничений». Затем включите «Автозапуск» для SoundField.")
            Subtitle("После этого откройте список недавних приложений и закрепите SoundField замком, если такая кнопка доступна.")
        }
    }
}

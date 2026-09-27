package app.soundfield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.soundfield.engine.Engine
import app.soundfield.engine.UiState

@Composable
fun TelemetryButton(ui: UiState, engine: Engine) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.exportTelemetry() else engine.telemetryPermissionDenied()
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = !ui.telemetrySaving,
        onClick = {
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else engine.exportTelemetry()
        },
    ) { Text(if (ui.telemetrySaving) "Сохраняем…" else "Telemetria") }
    ui.telemetryFile?.let { Subtitle("Сохранено: $it") }
}

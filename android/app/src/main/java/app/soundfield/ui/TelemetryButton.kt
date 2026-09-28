package app.soundfield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    ) { Text(if (ui.telemetrySaving) "Сохраняем…" else "Телеметрия") }
    ui.telemetryFile?.let { Subtitle("Сохранено: $it") }
}

@Composable
fun TelemetryIconButton(ui: UiState, engine: Engine, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.exportTelemetry() else engine.telemetryPermissionDenied()
    }
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(SFColors.Secondary)
            .border(1.dp, SFColors.SecondaryBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = !ui.telemetrySaving) {
                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else engine.exportTelemetry()
            }
            .semantics { contentDescription = if (ui.telemetrySaving) "Сохраняем телеметрию" else "Экспорт телеметрии" },
        contentAlignment = Alignment.Center,
    ) {
        if (ui.telemetrySaving) {
            CircularProgressIndicator(Modifier.size(19.dp), color = SFColors.Accent1, strokeWidth = 2.dp)
        } else {
            Canvas(Modifier.size(23.dp)) {
                val line = Path().apply {
                    moveTo(size.width * 0.08f, size.height * 0.62f)
                    lineTo(size.width * 0.30f, size.height * 0.62f)
                    lineTo(size.width * 0.44f, size.height * 0.22f)
                    lineTo(size.width * 0.60f, size.height * 0.78f)
                    lineTo(size.width * 0.73f, size.height * 0.45f)
                    lineTo(size.width * 0.92f, size.height * 0.45f)
                }
                drawPath(line, SFColors.Accent1, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

package app.soundfield.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.soundfield.engine.Engine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = Engine.get(this)
        setContent { SoundFieldTheme { AppRoot(engine) } }
    }

    override fun onStart() {
        super.onStart()
        // Разблокировка / возврат в приложение: сразу проверяем связь и восстанавливаем пару, не дожидаясь таймаутов.
        Engine.get(this).onForeground()
    }

    override fun onStop() {
        super.onStop()
        Engine.get(this).onBackground()
    }
}

private enum class Sub { NONE, CALIBRATION, DIAGNOSTICS, STABILITY, LAN_BLOCKED }

@Composable
fun AppRoot(engine: Engine) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var sub by remember { mutableStateOf(Sub.NONE) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.micPermissionGranted()
        else Toast.makeText(ctx, "Без микрофона доступна только ручная калибровка", Toast.LENGTH_LONG).show()
    }
    val audioFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) engine.addAudioFiles(uris)
    }
    val audioFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) engine.addAudioFiles(listOf(uri))
    }
    var showAudioSourceDialog by rememberSaveable { mutableStateOf(false) }
    var handledPickerRequest by rememberSaveable { mutableIntStateOf(ui.filePickerRequest) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(ui.messageId) {
        if (ui.messageId > 0 && ui.message.isNotEmpty()) Toast.makeText(ctx, ui.message, Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(ui.filePickerRequest) {
        if (ui.filePickerRequest > handledPickerRequest) {
            handledPickerRequest = ui.filePickerRequest
            showAudioSourceDialog = true
        }
    }
    LaunchedEffect(ui.showStabilitySettings) {
        if (ui.showStabilitySettings) sub = Sub.STABILITY
    }
    LaunchedEffect(ui.showLanBlocked) {
        if (ui.showLanBlocked) sub = Sub.LAN_BLOCKED
        else if (sub == Sub.LAN_BLOCKED) sub = Sub.NONE
    }
    if (!ui.inPair && sub != Sub.STABILITY && sub != Sub.LAN_BLOCKED) sub = Sub.NONE
    BackHandler(enabled = sub != Sub.NONE) {
        if (sub == Sub.STABILITY) engine.dismissStabilitySettings()
        if (sub == Sub.LAN_BLOCKED) engine.dismissLanBlockedHint()
        sub = Sub.NONE
    }

    Box(Modifier.fillMaxSize().background(SFColors.background).safeDrawingPadding()) {
        when {
            sub == Sub.STABILITY -> StabilitySettingsScreen(onBack = {
                engine.dismissStabilitySettings()
                sub = Sub.NONE
            })
            sub == Sub.LAN_BLOCKED -> LanBlockedScreen(ui.lanBlockedPeerName, ui.lanBlockedDetails, engine)
            !ui.inPair -> WelcomeScreen(ui, engine)
            sub == Sub.CALIBRATION -> CalibrationScreen(ui, engine,
                requestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, onBack = { sub = Sub.NONE })
            sub == Sub.DIAGNOSTICS -> DiagnosticsScreen(ui, engine, onBack = { sub = Sub.NONE })
            else -> PlayerScreen(ui, engine, openCalibration = { sub = Sub.CALIBRATION }, openDiagnostics = { sub = Sub.DIAGNOSTICS },
                openTrackPicker = { engine.requestTrackPicker() })
        }
    }
    if (showAudioSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAudioSourceDialog = false },
            title = { Text("Добавить музыку") },
            text = { Text("Выберите отдельные аудиофайлы или папку. Файлы из папки будут добавлены в очередь по имени.") },
            confirmButton = {
                TextButton(onClick = { showAudioSourceDialog = false; audioFilesLauncher.launch(arrayOf("audio/*", "application/octet-stream")) }) {
                    Text("Выбрать файлы")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showAudioSourceDialog = false; audioFolderLauncher.launch(null) }) { Text("Выбрать папку") }
                    TextButton(onClick = { showAudioSourceDialog = false }) { Text("Отмена") }
                }
            },
        )
    }
}

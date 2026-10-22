package app.soundfield.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.core.content.ContextCompat
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

private enum class Sub { NONE, PAIRING, CALIBRATION, DIAGNOSTICS, EQUALIZER, SPATIAL, STABILITY, LAN_BLOCKED }

@Composable
fun AppRoot(engine: Engine) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var sub by remember { mutableStateOf(Sub.NONE) }
    var syncProbeAfterPermission by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            engine.micPermissionGranted()
            if (syncProbeAfterPermission) engine.startSyncProbe()
        }
        syncProbeAfterPermission = false
        if (!granted)
            Toast.makeText(ctx, "Без микрофона доступна только ручная калибровка", Toast.LENGTH_LONG).show()
    }
    val browserPrefs = remember { ctx.getSharedPreferences("soundfield", android.content.Context.MODE_PRIVATE) }
    var browserRoot by rememberSaveable { mutableStateOf(browserPrefs.getString("audioBrowserRoot", null)) }
    var showAudioBrowser by rememberSaveable { mutableStateOf(false) }
    val audioRootLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            browserPrefs.edit().putString("audioBrowserRoot", uri.toString()).apply()
            browserRoot = uri.toString()
            showAudioBrowser = true
        }
    }
    var handledPickerRequest by rememberSaveable { mutableIntStateOf(ui.filePickerRequest) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // id последнего показанного сообщения переживает пересоздание активности, чтобы тост не повторялся
    var shownMsgId by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(ui.messageId) {
        if (ui.messageId > 0 && ui.messageId != shownMsgId && ui.message.isNotEmpty()) {
            shownMsgId = ui.messageId
            Toast.makeText(ctx, ui.message, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(ui.filePickerRequest) {
        if (ui.filePickerRequest > handledPickerRequest) {
            handledPickerRequest = ui.filePickerRequest
            val root = browserRoot?.let(Uri::parse)
            if (root != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission }) {
                showAudioBrowser = true
            } else audioRootLauncher.launch(root)
        }
    }
    LaunchedEffect(ui.showStabilitySettings) {
        if (ui.showStabilitySettings) sub = Sub.STABILITY
    }
    LaunchedEffect(ui.showLanBlocked) {
        if (ui.showLanBlocked) sub = Sub.LAN_BLOCKED
        else if (sub == Sub.LAN_BLOCKED) sub = Sub.NONE
    }
    // Главная страница — плеер. Экран подключения закрывается сам, когда пара создана; калибровка бывает только в паре.
    LaunchedEffect(ui.inPair) { if (ui.inPair && sub == Sub.PAIRING) sub = Sub.NONE }
    if (!ui.inPair && sub == Sub.CALIBRATION) sub = Sub.NONE
    BackHandler(enabled = sub != Sub.NONE) {
        if (sub == Sub.STABILITY) engine.dismissStabilitySettings()
        if (sub == Sub.LAN_BLOCKED) engine.dismissLanBlockedHint()
        if (sub == Sub.CALIBRATION && ui.mode == app.soundfield.core.SourceMode.LEVEL_MATCH) engine.stopLevelMatch(commit = false)
        sub = Sub.NONE
    }

    Box(Modifier.fillMaxSize().background(SFColors.background).safeDrawingPadding()) {
        when {
            sub == Sub.STABILITY -> StabilitySettingsScreen(onBack = {
                engine.dismissStabilitySettings()
                sub = Sub.NONE
            })
            sub == Sub.LAN_BLOCKED -> LanBlockedScreen(ui.lanBlockedPeerName, ui.lanBlockedDetails, engine)
            sub == Sub.PAIRING -> WelcomeScreen(ui, engine, onBack = { sub = Sub.NONE })
            sub == Sub.CALIBRATION -> CalibrationScreen(ui, engine,
                requestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, onBack = { sub = Sub.NONE })
            sub == Sub.DIAGNOSTICS -> DiagnosticsScreen(ui, engine,
                onRequestSyncProbe = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        engine.startSyncProbe()
                    else {
                        syncProbeAfterPermission = true
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, onBack = { sub = Sub.NONE })
            sub == Sub.EQUALIZER -> EqualizerScreen(ui, engine, onBack = { sub = Sub.NONE })
            sub == Sub.SPATIAL -> SpatialScreen(ui, engine, onBack = { sub = Sub.NONE })
            else -> PlayerScreen(ui, engine, openCalibration = { sub = Sub.CALIBRATION }, openDiagnostics = { sub = Sub.DIAGNOSTICS },
                openEqualizer = { sub = Sub.EQUALIZER },
                openSpatial = { sub = Sub.SPATIAL }, openPairing = { sub = Sub.PAIRING })
        }
    }
    PairingDialogs(ui, engine)
    if (showAudioBrowser && browserRoot != null) {
        AudioBrowserDialog(
            root = Uri.parse(browserRoot),
            onDismiss = { showAudioBrowser = false },
            onChangeRoot = { showAudioBrowser = false; audioRootLauncher.launch(browserRoot?.let(Uri::parse)) },
            onImport = { uris -> showAudioBrowser = false; engine.addAudioFiles(uris) },
        )
    }
}

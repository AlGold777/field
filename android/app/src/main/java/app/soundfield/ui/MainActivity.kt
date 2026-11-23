package app.soundfield.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.app.ActivityCompat
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
    // rememberSaveable: смена темы, шрифта или языка пересоздаёт Activity — открытый экран сохраняется.
    var sub by rememberSaveable { mutableStateOf(Sub.NONE) }
    var syncProbeAfterPermission by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // отказ без показа объяснения системой = постоянный отказ («больше не спрашивать»): дальше ведём в настройки приложения
    var micDeniedForever by rememberSaveable { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micDeniedForever = false
            engine.micPermissionGranted()
            if (syncProbeAfterPermission) engine.startSyncProbe()
        }
        syncProbeAfterPermission = false
        if (!granted) {
            val act = ctx as? Activity
            micDeniedForever = act != null && !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.RECORD_AUDIO)
            val text = if (micDeniedForever) "Микрофон запрещён. Разрешите его в настройках приложения, иначе доступна только ручная калибровка"
                else "Без микрофона доступна только ручная калибровка"
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
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
    // Источник YouTube: микрофонное разрешение (его требует AudioRecord захвата), затем системный запрос MediaProjection.
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        engine.captureConsent(result.resultCode, result.data)
    }
    val launchCaptureConsent = {
        val manager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        // Android 14+: сразу запрос на весь экран, без выбора «одно приложение» — звук всё равно берётся только у YouTube.
        val intent = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        runCatching { captureLauncher.launch(intent) }
            .onFailure { Toast.makeText(ctx, "Системный запрос захвата недоступен", Toast.LENGTH_LONG).show() }
        Unit
    }
    val captureMicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCaptureConsent()
        else Toast.makeText(ctx, "Захват звука YouTube требует разрешения «Запись аудио»", Toast.LENGTH_LONG).show()
    }
    var handledCaptureRequest by rememberSaveable { mutableIntStateOf(ui.captureRequest) }
    LaunchedEffect(ui.captureRequest) {
        if (ui.captureRequest > handledCaptureRequest) {
            handledCaptureRequest = ui.captureRequest
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                launchCaptureConsent()
            else captureMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // При захвате YouTube свой звук идёт через поток будильника: кнопки громкости должны менять его.
    val captureOnThisPhone = ui.input == app.soundfield.core.InputSource.YOUTUBE && ui.role == app.soundfield.core.Role.SERVER
    DisposableEffect(captureOnThisPhone) {
        val activity = ctx as? Activity
        activity?.volumeControlStream = if (captureOnThisPhone) android.media.AudioManager.STREAM_ALARM
            else android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
        onDispose { activity?.volumeControlStream = android.media.AudioManager.USE_DEFAULT_STREAM_TYPE }
    }
    var handledPickerRequest by rememberSaveable { mutableIntStateOf(ui.filePickerRequest) }
    // После выгрузки процесса счётчики Engine начинаются с 0, а сохранённые «обработано» — нет:
    // «+» и захват YouTube молчали, пока новый счётчик не догонял старый.
    var handledProcess by rememberSaveable { mutableStateOf(Engine.PROCESS_TOKEN) }
    if (handledProcess != Engine.PROCESS_TOKEN) {
        handledProcess = Engine.PROCESS_TOKEN
        handledCaptureRequest = ui.captureRequest
        handledPickerRequest = ui.filePickerRequest
    }
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
    LaunchedEffect(ui.inPair) { if (!ui.inPair && sub == Sub.CALIBRATION) sub = Sub.NONE }
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
                requestMic = { if (micDeniedForever) openAppSettings(ctx) else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onBack = { sub = Sub.NONE }, micDeniedForever = micDeniedForever)
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
            loadRecent = { engine.loadRecentFiles() },
            onImport = { uris -> showAudioBrowser = false; engine.addAudioFiles(uris) },
        )
    }
}

/** Открывает экран настроек приложения (разрешения), когда системный запрос уже недоступен. */
private fun openAppSettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

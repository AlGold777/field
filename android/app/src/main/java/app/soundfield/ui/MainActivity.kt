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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
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
}

private enum class Sub { NONE, CALIBRATION, DIAGNOSTICS }

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
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(ui.messageId) {
        if (ui.messageId > 0 && ui.message.isNotEmpty()) Toast.makeText(ctx, ui.message, Toast.LENGTH_SHORT).show()
    }
    if (!ui.inPair) sub = Sub.NONE
    BackHandler(enabled = sub != Sub.NONE) { sub = Sub.NONE }

    Box(Modifier.fillMaxSize().background(SFColors.background).safeDrawingPadding()) {
        when {
            !ui.inPair -> WelcomeScreen(ui, engine)
            sub == Sub.CALIBRATION -> CalibrationScreen(ui, engine,
                requestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, onBack = { sub = Sub.NONE })
            sub == Sub.DIAGNOSTICS -> DiagnosticsScreen(ui, onBack = { sub = Sub.NONE })
            else -> PlayerScreen(ui, engine, openCalibration = { sub = Sub.CALIBRATION }, openDiagnostics = { sub = Sub.DIAGNOSTICS })
        }
    }
}

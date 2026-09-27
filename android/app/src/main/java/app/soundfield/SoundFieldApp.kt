package app.soundfield

import android.app.Application
import app.soundfield.engine.Engine
import app.soundfield.diagnostics.Telemetry

class SoundFieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.get(this).installCrashHandler()
        Engine.get(this)   // сеть и обнаружение поднимаются сразу — второй телефон увидит этот без лишних действий
    }
}

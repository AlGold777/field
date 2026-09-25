package app.soundfield

import android.app.Application
import app.soundfield.engine.Engine

class SoundFieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.get(this)   // сеть и обнаружение поднимаются сразу — второй телефон увидит этот без лишних действий
    }
}

# libsfspotify.so вызывает методы SpotifySink по именам (JNI): при минификации их нельзя переименовывать.
-keep interface app.soundfield.spotify.SpotifySink { *; }
-keepclassmembers class * implements app.soundfield.spotify.SpotifySink {
    public void onPcm(float[], int);
    public void onEvent(int, long, long, java.lang.String, java.lang.String);
    public void onLog(java.lang.String);
}

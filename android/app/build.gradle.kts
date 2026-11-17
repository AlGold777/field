plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.soundfield"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "app.soundfield"
        minSdk = 26
        targetSdk = 35
        versionCode = 82
        versionName = "1.11.6"
        ndk { abiFilters += "arm64-v8a" }
    }
    externalNativeBuild {
        cmake {
            path = file("../../dsp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // для тестовой раздачи; заменить на свой ключ
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress += "wav" }
}

// Хост-сборка ядра (libsfdsp.dylib с JNI) для JUnit на JVM: тесты загружают её через System.load.
val buildHostDsp = tasks.register<Exec>("buildHostDsp") {
    group = "verification"
    description = "Builds the host (macOS) sfdsp library, sflab and dsp_tests with clang++."
    val repo = rootProject.projectDir.parentFile
    workingDir = repo
    commandLine("sh", "tools/build_dsp.sh")
    inputs.dir(File(repo, "dsp/src")); inputs.dir(File(repo, "dsp/include")); inputs.dir(File(repo, "dsp/jni"))
    inputs.file(File(repo, "tools/build_dsp.sh"))
    outputs.dir(File(repo, "dsp/build/host"))
    isIgnoreExitValue = true  // нет clang++/JDK-заголовков — тесты ядра пропускаются (Assume)
}

// Приёмник Spotify Connect (Rust, librespot): libsfspotify.so в jniLibs. Нет cargo или NDK — скрипт выходит с 0,
// приложение собирается без источника Spotify. Ошибка компиляции приёмника останавливает сборку приложения.
val buildSpotifyBridge = tasks.register<Exec>("buildSpotifyBridge") {
    group = "build"
    description = "Builds libsfspotify.so (Spotify Connect receiver) for arm64-v8a with cargo."
    workingDir = rootProject.projectDir.parentFile
    commandLine("sh", "tools/build_spotify.sh")
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildSpotifyBridge) }

tasks.withType<Test>().configureEach {
    dependsOn(buildHostDsp)
    systemProperty("sfdsp.host.lib", File(rootProject.projectDir.parentFile, "dsp/build/host/libsfdsp.dylib").absolutePath)
}

tasks.register<JavaExec>("labRender") {
    group = "verification"
    description = "Renders sflab input WAVs through the app's Kotlin render path (ServerRenderer). Args: -PlabIn=DIR -PlabOut=DIR"
    dependsOn("compileDebugKotlin", buildHostDsp)
    systemProperty("sfdsp.host.lib", File(rootProject.projectDir.parentFile, "dsp/build/host/libsfdsp.dylib").absolutePath)
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) + configurations.getByName("debugRuntimeClasspath")
    mainClass.set("app.soundfield.tools.LabRenderMain")
    args(providers.gradleProperty("labIn").getOrElse(""), providers.gradleProperty("labOut").getOrElse(""))
}

tasks.register<JavaExec>("spatialRender") {
    group = "verification"
    description = "Render WAV files through the app's JVM SpatialSource pipeline."
    dependsOn("compileDebugKotlin", buildHostDsp)
    systemProperty("sfdsp.host.lib", File(rootProject.projectDir.parentFile, "dsp/build/host/libsfdsp.dylib").absolutePath)
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) + configurations.getByName("debugRuntimeClasspath")
    mainClass.set("app.soundfield.tools.SpatialRenderMain")
}

// Профили устройств (контракт A2, profiles/devices.json) кладутся в assets APK копией.
val profilesAssetsDir = File(layout.buildDirectory.get().asFile, "generated/profiles/assets")
val copyProfilesAssets = tasks.register<Copy>("copyProfilesAssets") {
    from(File(rootProject.projectDir.parentFile, "profiles/devices.json"))
    into(profilesAssetsDir)
}
android.sourceSets.getByName("main").assets.srcDir(profilesAssetsDir)
tasks.named("preBuild") { dependsOn(copyProfilesAssets) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

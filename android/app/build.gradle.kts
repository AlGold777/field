plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.soundfield"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.soundfield"
        minSdk = 26
        targetSdk = 35
        versionCode = 61
        versionName = "1.10.23"
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

tasks.register<JavaExec>("spatialRender") {
    group = "verification"
    description = "Render WAV files through the app's JVM SpatialSource pipeline."
    dependsOn("compileDebugKotlin")
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) + configurations.getByName("debugRuntimeClasspath")
    mainClass.set("app.soundfield.tools.SpatialRenderMain")
}

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

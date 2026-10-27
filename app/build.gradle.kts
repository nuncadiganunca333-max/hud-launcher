plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "jp.hud.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.hud.launcher"
        minSdk = 30
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 32
        versionName = "12.3"
    }
    signingConfigs {
        create("fixed") {
            storeFile = file("hud.keystore")
            storePassword = "hudlauncher"
            keyAlias = "hud"
            keyPassword = "hudlauncher"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.lifecycle:lifecycle-runtime:2.6.2")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-java:1.3.0")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.mlkit:object-detection:17.0.2")
}

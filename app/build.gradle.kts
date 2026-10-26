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
        versionCode = 9
        versionName = "6.0"
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

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.wannabe.phonekb"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.wannabe.phonekb"
        minSdk = 28          // BluetoothHidDevice 需要 Android 9+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 不依賴 AndroidX，只用 framework API，專案最輕
dependencies {}

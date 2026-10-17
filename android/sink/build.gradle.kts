// WatchPlay — :sink module build script.
//
// The watch side of the protocol, running on a second Android device: a GATT *server* that
// advertises the WatchPlay service, reassembles the phone's chunks, decodes JPEG and sends touches
// back. It exists because M1 needs something on the other end of the radio before any MCU firmware
// does: `watch/` describes an ESP32-S3 reference board, this is the same role on hardware that is
// already on the desk.
//
// Deliberately no AppCompat, no Compose, no resources: one Activity built in code, so the module
// stays small enough to read in one sitting.
//
// compileSdk/targetSdk 34 and minSdk 26 match :sdk. Kotlin comes from AGP 9's built-in support.

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.watchplay.sink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchplay.sink"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
}

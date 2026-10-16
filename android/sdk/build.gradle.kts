// WatchPlay — :sdk module build script.
//
// The SDK is a plain Android *library*: no Compose, no NDK, no native code. Dependencies are kept
// to androidx.core (only so that androidx.annotation.RequiresApi is available for the BLE
// transports) plus JUnit 4 for the pure-JVM unit tests.
//
// Toolchain: compileSdk/targetSdk 34, minSdk 26 (Android 8.0 — required because the SDK uses
// java.util.Base64-free APIs and BLE L2CAP CoC needs API 29 at runtime, guarded below).
// Java/Kotlin target 17 to match the JDK that Android Studio ships (JBR 21) and AGP 9's minimum.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.watchplay.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // Requested by the project brief. Note: AGP 8.x already deprecated `targetSdk` for library
        // modules (it only affects lint analysis, never a merged library manifest); if a future AGP
        // rejects it, drop this line — android:targetSdkVersion belongs to the consuming app.
        targetSdk = 34
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // The SDK generates no BuildConfig and ships no resources; keep the module dependency-free.
        buildConfig = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

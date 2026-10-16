// WatchPlay — :demo module build script.
//
// A deliberately tiny AppCompat application (no Compose, no ViewBinding) whose only job is to show that
// :sdk can drive a real pipeline: a WebView with an animated watch scene, mirrored to a scripted "watch"
// peer over LoopbackTransport, with live PipelineStats on screen.
//
// compileSdk/targetSdk 34 and minSdk 26 match :sdk. Kotlin comes from AGP 9's built-in Kotlin support.

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.watchplay.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchplay.demo"
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}

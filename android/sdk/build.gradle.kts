// WatchPlay — :sdk module build script.
//
// The SDK is a plain Android *library*: no Compose, no NDK, no native code. Dependencies stay minimal —
// kotlin-stdlib, androidx.core (only so androidx.annotation.RequiresApi is available for the BLE
// transports) and JUnit 4 for the pure-JVM unit tests.
//
// Toolchain: compileSdk/targetSdk 34, minSdk 26 (Android 8.0), Java 17.
// Kotlin is compiled by AGP 9's built-in Kotlin support; `compileOptions` also fixes the Kotlin jvmTarget.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.watchplay.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // NOTE for readers of the project brief, which asked for `targetSdk = 34` here: AGP 9 removed
        // `targetSdk` from the *library* DSL entirely ("Unresolved reference 'targetSdk'"), and a library
        // never contributes `targetSdkVersion` to the merged manifest anyway — the consuming application
        // owns it (:demo sets 34). Adding it back would require pinning AGP 8.x.
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
        // The SDK generates no BuildConfig and ships no resources.
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

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

// `CommittedTracesTest` parses the trace files that `tools/gen_traces.py` writes, so the Python
// reference implementation and this SDK are checked against the *same* bytes instead of against each
// other's assumptions. Reading them in place keeps the generator the only writer.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "watchplay.traces",
        rootProject.projectDir.parentFile.resolve("examples/protocol-traces").absolutePath,
    )
}

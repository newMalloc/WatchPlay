// WatchPlay — Android phone side (Gradle settings).
//
// Two modules:
//   :sdk  — com.watchplay.sdk, the reusable protocol/render/capture/transport library.
//   :demo — com.watchplay.demo, a tiny AppCompat app that drives :sdk over LoopbackTransport.
//
// Offline-first note: the `resolutionStrategy.eachPlugin` block below maps the Android and
// Kotlin plugin ids straight onto their implementation modules. That lets a machine with a warm
// `~/.gradle/caches/modules-2` build without fetching the small `<id>.gradle.plugin` marker
// artifacts from the Gradle Plugin Portal.

import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application",
                "com.android.library",
                -> useModule("com.android.tools.build:gradle:${requested.version}")

                "org.jetbrains.kotlin.android" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WatchPlay-Android"

include(":sdk")
include(":demo")

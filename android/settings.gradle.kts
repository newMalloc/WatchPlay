// WatchPlay — Android phone side (Gradle settings).
//
// Two modules:
//   :sdk  — com.watchplay.sdk, the reusable protocol/render/capture/transport library.
//   :demo — com.watchplay.demo, a tiny AppCompat app that drives :sdk over LoopbackTransport.
//
// Offline-first note: the `resolutionStrategy.eachPlugin` block below maps the Android plugin ids
// straight onto their implementation modules, so a machine with a warm
// `~/.gradle/caches/modules-2` can build without fetching the small `<id>.gradle.plugin` marker
// artifacts from the Gradle Plugin Portal. There is no entry for a Kotlin plugin id because Kotlin is
// compiled by AGP 9's built-in Kotlin support (see gradle.properties).

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

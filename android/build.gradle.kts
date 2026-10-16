// WatchPlay — Android phone side (root build script).
//
// Nothing is applied here; every plugin is declared `apply false` and applied by the module that
// needs it (:sdk and :demo). Version numbers live in gradle/libs.versions.toml.
//
// No custom `clean` task is declared on purpose: `gradlew clean` already resolves the `clean`
// task of every included module, and `project(":sdk")` accessors at configuration time are
// needless coupling between the modules.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

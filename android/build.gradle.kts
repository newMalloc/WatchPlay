// WatchPlay — Android phone side (root build script).
//
// Nothing is applied here; each plugin is declared `apply false` and applied by the module that needs it
// (:sdk and :demo). Version numbers live in gradle/libs.versions.toml.
//
// Kotlin is compiled by AGP 9's built-in Kotlin support rather than by the standalone
// org.jetbrains.kotlin.android plugin, which AGP 9.1 does not accept (see gradle.properties).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

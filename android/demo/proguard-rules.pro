# WatchPlay :demo — R8 rules for a minified release build.
#
# The demo reflects nothing and ships no serialisation, so the defaults are enough. The file exists so
# the `proguardFiles(...)` declaration in build.gradle.kts resolves.
-dontwarn okio.**

# WatchPlay :sdk — ProGuard/R8 rules that consumers of the library inherit.
#
# The SDK is a skeleton: nothing here is obfuscation-sensitive yet. The file exists so the
# `consumerProguardFiles(...)` declaration in build.gradle.kts resolves.
#
# TODO(next contributor): once the SDK is used with minifyEnabled consumers, keep the public API
# surface of com.watchplay.sdk and any reflectively-loaded transport implementations.
-keep class com.watchplay.sdk.** { *; }

// Root build file. Declares plugins for subprojects without applying them here.
//
// Note what's absent: `org.jetbrains.kotlin.android`. AGP 9 compiles Kotlin
// itself, and applying that plugin is a hard failure. See gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

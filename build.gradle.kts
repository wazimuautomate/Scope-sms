// Root build file. Declares plugins for subprojects without applying them here.
//
// Note what's absent: `org.jetbrains.kotlin.android`. AGP 9 compiles Kotlin
// itself, and applying that plugin is a hard failure. See gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // Room's annotation processor. KSP, never kapt — kapt is incompatible with
    // AGP 9's built-in Kotlin. Added in Phase 3, the first phase to use Room.
    alias(libs.plugins.ksp) apply false
}

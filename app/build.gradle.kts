plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.scopesms.autoreply"
    compileSdk = 36

    defaultConfig {
        // Permanent. The agent's direct-install updates are matched on this —
        // change it and the next APK installs alongside the old app instead of
        // updating it, orphaning their rules and activity history.
        applicationId = "com.scopesms.autoreply"

        // Android 11. A hard floor from CLAUDE.md constraint 1, not a default:
        // the target market is low-end Android 11/12 handsets (Tecno, Infinix,
        // itel, Xiaomi are common among Bingwa agents in Kenya). If an API
        // needs a higher minSdk, find the compat path — don't raise this.
        minSdk = 30
        targetSdk = 36

        versionCode = 1
        versionName = "0.1.0-phase0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Every CI push produces one of these for the agent to install.
            isMinifyEnabled = false
        }
        release {
            // Signing is Phase 11's job: a keystore held as a base64 GitHub
            // Secret, applied by a tag-triggered workflow. Deliberately not
            // configured here — an unsigned release build failing loudly is
            // better than one silently signed with debug keys.
            isMinifyEnabled = true
            isShrinkResources = true
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
        compose = true
        // Enabled for BuildConfig.APPLICATION_ID, asserted in
        // ArchitectureGuardTest. Off by default since AGP 8.
        buildConfig = true
    }
}

// No `kotlin { compilerOptions { } }` block: under AGP 9's built-in Kotlin,
// AGP aligns the Kotlin jvmTarget with `compileOptions` above on its own. If a
// later phase hits a "jvmTarget mismatch" error, that block (not the AGP 8-era
// `android { kotlinOptions { } }`, which no longer applies) is the fix.

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

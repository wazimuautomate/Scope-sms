plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    // Phase 8 — Room's annotation processor. KSP, never kapt: kapt is
    // incompatible with AGP 9's built-in Kotlin (memory.md). First use of this
    // catalog pin, so this push is what proves KSP 2.3.10 resolves against
    // AGP 9.2.1 — the catalog flags every "later phases" entry as researched but
    // never exercised by a build.
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.scopesms.autoreply"

    // Compile against 37 (Android 17), but target 36 — see targetSdk below.
    // Not a free choice: current AndroidX (core-ktx 1.19.0, activity, lifecycle)
    // refuses to build against anything lower, failing with "requires libraries
    // and applications that depend on it to compile against version 37 or
    // later". 37 is also AGP 9.2's ceiling.
    compileSdk = 37

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

        // Deliberately 36, not 37, even though Android 17 (API 37) went stable
        // in June 2026 and CLAUDE.md constraint 1 says "target latest stable".
        //
        // targetSdk is what opts the app into a platform's new *runtime*
        // behavior. Android 17's behavior changes are one month old and
        // untested here — and the changes most likely to matter to this app are
        // exactly the ones Android keeps tightening: background execution,
        // broadcast delivery, and SMS/telephony permissions. Every one of those
        // sits on the path between "a customer pays" and "the customer gets a
        // reply". Opting into them blind, on an app the agent's income depends
        // on, trades real risk for no benefit: nothing here needs an API 37
        // behavior, and this ships as a direct APK, so Play's targetSdk deadline
        // doesn't apply.
        //
        // This is a flagged decision, not a default. Phase 10 owns cross-version
        // testing and is where 37 should be evaluated against a real Android 17
        // device. See memory.md.
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
        // Stays at 17 even though CI now provisions JDK 21 for Robolectric
        // (see .github/workflows/build.yml). 17 is AGP's minimum, not its
        // maximum — a 21 toolchain emits 17 bytecode fine, and the app's own
        // compatibility floor is unchanged.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources/manifest to boot an Android
            // runtime in a JVM test. Without this, Phase 8's DAO tests fail at
            // startup rather than on an assertion.
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        // Enabled for BuildConfig.APPLICATION_ID, asserted in
        // ArchitectureGuardTest. Off by default since AGP 8.
        buildConfig = true
    }
}

// Phase 8 — Room. Writes the schema JSON to app/schemas/, which is committed:
// it's what makes a migration test possible later and what puts a schema change
// in the diff instead of hiding it behind annotations. ScopeSmsDatabase has no
// destructive-migration fallback on purpose, so this is the record a future
// migration is written against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    // Room's own recommendation, and it matters here: it moves query errors from
    // "we hope no one ever inserts a bad row" to a build failure.
    arg("room.generateKotlin", "true")
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

    // Phase 1 — SIM selection and onboarding state. First real use of this
    // catalog pin (memory.md flags every "later phases" entry as researched but
    // never resolved by a build), so this push is what confirms it exists.
    implementation(libs.androidx.datastore.preferences)

    // Phase 8 — activity log + dashboard stats. room-ktx supplies the coroutine
    // and Flow support the DAO returns. Phases 3 and 5b add their tables to the
    // same database (see ScopeSmsDatabase) and need no extra dependency here.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    // Phase 8 — the log/stats tests use a real in-memory SQLite via Room, so the
    // SQL in ActivityLogDao is executed rather than trusted. That needs
    // Robolectric, and Robolectric against SDK 36+ needs JDK 21 — the CI workflow
    // is bumped to 21 in this branch. See memory.md.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

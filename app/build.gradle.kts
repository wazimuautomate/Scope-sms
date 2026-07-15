plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    // Room's compiler runs through KSP — needed by rules (3), templates (4),
    // the outbound queue (5b) and the activity log (8) alike. Never kapt: kapt
    // is incompatible with AGP 9's built-in Kotlin (see memory.md).
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

// Room's schema export. data/README.md is explicit that a shipped build must
// never use fallbackToDestructiveMigration() — once the agent is live, a
// destructive migration throws away their bundle rules and activity history.
// Real migrations need the schema JSON committed, so export it from the start.
//
// Deliberately no `unitTests { isReturnDefaultValues = true }` above: the
// engines are pure Kotlin behind ports precisely so they test on the JVM.
// Returning defaults would let a stray android.* call quietly return null
// instead of failing with "not mocked", hiding the day that property breaks.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.compose.material.icons.core)

    // Phase 1 — SIM selection and onboarding state. First real use of this
    // catalog pin (memory.md flags every "later phases" entry as researched but
    // never resolved by a build), so this push is what confirms it exists.
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Phase 5 — SCOPE SMS gateway client -------------------------------
    // Retrofit over Ktor: the catalog already researched and pinned it, the
    // gateway is three plain JSON POSTs (no streaming, no websockets), and
    // OkHttp is the better-understood client on the low-end Android 11 devices
    // this ships to. Choice recorded in memory.md.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)

    // --- Room — the single AppDatabase -------------------------------------
    // Source of truth for pricing rules (3), message templates (4), the
    // outbound send queue (5b) and the activity log (8). All four entities live
    // in one database; see data/AppDatabase.kt before adding another.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Declared rather than inherited transitively through lifecycle: domain/
    // and di/ use Flow and CoroutineScope directly, and a transitive version
    // bump shouldn't be able to silently move them.
    implementation(libs.kotlinx.coroutines.core)

    // --- Phase 5b — outbound queue ----------------------------------------
    implementation(libs.androidx.work.runtime.ktx)

    // --- Test -------------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Coordinate renamed in OkHttp 5.x — mockwebserver3-junit4, not the old
    // com.squareup.okhttp3:mockwebserver (see memory.md).
    testImplementation(libs.mockwebserver3.junit4)

    // Room-backed tests (cache sync, log/stats) run real SQL against a real
    // in-memory SQLite rather than trusting it: a wrong column name or a bad
    // boolean-sum idiom compiles fine and returns confidently wrong numbers on
    // the agent's dashboard. That needs Robolectric, and Robolectric against
    // SDK 36+ needs JDK 21 — build.yml is bumped to 21. See memory.md.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
}

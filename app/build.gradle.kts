plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    // Room's compiler runs through KSP. Never kapt — kapt is incompatible with
    // AGP 9's built-in Kotlin (see gradle/libs.versions.toml).
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Enabled for BuildConfig.APPLICATION_ID, asserted in
        // ArchitectureGuardTest. Off by default since AGP 8.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources/manifest to inflate an
            // Application. Only the Room cache-sync tests use it; everything in
            // domain/ is JVM-pure and unaffected.
            isIncludeAndroidResources = true
        }
    }
}

// Room writes its schema JSON here, one file per version.
//
// data/README.md commits to real migrations from the first release onward:
// once the agent is running this on their live business, a destructive
// migration throws away their bundle prices and history. Migrations can't be
// written or verified without these checked-in schemas, so the export is
// mandatory, not documentation.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // Room — source of truth for pricing rules (Phase 3) and message templates
    // (Phase 4). The send queue (5b) and activity log (8) extend the same
    // database; see data/db/ScopeSmsDatabase.kt before adding an entity.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Declared rather than inherited transitively through lifecycle: domain/
    // and di/ use Flow and CoroutineScope directly, and a transitive version
    // bump shouldn't be able to silently move them.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // Only for the Room-backed sync tests, pinned to SDK 30. See the catalog.
    testImplementation(libs.robolectric)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    // Room's compiler runs through KSP — needed by rules (3), templates (4),
    // the outbound queue (5b) and the activity log (8) alike. Never kapt: kapt
    // is incompatible with AGP 9's built-in Kotlin (see memory.md).
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tricreta.scopesms"

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
        applicationId = "com.tricreta.scopesms"

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

        // Semantic version + build number, surfaced in Settings and compared
        // against update.json by the in-app update check.
        //
        // versionCode is the monotonic integer Android's installer uses to decide
        // an install is an update; the release workflow FAILS if a tag's
        // versionCode is not strictly greater than the one currently published in
        // update.json. Bump BOTH for a release and tag the commit `v<versionName>`
        // — the release workflow verifies the tag matches versionName, because a
        // Release labelled v1.1.0 whose APK reports 1.0.0 makes the update prompt
        // reappear forever.
        //
        // 1.0.0 / versionCode 1 is the first permanent release under the
        // com.tricreta.scopesms identity. Bug fix → 1.0.1 (code 2); backward-
        // compatible feature → 1.1.0; major/breaking → 2.0.0. versionCode only
        // ever increases.
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default app label. Build types override it (see below) so a debug build
        // installs as "Scope SMS Debug" beside the real app. Substituted into
        // AndroidManifest.xml as ${appLabel}.
        manifestPlaceholders["appLabel"] = "Scope SMS"

        // The in-app updater reads this manifest to learn the latest version, its
        // APK URL and SHA-256. The GitHub *contents API* endpoint, not
        // raw.githubusercontent.com: the repo is PRIVATE, and raw.githubusercontent
        // returns 404 to an unauthenticated request and does not accept a token at
        // all. The contents API does — with the Bearer token below and
        // `Accept: application/vnd.github.raw`, the body is update.json itself.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://api.github.com/repos/wazimuautomate/Scope-sms/contents/update.json?ref=main\"",
        )

        // Read-only GitHub token the in-app updater uses to reach the PRIVATE
        // release repo (manifest fetch + release-asset download). Supplied at
        // build time as the UPDATE_READ_TOKEN env var / Gradle property from a CI
        // secret — NEVER committed (CLAUDE.md constraint 7). Absent (a local build,
        // or CI before the secret is added) → empty → the updater degrades to
        // "manual updates only" rather than erroring, and no secret lands in git.
        // GitHub tokens are [A-Za-z0-9_] only, so no string escaping is needed.
        val updateReadToken = (project.findProperty("UPDATE_READ_TOKEN") as String?)
            ?: System.getenv("UPDATE_READ_TOKEN")
            ?: ""
        buildConfigField("String", "UPDATE_READ_TOKEN", "\"$updateReadToken\"")
    }

    // Release signing, from the permanent keystore CI materialises out of GitHub
    // Secrets (ANDROID_KEYSTORE_BASE64 / ANDROID_KEYSTORE_PASSWORD /
    // ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD). The workflow decodes the base64
    // secret to a file and passes its path as ANDROID_KEYSTORE_PATH.
    //
    // Configured only when the env vars are present, so an ordinary
    // `assembleRelease` on a machine without the secrets produces an *unsigned*
    // APK rather than one silently signed with the debug key. A debug-signed
    // "release" would install fine and then refuse every future real update with
    // a signature mismatch — on the agent's phone, holding their live data.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")

                // v1 too: minSdk is 30 so v2/v3 always apply, but some OEM
                // installers on this app's target handsets still look for v1.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false

            // Developer-only build, cleanly separated from the real app. Its own
            // applicationId suffix and label mean it installs *beside* the
            // release app (com.tricreta.scopesms.debug vs com.tricreta.scopesms)
            // and can never be confused with it or mistaken for an update to it.
            // Keeps Android's default per-machine debug key — debug builds are
            // never distributed and never used to update a release install.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Scope SMS Debug"
        }
        release {
            // Signed by the tag-triggered release workflow. Null when the secrets
            // aren't present — see signingConfigs above for why that is
            // deliberately an unsigned APK rather than a debug-signed one.
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["appLabel"] = "Scope SMS"

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        // A release must be cuttable on demand to get a fix to the agent — a
        // pre-existing lint warning must not block shipping. Lint still RUNS in
        // CI (`./gradlew lint`) and its HTML report is uploaded; it just doesn't
        // fail the build, and checkReleaseBuilds is off so `assembleRelease`
        // isn't gated by lintVitalRelease either. Tighten to gating once a
        // lint-baseline.xml is captured. Recorded in memory.md.
        abortOnError = false
        checkReleaseBuilds = false
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

    // Compose UI test, run on the JVM through Robolectric. The BOM aligns the
    // ui-test artifacts with the rest of Compose. ui-test-manifest is debug-only
    // (it merges the empty host activity createComposeRule() launches into the
    // debug manifest and must never reach the release APK). Together these let
    // TemplatesScreenTest run Compose's real measure/layout pass off-device — the
    // one thing 276 pure-JVM tests could not do, and where the Messages-tab crash
    // has hidden for three rounds.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

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

    // --- Phase 10 — instrumented smoke tests -------------------------------
    // Deliberately minimal. The JVM suite is the primary safety net; these cover
    // only what a JVM cannot answer — chiefly the Android Keystore, which has no
    // JVM equivalent and is where the OEM failures live. See SmokeTest.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}

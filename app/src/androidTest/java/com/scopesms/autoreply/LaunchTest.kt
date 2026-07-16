package com.scopesms.autoreply

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 — the app opens without crashing.
 *
 * ## What this catches that nothing else does
 * Every other test in this project stops at the seam below the UI. 276 JVM tests
 * prove the parser, the rules, the templates and the queue; `SmokeTest` proves
 * the graph and the Keystore. **None of them compose a single pixel**, and a
 * Compose app can compile perfectly and still die on first frame — a missing
 * string resource, a bad `stringResource` format argument, a ViewModel factory
 * that throws, a theme referencing a colour that isn't there.
 *
 * That failure mode is uniquely bad here: it is invisible to CI, invisible to
 * the developer with no Android Studio, and the first person to see it is the
 * agent, on the phone they run their business from.
 *
 * Reaching RESUMED means `setContent` ran, `ScopeSmsTheme` resolved,
 * `ScopeSmsApp` composed, `AppViewModel` was constructed through its factory,
 * and DataStore's first read came back — the whole cold-start path, which on a
 * fresh install lands on onboarding.
 *
 * ## What this is not
 * Not the Phase 7 exit criterion. That asks a human to click through both
 * light and dark and judge whether the screens match the design intent, and no
 * automated test can answer it. This only says the app doesn't fall over on
 * launch, which is the floor, not the bar.
 */
@RunWith(AndroidJUnit4::class)
class LaunchTest {

    @Test
    fun theAppReachesResumedWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /**
     * And survives a config change.
     *
     * Recreation re-runs the whole composition against retained ViewModels, which
     * is where `rememberSaveable` state and a re-collected DataStore flow can
     * disagree. Cheap to check, and rotating the phone is not an exotic thing for
     * an agent to do.
     */
    @Test
    fun theAppSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}

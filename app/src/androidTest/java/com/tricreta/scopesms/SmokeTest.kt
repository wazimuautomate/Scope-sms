package com.tricreta.scopesms

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tricreta.scopesms.data.settings.GatewayCredentialsStore
import com.tricreta.scopesms.data.settings.KeystoreCrypto
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.GatewayProvider
import com.tricreta.scopesms.telephony.SmsReceiver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 10 — the handful of things that can only be true on a real Android
 * runtime, and are catastrophic when they aren't.
 *
 * ## Why these tests and no others
 * 276 JVM tests already cover the parser, the rules engine, the templates, the
 * gateway's failure taxonomy and the burst behaviour. Re-running those on an
 * emulator would cost ten minutes a push to prove the same things again.
 *
 * What the JVM cannot prove is exactly what this file tests: that the app's
 * object graph constructs on a real device, that the receiver the system will
 * actually instantiate exists and doesn't crash, and that the **Android
 * Keystore** — which has no JVM equivalent and is the single most
 * device-specific thing in the app — can really encrypt and decrypt on this API
 * level. `memory.md` is explicit that keystore behaviour is where the OEM
 * failures live; a unit test with a fake `Crypto` proves the store's logic and
 * nothing about the phone.
 *
 * Deliberately no gateway calls and no UI driving: the first would send a real
 * SMS and cost the agent money, and the second needs a human to judge whether
 * the screen is right (BUILD-PLAN Phase 7's exit criterion says so).
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The app's own Application is installed and the graph builds.
     *
     * `AppContainer.from` throws by design if `android:name` is missing from the
     * manifest — which would silently break ingestion at 2am and is invisible to
     * every JVM test.
     */
    @Test
    fun theObjectGraphBuildsOnDevice() {
        val container = AppContainer.from(context)
        assertNotNull(container.settings)
        assertNotNull(container.simReader)
        assertNotNull(container.batteryOptimization)
    }

    /** Room opens for real, with the real schema, on this API level. */
    @Test
    fun theDatabaseOpens() {
        val db = AppContainer.from(context).database
        assertTrue(db.openHelper.writableDatabase.isOpen)
    }

    /**
     * The Android Keystore round-trips the agent's API key.
     *
     * The one piece of this app with no JVM equivalent and a documented history
     * of OEM-specific breakage. If this fails on a handset, that agent's replies
     * never send — so it is worth an emulator run per API level.
     */
    @Test
    fun theKeystoreEncryptsAndDecrypts() {
        val crypto = KeystoreCrypto()
        val secret = "sk_live_not_a_real_key_0123456789"

        val encrypted = crypto.encrypt(secret)
        assertNotEquals("the ciphertext must not be the plaintext", secret, encrypted)
        assertEquals(secret, crypto.decrypt(encrypted))
    }

    /**
     * GCM uses a fresh IV per encryption.
     *
     * Two encryptions of the same key must not produce the same bytes. If they
     * did, the key is being encrypted with a reused IV — which under GCM is not
     * a subtle weakness but a break.
     */
    @Test
    fun eachEncryptionUsesAFreshIv() {
        val crypto = KeystoreCrypto()
        assertNotEquals(crypto.encrypt("same input"), crypto.encrypt("same input"))
    }

    /** The credential store saves and reads back through the real Keystore. */
    @Test
    fun credentialsSurviveARoundTripThroughTheRealStore() = runBlocking {
        val store = GatewayCredentialsStore.create(context)
        val provider = GatewayProvider.BLAZETECH
        try {
            val saved = store.save(provider, GatewayCredentials(apiKey = "test-key-abc", senderId = "SCOPE"))
            assertTrue("the keystore refused to store credentials", saved)

            val read = withTimeout(TIMEOUT_MS) { store.credentials(provider) }
            assertEquals("test-key-abc", read?.apiKey)
            assertEquals("SCOPE", read?.senderId)
        } finally {
            // Never leave a key behind on a device — even a fake one on an
            // emulator that outlives the run.
            store.clear(provider)
        }
    }

    /**
     * The receiver the system will construct exists, and a junk broadcast can't
     * crash it.
     *
     * BUILD-PLAN Phase 9: malformed SMS must log and skip, never crash. An
     * intent with no PDUs is the cheapest version of that, and it also proves the
     * class is instantiable by name — the thing the manifest promises the OS.
     */
    @Test
    fun theReceiverSurvivesAnEmptyBroadcast() {
        val receiver = SmsReceiver()
        receiver.onReceive(context, Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
        receiver.onReceive(context, Intent("com.example.SOMETHING_ELSE"))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

package com.tricreta.scopesms.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.GatewayProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The provider-scoping this class exists for is the highest-stakes part of the
 * BlazeTech→dual-gateway change: a bug here silently breaks an existing agent's
 * live BlazeTech sending the moment they update. Every case below maps to one
 * clause of that guarantee.
 *
 * Uses a hand-rolled fake [DataStore] and [GatewayCredentialsStore.Crypto] —
 * neither needs an Android Keystore or real disk I/O, and the class's own
 * `Crypto` seam exists precisely so tests don't need one (see the class doc).
 */
class GatewayCredentialsStoreTest {

    private val dataStore = FakeDataStore()
    private val store = GatewayCredentialsStore(dataStore, FakeCrypto())

    // Legacy keys, exactly as GatewayCredentialsStore's own (private) constants
    // name them — DataStore preference keys are equal by name, so a second
    // `stringPreferencesKey("gateway_api_key")` here reads/writes the same slot.
    private val legacyApiKeyKey = stringPreferencesKey("gateway_api_key")
    private val legacySenderIdKey = stringPreferencesKey("gateway_sender_id")

    @Test
    fun `BlazeTech and HostPinnacle credentials are independent`() = runTest {
        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("bt-key", "BT_SENDER"))
        store.save(GatewayProvider.HOSTPINNACLE, GatewayCredentials("hp-key", "HP_SENDER"))

        assertThat(store.credentials(GatewayProvider.BLAZETECH))
            .isEqualTo(GatewayCredentials("bt-key", "BT_SENDER"))
        assertThat(store.credentials(GatewayProvider.HOSTPINNACLE))
            .isEqualTo(GatewayCredentials("hp-key", "HP_SENDER"))

        // Saving one must never have touched the other.
        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("bt-key-2", "BT_SENDER_2"))
        assertThat(store.credentials(GatewayProvider.HOSTPINNACLE))
            .isEqualTo(GatewayCredentials("hp-key", "HP_SENDER"))
    }

    @Test
    fun `a value written under the old unscoped keys is readable via credentials(BLAZETECH) before any scoped save`() =
        runTest {
            // Simulates every install from before this feature shipped: only the
            // legacy pair exists, nothing has ever been saved under the new
            // scoped BlazeTech keys.
            writeLegacyPair("legacy-key", "LEGACY_SENDER")

            val creds = store.credentials(GatewayProvider.BLAZETECH)

            assertThat(creds).isEqualTo(GatewayCredentials("legacy-key", "LEGACY_SENDER"))
        }

    @Test
    fun `the legacy fallback does not apply to HostPinnacle`() = runTest {
        // HostPinnacle never existed before this feature, so it has nothing to
        // fall back to even when a legacy BlazeTech pair is present.
        writeLegacyPair("legacy-key", "LEGACY_SENDER")

        assertThat(store.credentials(GatewayProvider.HOSTPINNACLE)).isNull()
    }

    @Test
    fun `after save(BLAZETECH), the new scoped key wins even if a legacy value is also present`() = runTest {
        writeLegacyPair("legacy-key", "LEGACY_SENDER")

        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("new-key", "NEW_SENDER"))

        assertThat(store.credentials(GatewayProvider.BLAZETECH))
            .isEqualTo(GatewayCredentials("new-key", "NEW_SENDER"))
    }

    @Test
    fun `clear(BLAZETECH) removes both the scoped and legacy keys`() = runTest {
        writeLegacyPair("legacy-key", "LEGACY_SENDER")
        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("new-key", "NEW_SENDER"))

        store.clear(GatewayProvider.BLAZETECH)

        assertThat(store.credentials(GatewayProvider.BLAZETECH)).isNull()
        val prefs = dataStore.data.first()
        assertThat(prefs[legacyApiKeyKey]).isNull()
        assertThat(prefs[legacySenderIdKey]).isNull()
    }

    @Test
    fun `clear(HOSTPINNACLE) does not touch BlazeTech's legacy pair`() = runTest {
        writeLegacyPair("legacy-key", "LEGACY_SENDER")
        store.save(GatewayProvider.HOSTPINNACLE, GatewayCredentials("hp-key", "HP_SENDER"))

        store.clear(GatewayProvider.HOSTPINNACLE)

        assertThat(store.credentials(GatewayProvider.HOSTPINNACLE)).isNull()
        // BlazeTech's legacy-backed credentials are untouched.
        assertThat(store.credentials(GatewayProvider.BLAZETECH))
            .isEqualTo(GatewayCredentials("legacy-key", "LEGACY_SENDER"))
    }

    @Test
    fun `isConfigured is true via the legacy fallback and stays true after a scoped save`() = runTest {
        assertThat(store.isConfigured(GatewayProvider.BLAZETECH).first()).isFalse()

        writeLegacyPair("legacy-key", "LEGACY_SENDER")
        assertThat(store.isConfigured(GatewayProvider.BLAZETECH).first()).isTrue()

        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("new-key", "NEW_SENDER"))
        assertThat(store.isConfigured(GatewayProvider.BLAZETECH).first()).isTrue()
    }

    @Test
    fun `senderId reflects the legacy fallback until a scoped save overrides it`() = runTest {
        assertThat(store.senderId(GatewayProvider.BLAZETECH).first()).isNull()

        writeLegacyPair("legacy-key", "LEGACY_SENDER")
        assertThat(store.senderId(GatewayProvider.BLAZETECH).first()).isEqualTo("LEGACY_SENDER")

        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("new-key", "NEW_SENDER"))
        assertThat(store.senderId(GatewayProvider.BLAZETECH).first()).isEqualTo("NEW_SENDER")
    }

    @Test
    fun `userId round-trips for HostPinnacle and stays null for BlazeTech`() = runTest {
        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("bt-key", "BT_SENDER"))
        store.save(
            GatewayProvider.HOSTPINNACLE,
            GatewayCredentials(apiKey = "hp-password", senderId = "HP_SENDER", userId = "hp-user"),
        )

        assertThat(store.credentials(GatewayProvider.HOSTPINNACLE))
            .isEqualTo(GatewayCredentials(apiKey = "hp-password", senderId = "HP_SENDER", userId = "hp-user"))
        assertThat(store.userId(GatewayProvider.HOSTPINNACLE).first()).isEqualTo("hp-user")

        // BlazeTech never has a userid — no legacy fallback exists for it, and
        // this save didn't provide one.
        assertThat(store.credentials(GatewayProvider.BLAZETECH))
            .isEqualTo(GatewayCredentials("bt-key", "BT_SENDER"))
        assertThat(store.userId(GatewayProvider.BLAZETECH).first()).isNull()
    }

    @Test
    fun `clear(HOSTPINNACLE) removes its saved userId`() = runTest {
        store.save(
            GatewayProvider.HOSTPINNACLE,
            GatewayCredentials(apiKey = "hp-password", senderId = "HP_SENDER", userId = "hp-user"),
        )

        store.clear(GatewayProvider.HOSTPINNACLE)

        assertThat(store.userId(GatewayProvider.HOSTPINNACLE).first()).isNull()
        assertThat(store.credentials(GatewayProvider.HOSTPINNACLE)).isNull()
    }

    @Test
    fun `re-saving HostPinnacle without a userId clears the previously saved one`() = runTest {
        store.save(
            GatewayProvider.HOSTPINNACLE,
            GatewayCredentials(apiKey = "hp-password", senderId = "HP_SENDER", userId = "hp-user"),
        )

        // Simulates re-saving through a hypothetical path with no username —
        // the stale userid must not linger and get sent under a new save.
        store.save(GatewayProvider.HOSTPINNACLE, GatewayCredentials(apiKey = "hp-password-2", senderId = "HP_SENDER"))

        assertThat(store.userId(GatewayProvider.HOSTPINNACLE).first()).isNull()
    }

    @Test
    fun `scopedTo binds a GatewayCredentialsProvider to exactly one provider`() = runTest {
        store.save(GatewayProvider.BLAZETECH, GatewayCredentials("bt-key", "BT_SENDER"))
        store.save(GatewayProvider.HOSTPINNACLE, GatewayCredentials("hp-key", "HP_SENDER"))

        val blazeTechScoped = store.scopedTo(GatewayProvider.BLAZETECH)
        val hostPinnacleScoped = store.scopedTo(GatewayProvider.HOSTPINNACLE)

        assertThat(blazeTechScoped.credentials()).isEqualTo(GatewayCredentials("bt-key", "BT_SENDER"))
        assertThat(hostPinnacleScoped.credentials()).isEqualTo(GatewayCredentials("hp-key", "HP_SENDER"))
    }

    private suspend fun writeLegacyPair(apiKey: String, senderId: String) {
        val crypto = FakeCrypto()
        dataStore.edit { prefs ->
            prefs[legacyApiKeyKey] = crypto.encrypt(apiKey)
            prefs[legacySenderIdKey] = crypto.encrypt(senderId)
        }
    }
}

/**
 * A [DataStore] over an in-memory [MutableStateFlow] — no disk, no Android
 * dependency. Faithful to the real contract: [updateData] is the only mutation
 * path, exactly like the on-disk implementation, so [androidx.datastore.preferences.core.edit]
 * (which [GatewayCredentialsStore] and this test both use) works unmodified.
 */
private class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/**
 * A reversible, deterministic stand-in for [KeystoreCrypto] — real AES/GCM needs
 * the Android Keystore, which JVM unit tests don't have. Round-trips exactly,
 * which is all [GatewayCredentialsStore] depends on its [GatewayCredentialsStore.Crypto]
 * doing.
 */
private class FakeCrypto : GatewayCredentialsStore.Crypto {
    override fun encrypt(plaintext: String): String = "$PREFIX$plaintext"

    override fun decrypt(encoded: String): String {
        require(encoded.startsWith(PREFIX)) { "Not encrypted by FakeCrypto: $encoded" }
        return encoded.removePrefix(PREFIX)
    }

    private companion object {
        const val PREFIX = "enc:"
    }
}

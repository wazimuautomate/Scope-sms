package com.tricreta.scopesms.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.GatewayCredentialsProvider
import com.tricreta.scopesms.network.GatewayProvider
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The agent's SCOPE gateway credentials, encrypted at rest, **scoped per
 * [GatewayProvider]**.
 *
 * Resolves **open decision 1** in `memory.md`. CLAUDE.md constraint 7: the API
 * key is a secret — never hardcoded, never committed, never logged.
 *
 * ## Provider-scoping, and the backward-compat fallback
 * BlazeTech and HostPinnacle each get their own key + sender ID, stored under
 * their own DataStore keys ([apiKeyKey]/[senderIdKey]) so switching the active
 * provider in Settings can never lose or overwrite the other one's saved
 * credentials.
 *
 * The original, unscoped keys ([KEY_API_KEY]/[KEY_SENDER_ID]) are kept
 * **permanently, read-only** — every install that existed before this feature
 * shipped has its live BlazeTech credentials stored there, and there is no
 * migration step for them to run. [credentials], [senderId] and [isConfigured]
 * fall back to the legacy keys for [GatewayProvider.BLAZETECH] only, and only
 * when the new scoped BlazeTech keys are absent — a **read-through fallback**,
 * not a one-time write migration, so it keeps working forever with zero agent
 * action and no "did the migration run" failure mode to worry about. [save]
 * only ever writes the new scoped keys, so the legacy pair becomes vestigial
 * (but harmless) the moment the agent re-saves BlazeTech, and [clear] for
 * BlazeTech removes both the scoped and legacy pair so a deliberate "clear"
 * fully clears rather than leaving the fallback to mask it.
 *
 * ## Shape of the crypto — unchanged
 * A single 256-bit AES key lives in the Keystore under [KEY_ALIAS] and never
 * leaves it — on most handsets it is held in the TEE, so the raw key is not
 * extractable even from a rooted device. Each write gets a fresh random IV
 * (`setRandomizedEncryptionRequired` is on by default and we never supply our
 * own), stored alongside the ciphertext. GCM authenticates as well as encrypts,
 * so a tampered value fails to decrypt rather than decrypting to garbage that
 * then gets POSTed to the gateway.
 *
 * **One shared Keystore key for both providers, deliberately.** GCM's
 * per-value random IV already isolates every ciphertext from every other one,
 * scoped or not, so a second Keystore alias would buy nothing but a second
 * thing that can go wrong on the OEM Keystore bugs this class's storage choice
 * already exists to route around.
 *
 * No `setUserAuthenticationRequired`: the queue worker sends replies while the
 * phone is locked in the agent's pocket, which is the entire point of the app.
 *
 * ## A decrypt failure is a prompt, never a crash
 * [GatewayCredentialsProvider] requires this, and it is the failure path that
 * matters. If the key is gone or the blob won't authenticate — OEM Keystore bug,
 * a wipe, an app-data restore onto a handset that can't decrypt it — [credentials]
 * returns null and the stored value is cleared. Null is a state the app already
 * models: the queue treats it as terminal ("gateway not set up"), the activity
 * log says so in the agent's own words, and Settings shows the fields empty for
 * re-entry. The one thing that must never happen is silently continuing to fail
 * to send.
 *
 * ## Own DataStore file, on purpose
 * Separate from [SettingsRepository]'s. Two DataStore instances over the *same
 * file* corrupt each other — that's the rule — but two instances over two files
 * are fine and independent. Keeping the secret in its own file also means
 * [clear] can drop it without touching the agent's SIM choice or toggles.
 *
 * ## Not a [GatewayCredentialsProvider] itself
 * That interface has no provider parameter, and a class whose every method now
 * takes one doesn't fit it. Use [scopedTo] to get a provider-bound adapter — one
 * per [com.tricreta.scopesms.network.SmsGateway] instance, each only ever
 * reading its OWN provider's credentials, never "whichever is active".
 */
class GatewayCredentialsStore(
    private val dataStore: DataStore<Preferences>,
    private val crypto: Crypto = KeystoreCrypto(),
) {

    /**
     * True once the agent has saved a key and sender ID for [provider].
     *
     * Deliberately does not decrypt: onboarding and Settings ask this on every
     * recomposition, and it only needs to know whether a value exists.
     */
    fun isConfigured(provider: GatewayProvider): Flow<Boolean> = dataStore.data
        .safe()
        .map { prefs ->
            val scoped = prefs[apiKeyKey(provider)] != null && prefs[senderIdKey(provider)] != null
            scoped || (legacyFallbackApplies(provider) && hasLegacyPair(prefs))
        }

    /**
     * The sender ID for display, or null.
     *
     * Encrypted like the key even though it isn't secret — it's stamped on every
     * SMS the customer receives. It rides in the same blob because splitting it
     * out would buy nothing and add a second failure mode.
     */
    fun senderId(provider: GatewayProvider): Flow<String?> = dataStore.data
        .safe()
        .map { prefs -> storedSenderId(prefs, provider)?.let { decryptOrNull(it) } }

    /**
     * The credentials for [provider], or null when unset or undecryptable.
     *
     * Called on the queue's send path, once per drain — but only for the ONE
     * provider a given job/gateway instance cares about; see [scopedTo].
     */
    suspend fun credentials(provider: GatewayProvider): GatewayCredentials? = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.safe().first()
        val storedKey = storedApiKey(prefs, provider)
        val storedSender = storedSenderId(prefs, provider)

        // Nothing stored: the agent hasn't finished setup for this provider. A
        // normal state — e.g. they configured BlazeTech but never touched
        // HostPinnacle.
        if (storedKey == null || storedSender == null) return@withContext null

        val apiKey = decryptOrNull(storedKey)
        val senderId = decryptOrNull(storedSender)

        if (apiKey.isNullOrBlank() || senderId.isNullOrBlank()) {
            // Stored but unreadable — the Keystore key is gone (an OEM Keystore
            // bug on exactly the handsets this class's doc names, or an app-data
            // restore onto a phone that can't decrypt it).
            //
            // Clearing is not tidying up; it is the entire recovery path. Left
            // in place, isConfigured stays true because the *bytes* are still
            // there, so Home shows no warning, Settings shows a masked key, and
            // the app insists it is set up while every single send fails
            // terminally on InvalidApiKey. Dropping the value flips isConfigured
            // to false, which surfaces the "gateway not set up" prompt and gets
            // the agent to re-enter it — the outcome GatewayCredentialsProvider
            // requires and this class promises.
            Log.e(TAG, "Stored gateway credentials for $provider are unreadable; cleared for re-entry.")
            clear(provider)
            return@withContext null
        }

        GatewayCredentials(apiKey = apiKey, senderId = senderId)
    }

    /**
     * Stores credentials for [provider], replacing any previous pair saved
     * under its own scoped keys. Never touches the other provider's credentials
     * or (for BlazeTech) the legacy unscoped keys — those are read-only from
     * here on, see the class doc.
     *
     * @return false if encryption failed — the Keystore is unusable on this
     *   handset. Settings must surface that rather than claiming a save that
     *   didn't happen, which would leave the agent thinking they're set up while
     *   every reply fails.
     */
    suspend fun save(provider: GatewayProvider, credentials: GatewayCredentials): Boolean =
        withContext(Dispatchers.IO) {
            val encryptedKey = encryptOrNull(credentials.apiKey) ?: return@withContext false
            val encryptedSender = encryptOrNull(credentials.senderId) ?: return@withContext false

            try {
                dataStore.edit { prefs ->
                    prefs[apiKeyKey(provider)] = encryptedKey
                    prefs[senderIdKey(provider)] = encryptedSender
                }
                true
            } catch (e: IOException) {
                Log.e(TAG, "Could not write gateway credentials for $provider.", e)
                false
            }
        }

    /**
     * Forgets [provider]'s credentials. Used by Settings and after an
     * undecryptable read.
     *
     * For [GatewayProvider.BLAZETECH] this also removes the legacy unscoped
     * keys — a deliberate "clear" must fully clear, not leave the read-through
     * fallback masking it and quietly un-clearing the credentials on next read.
     */
    suspend fun clear(provider: GatewayProvider) {
        try {
            dataStore.edit { prefs ->
                prefs.remove(apiKeyKey(provider))
                prefs.remove(senderIdKey(provider))
                if (legacyFallbackApplies(provider)) {
                    prefs.remove(KEY_API_KEY)
                    prefs.remove(KEY_SENDER_ID)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not clear gateway credentials for $provider.", e)
        }
    }

    /**
     * A [GatewayCredentialsProvider] bound to one [provider] — what each
     * [com.tricreta.scopesms.network.SmsGateway] instance is built with. Each
     * gateway client only ever needs its OWN provider's credentials, never
     * "whichever is active" (that question belongs to
     * [com.tricreta.scopesms.network.GatewayRegistry] and
     * `SettingsRepository.activeGatewayProvider`, not to a single gateway client).
     */
    fun scopedTo(provider: GatewayProvider): GatewayCredentialsProvider =
        object : GatewayCredentialsProvider {
            override suspend fun credentials(): GatewayCredentials? =
                this@GatewayCredentialsStore.credentials(provider)
        }

    // --- Legacy-key fallback (BlazeTech only) --------------------------------

    /** Only BlazeTech existed before this feature; only it has legacy data to fall back to. */
    private fun legacyFallbackApplies(provider: GatewayProvider): Boolean =
        provider == GatewayProvider.BLAZETECH

    private fun hasLegacyPair(prefs: Preferences): Boolean =
        prefs[KEY_API_KEY] != null && prefs[KEY_SENDER_ID] != null

    private fun storedApiKey(prefs: Preferences, provider: GatewayProvider): String? =
        prefs[apiKeyKey(provider)]
            ?: prefs[KEY_API_KEY].takeIf { legacyFallbackApplies(provider) }

    private fun storedSenderId(prefs: Preferences, provider: GatewayProvider): String? =
        prefs[senderIdKey(provider)]
            ?: prefs[KEY_SENDER_ID].takeIf { legacyFallbackApplies(provider) }

    // --------------------------------------------------------------------------

    private fun encryptOrNull(plaintext: String): String? = try {
        crypto.encrypt(plaintext)
    } catch (e: GeneralSecurityException) {
        // Never log the plaintext or the exception's message — a Keystore
        // provider is entitled to put the value in it.
        Log.e(TAG, "Encrypting gateway credentials failed: ${e.javaClass.simpleName}")
        null
    }

    private fun decryptOrNull(stored: String): String? = try {
        crypto.decrypt(stored)
    } catch (e: GeneralSecurityException) {
        // Never log the exception's message — a Keystore provider is entitled to
        // put the value in it.
        Log.e(TAG, "Could not decrypt gateway credentials: ${e.javaClass.simpleName}")
        null
    } catch (e: IllegalArgumentException) {
        // Base64 that isn't. Same treatment: unusable is unusable.
        Log.e(TAG, "Stored gateway credentials are malformed.")
        null
    }

    /**
     * DataStore's own guidance: an [IOException] reading the file surfaces in the
     * Flow. Falling back to empty means "not configured", which the app already
     * handles, rather than throwing into the queue worker.
     */
    private fun Flow<Preferences>.safe(): Flow<Preferences> = catch { cause ->
        if (cause is IOException) {
            Log.e(TAG, "Could not read gateway credentials; treating as unset.", cause)
            emit(emptyPreferences())
        } else {
            throw cause
        }
    }

    /** Seam so the store's behaviour is testable without an Android Keystore. */
    interface Crypto {
        fun encrypt(plaintext: String): String
        fun decrypt(encoded: String): String
    }

    companion object {
        private const val TAG = "ScopeSms/Credentials"

        /**
         * Legacy, unscoped keys — **permanent, read-only from here on.** Every
         * install from before this feature shipped has its live BlazeTech
         * credentials stored under these. See the class doc for why this is a
         * read-through fallback rather than a migration.
         */
        private val KEY_API_KEY = stringPreferencesKey("gateway_api_key")
        private val KEY_SENDER_ID = stringPreferencesKey("gateway_sender_id")

        private fun apiKeyKey(provider: GatewayProvider) =
            stringPreferencesKey("gateway_api_key_${provider.name.lowercase()}")

        private fun senderIdKey(provider: GatewayProvider) =
            stringPreferencesKey("gateway_sender_id_${provider.name.lowercase()}")

        private val Context.credentialsDataStore by preferencesDataStore(name = "scope_sms_gateway")

        fun create(context: Context): GatewayCredentialsStore =
            GatewayCredentialsStore(context.applicationContext.credentialsDataStore)
    }
}

/**
 * AES/GCM through the Android Keystore. See [GatewayCredentialsStore] for why.
 *
 * Output is `Base64(iv || ciphertext)`. The IV is 12 bytes — GCM's standard, and
 * what the platform generates — so decryption can split them without a header.
 */
class KeystoreCrypto : GatewayCredentialsStore.Crypto {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV supplied: the key requires randomized encryption, so the
        // provider generates a fresh one per call. Reusing an IV under GCM is
        // catastrophic, and this is the API that makes it impossible.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "Expected a $IV_LENGTH-byte GCM IV, got ${iv.size}" }

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > IV_LENGTH) { "Ciphertext too short to hold an IV" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH),
        )

        // Throws AEADBadTagException (a GeneralSecurityException) if the blob was
        // tampered with or the key changed — which is exactly the signal the
        // caller turns into "prompt for re-entry".
        val plaintext = cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Deliberately absent: setUserAuthenticationRequired(true). The
                // queue sends replies while the phone is locked — requiring the
                // agent to unlock before a customer can be texted would defeat
                // the app.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "scope_sms_gateway_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}

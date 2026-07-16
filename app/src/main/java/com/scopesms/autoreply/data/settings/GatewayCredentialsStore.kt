package com.scopesms.autoreply.data.settings

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
import com.scopesms.autoreply.network.GatewayCredentials
import com.scopesms.autoreply.network.GatewayCredentialsProvider
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
 * The agent's SCOPE gateway credentials, encrypted at rest.
 *
 * Resolves **open decision 1** in `memory.md`. CLAUDE.md constraint 7: the API
 * key is a secret — never hardcoded, never committed, never logged.
 *
 * ## Why not `EncryptedSharedPreferences`
 * `androidx.security:security-crypto` is the obvious answer and it is a dead
 * end. Every API in it was deprecated at 1.1.0 ("in favour of existing platform
 * APIs and direct use of Android Keystore" — Google's own note), and it has
 * known **keyset-corruption crashes on Tecno/Infinix/itel/Xiaomi**, which is
 * precisely this app's market. A corrupted keyset means the agent's credentials
 * are unrecoverable and their replies stop going out, which is the worst outcome
 * this app has. So this does what Google now recommends: DataStore for
 * persistence, an Android Keystore AES/GCM key for the encryption.
 *
 * ## Shape of the crypto
 * A single 256-bit AES key lives in the Keystore under [KEY_ALIAS] and never
 * leaves it — on most handsets it is held in the TEE, so the raw key is not
 * extractable even from a rooted device. Each write gets a fresh random IV
 * (`setRandomizedEncryptionRequired` is on by default and we never supply our
 * own), stored alongside the ciphertext. GCM authenticates as well as encrypts,
 * so a tampered value fails to decrypt rather than decrypting to garbage that
 * then gets POSTed to the gateway.
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
 */
class GatewayCredentialsStore(
    private val dataStore: DataStore<Preferences>,
    private val crypto: Crypto = KeystoreCrypto(),
) : GatewayCredentialsProvider {

    /**
     * True once the agent has saved a key and sender ID.
     *
     * Deliberately does not decrypt: onboarding and Settings ask this on every
     * recomposition, and it only needs to know whether a value exists.
     */
    val isConfigured: Flow<Boolean> = dataStore.data
        .safe()
        .map { it[KEY_API_KEY] != null && it[KEY_SENDER_ID] != null }

    /**
     * The sender ID for display, or null.
     *
     * Encrypted like the key even though it isn't secret — it's stamped on every
     * SMS the customer receives. It rides in the same blob because splitting it
     * out would buy nothing and add a second failure mode.
     */
    val senderId: Flow<String?> = dataStore.data
        .safe()
        .map { prefs -> prefs[KEY_SENDER_ID]?.let { decryptOrNull(it) } }

    /**
     * The credentials, or null when unset or undecryptable.
     *
     * Called on the queue's send path, once per drain.
     */
    override suspend fun credentials(): GatewayCredentials? = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.safe().first()
        val apiKey = prefs[KEY_API_KEY]?.let { decryptOrNull(it) } ?: return@withContext null
        val senderId = prefs[KEY_SENDER_ID]?.let { decryptOrNull(it) } ?: return@withContext null

        if (apiKey.isBlank() || senderId.isBlank()) return@withContext null
        GatewayCredentials(apiKey = apiKey, senderId = senderId)
    }

    /**
     * Stores credentials, replacing any previous pair.
     *
     * @return false if encryption failed — the Keystore is unusable on this
     *   handset. Settings must surface that rather than claiming a save that
     *   didn't happen, which would leave the agent thinking they're set up while
     *   every reply fails.
     */
    suspend fun save(credentials: GatewayCredentials): Boolean = withContext(Dispatchers.IO) {
        val encryptedKey = encryptOrNull(credentials.apiKey) ?: return@withContext false
        val encryptedSender = encryptOrNull(credentials.senderId) ?: return@withContext false

        try {
            dataStore.edit { prefs ->
                prefs[KEY_API_KEY] = encryptedKey
                prefs[KEY_SENDER_ID] = encryptedSender
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Could not write gateway credentials.", e)
            false
        }
    }

    /** Forgets the credentials. Used by Settings and after an undecryptable read. */
    suspend fun clear() {
        try {
            dataStore.edit { prefs ->
                prefs.remove(KEY_API_KEY)
                prefs.remove(KEY_SENDER_ID)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not clear gateway credentials.", e)
        }
    }

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
        Log.e(TAG, "Stored gateway credentials could not be decrypted; clearing for re-entry.")
        null
    } catch (e: IllegalArgumentException) {
        // Base64 that isn't. Same treatment: unusable is unusable.
        Log.e(TAG, "Stored gateway credentials are malformed; clearing for re-entry.")
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

        private val KEY_API_KEY = stringPreferencesKey("gateway_api_key")
        private val KEY_SENDER_ID = stringPreferencesKey("gateway_sender_id")

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
